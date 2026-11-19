package de.aldes.pacsim;

import java.util.ArrayList;
import java.util.List;

/**
 * Framer for the frames exchanged between the installation and the clone.
 *
 * <pre>
 * 0: wait for a header byte: 0xFA, 0xFD, 0xFF or 0x02
 * 1: second header byte: FA FD, FD FA or FF FD
 * 2: third byte
 *    FA FD         : it is the length of the whole frame (header and sum
 *                    included), as {@code frame_feed()} in the clone reads it
 *    FD FA / FF FD : no length. The frame is collected until it looks valid
 *                    (see {@link #looksComplete(int)})
 * 3: collect the rest, complete once the length is reached
 * </pre>
 *
 * <p>Frames starting with 0x02 are Modbus-style and carry neither the
 * {@code FA FD} header nor a length byte nor a zero byte sum (protocol.md §3
 * and §6). They are collected and compared with the documented frames
 * ({@link AldesProtocol#MODBUS_FRAMES}); as soon as the collected bytes fit
 * none of them the attempt is dropped.
 *
 * <p>Evidence:
 *
 * <ul>
 *   <li>Length byte at FA FD: {@code pico.TEMPLATE} is {@code fd fa 58 ff 10}
 *       + 81 bytes + {@code fe 6c} = 88 bytes, length {@code 0x58} = 88.
 *   <li>No length byte at FD FA: the very same frame starts with
 *       {@code fd fa} and carries the 0x58 at byte 2. A rule "byte 2 is
 *       always the length" would therefore discard it.
 *   <li>No length byte at the installation header: {@code FF FD 5A FF 21}
 *       introduces a 90-byte 0x21 report ({@code report21.py}), 0x5A would be
 *       the wrong length. The report ends with 0xFE at index 88 and the
 *       checksum at 89.
 * </ul>
 *
 * <p>Therefore, for FD FA and FF FD, the frame is complete as soon as byte 3
 * is {@code 0xFF}, a {@code 0xFE} appears somewhere in the last quarter and,
 * from there on, the second-to-last byte is 0xFE and the checksum is zero
 * ({@link AldesProtocol#isSealed(byte[], int, int)}). Until then every byte is
 * appended. If the frame grows beyond {@link #FRAME_MAX}, the header was
 * garbage and the framer starts over.
 *
 * <p>More than {@link #INACTIVITY_MS} of silence discards a frame in progress
 * ({@code frame_flush_inactivity()}, as in the clone).
 */
public final class Framer {

    /** Upper bound from the clone ({@code FRAME_MAX}). */
    public static final int FRAME_MAX = 300;
    /** Minimum length from the clone ({@code frame_feed()}). */
    public static final int FRAME_MIN = 6;
    /** Silence in ms after which an incomplete frame is dropped. */
    public static final int INACTIVITY_MS = 20;

    private static final int HEADER_FA = 0xFA;
    private static final int HEADER_FD = 0xFD;
    private static final int HEADER_FF = 0xFF;
    /** Modbus-style header of the gateway (protocol.md §3/§6). */
    private static final int HEADER_MODBUS = 0x02;

    /** A clean frame closes with 0xFE ({@code framing_ok} in the clone). */
    private static final int SEAL_MARK = 0xFE;

    private static final int MODE_HUNT = 0;
    private static final int MODE_SECOND = 1;
    private static final int MODE_THIRD = 2;
    private static final int MODE_BODY = 3;
    private static final int MODE_SCAN = 4;
    private static final int MODE_MODBUS = 5;

    /** One completely read frame. */
    public record Frame(byte[] bytes, long receivedAtMs) {
        public int opcode() {
            return bytes[4] & 0xFF;
        }
    }

    private final byte[] store = new byte[FRAME_MAX + 16];
    private int filled;
    private int expected;
    private int mode;
    private long lastByteAtMs;

    public synchronized void reset() {
        filled = 0;
        expected = 0;
        mode = MODE_HUNT;
    }

    public synchronized boolean inFrame() {
        return mode != MODE_HUNT;
    }

    /** Pushes bytes through the framer and returns every complete frame. */
    public synchronized List<Frame> feed(byte[] input, int off, int n, long nowMs) {
        List<Frame> out = new ArrayList<>(1);
        for (int i = off; i < off + n; i++) {
            lastByteAtMs = nowMs;
            step(input[i] & 0xFF, out, nowMs);
        }
        return out;
    }

    public synchronized List<Frame> feed(byte[] input, long nowMs) {
        return feed(input, 0, input.length, nowMs);
    }

    private void step(int value, List<Frame> out, long nowMs) {
        switch (mode) {
            case MODE_HUNT:
                if (value == HEADER_FA || value == HEADER_FD || value == HEADER_FF) {
                    beginWith(value);
                } else if (value == HEADER_MODBUS) {
                    beginModbus(value);
                }
                break;
            case MODE_MODBUS:
                modbusStep(value, out, nowMs);
                break;
            case MODE_SECOND: {
                final int first = store[0] & 0xFF;
                if ((first == HEADER_FA && value == HEADER_FD)
                        || (first == HEADER_FD && value == HEADER_FA)
                        || (first == HEADER_FF && value == HEADER_FD)) {
                    store[1] = (byte) value;
                    filled = 2;
                    mode = MODE_THIRD;
                } else {
                    reset();
                    if (value == HEADER_FA || value == HEADER_FF) {
                        beginWith(value);
                    }
                }
                break;
            }
            case MODE_THIRD:
                store[2] = (byte) value;
                filled = 3;
                if ((store[0] & 0xFF) == HEADER_FA) {
                    // The only header with a length at byte 2.
                    takeLength(value, out, nowMs);
                } else {
                    mode = MODE_SCAN;
                }
                break;
            case MODE_SCAN:
                append(value, out, nowMs);
                break;
            case MODE_BODY:
                append(value, out, nowMs);
                if (filled >= expected) {
                    out.add(finish(nowMs));
                }
                break;
            default:
                reset();
                break;
        }
    }

    private void append(int value, List<Frame> out, long nowMs) {
        if (filled >= store.length) {
            // Longer than any allowed frame: the header was none.
            reset();
            return;
        }
        store[filled] = (byte) value;
        filled = filled + 1;
        if (mode == MODE_SCAN) {
            if (filled > FRAME_MAX) {
                reset();
            } else if (looksComplete(filled)) {
                expected = filled;
                out.add(finish(nowMs));
            }
        }
    }

    /**
     * Can a frame of length {@code n} already be complete?
     *
     * <p>Only the end is checked: byte 3 must be {@code 0xFF}, the frame must
     * close with 0xFE as the second-to-last byte and a zero checksum, and that
     * seal mark may not sit before the last quarter (otherwise the frame would
     * be cut short). A 0xFE inside the payload is ignored: the 0x22 report
     * carries arbitrary bytes there (Unix timestamp, temperatures).
     */
    private boolean looksComplete(int n) {
        if (n < FRAME_MIN || (store[3] & 0xFF) != HEADER_FF) {
            return false;
        }
        if (n - 2 < (3 * n) / 4 || (store[n - 2] & 0xFF) != SEAL_MARK) {
            return false;
        }
        return AldesProtocol.isSealed(store, 0, n);
    }

    private void beginModbus(int value) {
        store[0] = (byte) value;
        filled = 1;
        mode = MODE_MODBUS;
    }

    /**
     * Collects a 0x02 frame until it equals one of the documented frames. A
     * byte that fits none of them ends the attempt — the byte itself may
     * already start the next frame.
     */
    private void modbusStep(int value, List<Frame> out, long nowMs) {
        if (filled >= store.length) {
            reset();
            return;
        }
        store[filled] = (byte) value;
        filled = filled + 1;
        boolean prefix = false;
        for (byte[] candidate : AldesProtocol.MODBUS_FRAMES) {
            if (filled <= candidate.length && matchesPrefix(candidate, filled)) {
                if (filled == candidate.length) {
                    expected = filled;
                    out.add(finish(nowMs));
                    return;
                }
                prefix = true;
            }
        }
        if (!prefix) {
            reset();
            if (value == HEADER_FA || value == HEADER_FD || value == HEADER_FF) {
                beginWith(value);
            } else if (value == HEADER_MODBUS) {
                beginModbus(value);
            }
        }
    }

    private boolean matchesPrefix(byte[] candidate, int n) {
        for (int i = 0; i < n; i++) {
            if (store[i] != candidate[i]) {
                return false;
            }
        }
        return true;
    }

    private void beginWith(int value) {
        store[0] = (byte) value;
        filled = 1;
        mode = MODE_SECOND;
    }

    /** Length from byte 2 (FA FD only); resynchronizes on an absurd value. */
    private void takeLength(int length, List<Frame> out, long nowMs) {
        final boolean headerByte = length == HEADER_FA || length == HEADER_FD || length == HEADER_FF;
        if (length < FRAME_MIN || length > FRAME_MAX) {
            reset();
            if (headerByte) {
                // The byte may already be the header of the next frame.
                beginWith(length);
            }
            return;
        }
        expected = length;
        mode = MODE_BODY;
        if (filled > expected) {
            reset();
        } else if (filled == expected) {
            out.add(finish(nowMs));
        }
    }

    private Frame finish(long nowMs) {
        final byte[] frame = new byte[expected];
        System.arraycopy(store, 0, frame, 0, expected);
        reset();
        return new Frame(frame, nowMs);
    }

    /**
     * Drops a frame in progress when more than {@link #INACTIVITY_MS} passed
     * since the last byte ({@code frame_flush_inactivity}).
     *
     * @return {@code true} if an incomplete frame was dropped
     */
    public synchronized boolean flushIfIdle(long nowMs) {
        if (mode != MODE_HUNT && nowMs - lastByteAtMs > INACTIVITY_MS) {
            reset();
            return true;
        }
        return false;
    }
}
