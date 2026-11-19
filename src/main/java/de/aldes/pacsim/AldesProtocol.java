package de.aldes.pacsim;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Aldes protocol at byte level: frames, checksums, DOS/FAT time, 0x21 report.
 *
 * <p>The field layout comes from {@code report21.py}/{@code README.md} of the
 * community repository, so that pacsim produces the same bytes as the real
 * installation and the ESPHome clone decodes them:
 *
 * <pre>
 * 0   5   prefix FF FD 5A FF 21
 * 5  9   vacation departure, DOS/FAT 32 bit LE, 0 = not set
 * 9  13  vacation return, same format
 * 13 31  setpoints K1..K9, LE16 in 1/100 °C, 0xFFFF = not set
 * 31 53  reserved (00)
 * 53 71  temperatures K1..K9, LE16 signed in 1/100 °C
 * 71 73  reserved (00)
 * 73     ECS level, one byte in percent (0..100)
 * 74 77  reserved (00)
 * 77 81  installation clock, DOS/FAT 32 bit LE
 * 81 82  air mode 0..8
 * 82 83  ECS mode 0..2
 * 83 88  reserved (00)
 * 88     FE
 * 89     checksum = (256 - sum of the first 89) mod 256
 * </pre>
 *
 * <p>K1..K4 keep the offsets of {@code report21.py}; K5..K9 use the bytes the
 * reference report leaves unused (21..30 and 61..70). The clone still decodes
 * four zones and ignores the rest.
 *
 * <p>The standard monitoring report 0x22 (112 bytes) follows
 * {@code protocol.md}: fixed 26-byte trailer with the Unix timestamp at
 * trailer offset 14 (frame 100..103), {@code 0xFE} at 110 and the checksum at
 * 111. Its sensor page reuses the offsets of the 0x24 notes, which is an
 * assumption: {@code protocol.md} documents those offsets for 0x24, not for
 * 0x22.
 *
 * <p>The time programming 0x23 (119 bytes) is built only on request
 * ({@code FD FA 08 FF 42 22 FE A0}): header {@code FF FD 77 FF 23}, then four
 * programs of seven days with four LE bytes each from offset 5. The grid fills
 * the frame up to byte 116, so {@code 0xFE} and the checksum follow directly.
 *
 * <p>The clock is deliberately its own time base: on the reference
 * installation it runs slow, and the repository never converts it to host
 * time ("PAC calendar is not host time"). pacsim follows that and only lets
 * the clock advance.
 */
public final class AldesProtocol {

    public static final int REPORT_LEN = 90;
    public static final int WRITE_LEN = 88;
    /** Length of the standard monitoring report 0x22 (protocol.md). */
    public static final int STANDARD_LEN = 112;

    /** Number of zones the installation reports. */
    public static final int ZONE_COUNT = 9;
    /** First setpoint byte; zone i sits at {@code 13 + 2 * i}. */
    public static final int SETPOINT_BASE = 13;
    /** First measured-temperature byte; zone i sits at {@code 53 + 2 * i}. */
    public static final int TEMPERATURE_BASE = 53;
    /**
     * ECS level in percent, a single byte inside the otherwise unused block
     * 71..77. The installation reports it as {@code 0..100}.
     */
    public static final int ECS_LEVEL_OFFSET = 73;
    public static final int ECS_LEVEL_MIN = 0;
    public static final int ECS_LEVEL_MAX = 100;

    /** Prefix of a locally built report. */
    public static final byte[] REPORT_PREFIX = {(byte) 0xFF, (byte) 0xFD, 0x5A, (byte) 0xFF, 0x21};
    /**
     * Byte 2 of such a report. Unlike at {@code fd fa}, it is not a length
     * (the report is 90 bytes long) but the fixed tag 0x5A; the repository
     * carries the variant with the length at byte 8 from its captures
     * ({@code RX 5D..}), while locally we build the fixed header of
     * {@code report21.py}.
     */
    public static final int REPORT_LEN_BYTE = 0x5A;
    /** Index of the byte that carries the tag of a 0x21 report. */
    public static final int LENGTH_INDEX = 2;
    /** Header of a command: FD FA, length 0x58 (88), FF, opcode 0x10. */
    public static final byte[] WRITE_PREFIX = {(byte) 0xFD, (byte) 0xFA, 0x58, (byte) 0xFF, 0x10};
    /**
     * Report header as seen in captures: FD FA, then the real length
     * (90 = 0x5D) and the rest of the header FF 21. The clone accepts this
     * header as well.
     */
    public static final byte[] REPORT_PREFIX_ALT = {(byte) 0xFD, (byte) 0xFA, 0x5D, (byte) 0xFF, 0x21};

    /**
     * Standard monitoring report 0x22: byte 2 carries the real length
     * ({@code 0x70} = 112) — unlike the local 0x21, which uses the tag 0x5A.
     */
    public static final byte[] STANDARD_PREFIX = {(byte) 0xFF, (byte) 0xFD, 0x70, (byte) 0xFF, 0x22};
    /** First byte of the sensor page inside the 0x22 report. */
    public static final int SENSOR_BASE = 5;
    /** Length of the final block of every large report (protocol.md §8). */
    public static final int TRAILER_LEN = 26;
    /** Offset of the Unix timestamp inside that block: frame bytes 100..103. */
    public static final int TRAILER_TIMESTAMP_OFFSET = 14;
    /** {@code 0xFE} in front of the checksum of a 0x22 report. */
    public static final int STANDARD_SEAL_INDEX = STANDARD_LEN - 2;

    /**
     * Time programming 0x23: byte 2 carries the real length ({@code 0x77} =
     * 119) like the 0x22 page, then the grid of four programs.
     */
    public static final byte[] PROGRAM_PREFIX = {(byte) 0xFF, (byte) 0xFD, 0x77, (byte) 0xFF, 0x23};
    /** Length of the time-programming report 0x23 (protocol.md §4). */
    public static final int PROGRAM_LEN = 119;
    /** First byte of the program grid: 4 programs × 7 days × 4 bytes. */
    public static final int PROGRAM_BASE = 5;
    /** {@code 0xFE} in front of the checksum of a 0x23 report. */
    public static final int PROGRAM_SEAL_INDEX = PROGRAM_LEN - 2;

    // --------------------------------------------------- Session frames (§3)

    /** Ping of the installation: every 2..20 s, length 7 at byte 2. */
    public static final byte[] PING = {(byte) 0xFA, (byte) 0xFD, 0x07, (byte) 0xFF, 0x4A, (byte) 0xFE, (byte) 0xBB};
    /** Pong of the gateway. */
    public static final byte[] PONG = {(byte) 0xFD, (byte) 0xFA, 0x07, (byte) 0xFF, 0x13, (byte) 0xFE, (byte) 0xF2};
    /**
     * Init frame of the gateway: forces the installation out of debug mode
     * (175 bytes) into standard mode (112 bytes). It is a 0x02/Modbus-style
     * frame without the {@code FA FD} header and without a zero byte sum.
     */
    public static final byte[] INIT_STANDARD = {
            0x02, 0x03, 0x04, 0x20, 0x00, 0x01, 0x02, 0x42, (byte) 0xA2};
    /** Modbus read request for the setpoints (protocol.md §6). */
    public static final byte[] MODBUS_READ_SETPOINTS = {
            0x02, 0x03, 0x04, 0x20, 0x00, 0x01, (byte) 0x84, (byte) 0xC3};
    /** Every 0x02 frame the framer knows; anything else after 0x02 is dropped. */
    public static final byte[][] MODBUS_FRAMES = {MODBUS_READ_SETPOINTS, INIT_STANDARD};
    /**
     * Request of the gateway for the time programming (protocol.md §6): the ID
     * {@code 0x42} asks for the report following {@code 0x22}, i.e. the 0x23
     * page. pacsim answers this frame and nothing else with a 0x23.
     */
    public static final byte[] PROGRAM_REQUEST = {
            (byte) 0xFD, (byte) 0xFA, 0x08, (byte) 0xFF, 0x42, 0x22, (byte) 0xFE, (byte) 0xA0};

    /** Limits from pico.SETPOINT_MIN/MAX. */
    public static final double SETPOINT_MIN = 16.0;
    public static final double SETPOINT_MAX = 30.0;

    public static final String[] AIR_MODES = {
            "Arret",
            "Chauffage Confort",
            "Chauffage Eco",
            "Chauffage Programme A",
            "Chauffage Programme B",
            "Clim Confort",
            "Clim Boost",
            "Clim Programme C",
            "Clim Programme D",
    };

    public static final String[] ECS_MODES = {
            "Arret",
            "Marche",
            "Boost"
    };

    /** Time base of the installation: year, month, day, hour, minute, second. */
    public record PacClock(int year, int month, int day, int hour, int minute, int second) {

        public static PacClock of(LocalDateTime t) {
            return new PacClock(t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                    t.getHour(), t.getMinute(), t.getSecond());
        }

        public PacClock plusPacSeconds(long seconds) {
            // Own calendar arithmetic: the PAC clock shall only move forward,
            // without time zones and without host duration.
            LocalDateTime base = LocalDateTime.of(year, month, day, hour, minute, second);
            return of(base.plusSeconds(seconds));
        }

        public String iso() {
            return String.format("%04d-%02d-%02dT%02d:%02d:%02d", year, month, day, hour, minute, second);
        }
    }

    private AldesProtocol() {}

    // ------------------------------------------------------------------ Time

    /**
     * DOS/FAT date packed into a 32-bit field like {@code report21.decode_date}:
     * year since 1980 (7 bit), month (4), day (5), hour (5), minute (6),
     * second/2 (5). Seconds are truncated to 2 s steps.
     */
    public static int encodeFat(int year, int month, int day, int hour, int minute, int second) {
        return ((year - 1980) & 0x7F) << 25
                | (month & 0x0F) << 21
                | (day & 0x1F) << 16
                | (hour & 0x1F) << 11
                | (minute & 0x3F) << 5
                | ((second / 2) & 0x1F);
    }

    /** Reverse direction for display; {@code 0} means "not set". */
    public static PacClock decodeFat(int value) {
        if (value == 0) {
            return null;
        }
        return new PacClock(1980 + (value >>> 25), (value >>> 21) & 0x0F, (value >>> 16) & 0x1F,
                (value >>> 11) & 0x1F, (value >>> 5) & 0x3F, (value & 0x1F) * 2);
    }

    // ------------------------------------------------------------- Checksums

    /** Sum of all bytes modulo 256 — must be 0 for a valid frame. */
    public static int checksum(byte[] data, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len; i++) {
            sum += data[i] & 0xFF;
        }
        return sum & 0xFF;
    }

    /** Sets the last byte so that the frame sums up to 0. */
    public static void seal(byte[] frame) {
        int sum = checksum(frame, 0, frame.length - 1);
        frame[frame.length - 1] = (byte) ((0x100 - sum) & 0xFF);
    }

    public static boolean isSealed(byte[] frame, int off, int len) {
        if (len < 2 || off < 0 || off + len > frame.length) {
            return false;
        }
        return checksum(frame, off, len) == 0;
    }

    // --------------------------------------------------------------- Building

    /** State for a 0x21 report. Arrays must have length 4. */
    public record State(
            double[] setpoints,
            double[] temperatures,
            int air,
            int ecs,
            int ecsLevel,
            Integer vacationDeparture,
            Integer vacationReturn,
            PacClock clock) {

        public State verify() {
            if (setpoints.length != ZONE_COUNT || temperatures.length != ZONE_COUNT) {
                throw new IllegalArgumentException(ZONE_COUNT + " zones expected");
            }
            if (air < 0 || air >= AIR_MODES.length) {
                throw new IllegalArgumentException("air mode " + air + " outside 0..8");
            }
            if (ecs < 0 || ecs >= ECS_MODES.length) {
                throw new IllegalArgumentException("ECS mode " + ecs + " outside 0..2");
            }
            if (ecsLevel < ECS_LEVEL_MIN || ecsLevel > ECS_LEVEL_MAX) {
                throw new IllegalArgumentException("ECS level " + ecsLevel + " outside 0..100");
            }
            return this;
        }
    }

    /** Builds the 90-byte 0x21 frame as the installation sends it every 30 s. */
    public static byte[] buildReport(State state, long uptimeSeconds) {
        state.verify();
        byte[] r = new byte[REPORT_LEN];
        System.arraycopy(REPORT_PREFIX, 0, r, 0, REPORT_PREFIX.length);
        // The header FF FD 5A FF 21 is fixed and never overwritten: that is
        // how report21.py and parseReport() check the report.

        putLe32(r, 5, state.vacationDeparture() == null ? 0 : state.vacationDeparture());
        putLe32(r, 9, state.vacationReturn() == null ? 0 : state.vacationReturn());

        for (int i = 0; i < ZONE_COUNT; i++) {
            // "empty" is 0xFFFF, otherwise Math.round(NaN) = 0 would send a
            // zone that looks like 0.00 °C.
            putLe16(r, SETPOINT_BASE + 2 * i, hundredths(state.setpoints()[i]));
            putLe16(r, TEMPERATURE_BASE + 2 * i, hundredths(state.temperatures()[i]));
        }

        putLe32(r, 77, encodeFat(state.clock().year(), state.clock().month(), state.clock().day(),
                state.clock().hour(), state.clock().minute(), state.clock().second()));
        r[81] = (byte) state.air();
        r[82] = (byte) state.ecs();
        r[ECS_LEVEL_OFFSET] = (byte) state.ecsLevel();
        r[88] = (byte) 0xFE;
        seal(r);
        return r;
    }

    /** Returns the decoded report, or {@code null} when the check fails. */
    public static State parseReport(byte[] r) {
        if (r.length != REPORT_LEN || !isSealed(r, 0, r.length) || (r[88] & 0xFF) != 0xFE) {
            return null;
        }
        // At FD FA byte 2 differs (90 = 0x5D, the report arrived with 0x5A),
        // so only bytes 0, 1, 3 and 4 are compared.
        boolean prefixOk = ((r[0] & 0xFF) == 0xFF && (r[1] & 0xFF) == 0xFD
                && (r[3] & 0xFF) == 0xFF && (r[4] & 0xFF) == 0x21)
                || ((r[0] & 0xFF) == 0xFD && (r[1] & 0xFF) == 0xFA
                && (r[3] & 0xFF) == 0xFF && (r[4] & 0xFF) == 0x21);
        if (!prefixOk) {
            return null;
        }
        double[] sp = new double[ZONE_COUNT];
        double[] tp = new double[ZONE_COUNT];
        for (int i = 0; i < ZONE_COUNT; i++) {
            int s = le16(r, SETPOINT_BASE + 2 * i);
            sp[i] = s == 0xFFFF ? Double.NaN : s / 100.0;
            // report21.py checks for 65535 before casting to int16; otherwise
            // -1 = "unknown" would also be 0xFFFF and show up as -0.01 °C.
            int tRaw = le16(r, TEMPERATURE_BASE + 2 * i);
            tp[i] = tRaw == 0xFFFF ? Double.NaN : (short) tRaw / 100.0;
        }
        int dep = le32(r, 5);
        int ret = le32(r, 9);
        return new State(sp, tp, r[81] & 0xFF, r[82] & 0xFF, r[ECS_LEVEL_OFFSET] & 0xFF,
                dep == 0 ? null : dep, ret == 0 ? null : ret, decodeFat(le32(r, 77)));
    }

    // ------------------------------------------------- Standard report (0x22)

    /**
     * One sensor slot of the standard report: {@code offset} relative to the
     * sensor page, {@code raw} in 1/100 of {@code unit} when
     * {@code hundredths} is set (the three-way valve angle is a plain number).
     */
    public record Sensor(int offset, String label, String unit, int raw, boolean hundredths) {
        public double value() {
            return hundredths ? raw / 100.0 : raw;
        }
    }

    /**
     * Sensor map of the standard report. Offsets, labels and the "sensor
     * absent" value −40 °C come from the 0x24 notes in {@code protocol.md};
     * slots the notes leave unassigned stay {@code 0xFFFF} = "not set".
     */
    public static final Sensor[] STANDARD_SENSORS = {
            new Sensor(29, "Valve 3-way", "°", 45, false),
            new Sensor(45, "ThoA outdoor", "°C", 1200, true),
            new Sensor(47, "DHW top", "°C", 5200, true),
            new Sensor(49, "DHW bottom", "°C", 4500, true),
            new Sensor(57, "Th3 gas line", "°C", 3000, true),
            new Sensor(61, "ThoR1 outdoor exchanger", "°C", 800, true),
            new Sensor(63, "DHW exchanger pressure", "bar", 1215, true),
            new Sensor(65, "Sensor absent", "°C", -4000, true),
            new Sensor(75, "Compressor outlet", "°C", 6500, true),
    };

    /**
     * Builds the 112-byte standard monitoring report the installation sends
     * after the init frame. The trailer follows {@code protocol.md} §8: a
     * 26-byte block with the Unix timestamp at trailer offset 14 and zeros for
     * the status bytes, {@code 0xFE} at 110, checksum at 111.
     */
    public static byte[] buildStandard22(long unixSeconds) {
        byte[] f = new byte[STANDARD_LEN];
        System.arraycopy(STANDARD_PREFIX, 0, f, 0, STANDARD_PREFIX.length);
        final int trailer = STANDARD_LEN - TRAILER_LEN;
        Arrays.fill(f, SENSOR_BASE, trailer, (byte) 0xFF);
        for (Sensor s : STANDARD_SENSORS) {
            putLe16(f, SENSOR_BASE + s.offset(), s.raw() & 0xFFFF);
        }
        Arrays.fill(f, trailer, STANDARD_SEAL_INDEX, (byte) 0x00);
        putLe32(f, trailer + TRAILER_TIMESTAMP_OFFSET, (int) unixSeconds);
        f[STANDARD_SEAL_INDEX] = (byte) 0xFE;
        seal(f);
        return f;
    }

    /** {@code true} for a complete, sealed 112-byte 0x22 report. */
    public static boolean isStandard22(byte[] f) {
        return f.length == STANDARD_LEN
                && (f[0] & 0xFF) == 0xFF && (f[1] & 0xFF) == 0xFD && (f[4] & 0xFF) == 0x22
                && (f[STANDARD_SEAL_INDEX] & 0xFF) == 0xFE
                && isSealed(f, 0, f.length);
    }

    /** Unix timestamp of a 0x22 report, or {@code null} when it is invalid. */
    public static Integer standard22Timestamp(byte[] f) {
        if (!isStandard22(f)) {
            return null;
        }
        return le32(f, STANDARD_LEN - TRAILER_LEN + TRAILER_TIMESTAMP_OFFSET);
    }

    /** Raw sensor value at {@code offset} of the sensor page; 0xFFFF = not set. */
    public static int standard22Sensor(byte[] f, int offset) {
        return le16(f, SENSOR_BASE + offset);
    }

    // ------------------------------------------- Time programming (0x23)

    /**
     * Builds the 119-byte time-programming report: four programs A–D, each
     * with seven days starting on Monday, four LE bytes per day and one bit
     * per hour (bit 0 = 0 h, 1 = comfort).
     *
     * <p>The grid starts at byte 5 and fills the frame up to 116, so only
     * {@code 0xFE} and the checksum follow — the 26-byte trailer of §8 has no
     * room in this page and is not written.
     *
     * <p>Program-major order (A Mo..Su, then B, C, D) follows "Programs A, B,
     * C, D × 7 days, starting on Monday" in {@code protocol.md}.
     */
    public static byte[] buildProgram23(Programs programs) {
        byte[] f = new byte[PROGRAM_LEN];
        System.arraycopy(PROGRAM_PREFIX, 0, f, 0, PROGRAM_PREFIX.length);
        for (int program = 0; program < Programs.COUNT; program++) {
            for (int day = 0; day < Programs.DAYS; day++) {
                putLe32(f, PROGRAM_BASE + 4 * (program * Programs.DAYS + day),
                        programs.dayBits(program, day));
            }
        }
        f[PROGRAM_SEAL_INDEX] = (byte) 0xFE;
        seal(f);
        return f;
    }

    /** {@code true} for a complete, sealed 119-byte 0x23 report. */
    public static boolean isProgram23(byte[] f) {
        return f.length == PROGRAM_LEN
                && (f[0] & 0xFF) == 0xFF && (f[1] & 0xFF) == 0xFD && (f[4] & 0xFF) == 0x23
                && (f[PROGRAM_SEAL_INDEX] & 0xFF) == 0xFE
                && isSealed(f, 0, f.length);
    }

    /** Hour bits of one day inside a 0x23 report; bit 0 = 0 h, 1 = comfort. */
    public static int programDayBits(byte[] f, int program, int day) {
        return le32(f, PROGRAM_BASE + 4 * (program * Programs.DAYS + day));
    }

    // ---------------------------------------------------- Debug page (0x25)

    /** Length of the verbose debug page 0x25 (protocol.md §3). */
    public static final int DEBUG_LEN = 175;

    /** Index of the {@code 0xFE} in front of the checksum. */
    public static final int DEBUG_SEAL_INDEX = DEBUG_LEN - 2;

    /**
     * The 0x25 page exactly as captured in {@code protocol.md} §3. Its payload
     * is consumption history pacsim has no model for, so the capture goes out
     * unchanged; only the checksum is recomputed.
     */
    private static final byte[] DEBUG25_CAPTURE = parseHex(
            "FAFDAFFF2509000000000000002B000000C5070000000000001F250000090000"
                    + "00000000002A000000C507000000000000422400002000000000000000660000"
                    + "00C019000000000000145200001D000000000000002700000056170000000000"
                    + "00621F00001C00000000000000010000008816000000000000CE000000120000"
                    + "0000000000020000007C0E0000000000009C0100001700000000000000000000"
                    + "00821200000000000000000000FE29");

    /** A sealed 175-byte 0x25 debug page. */
    public static byte[] buildDebug25() {
        byte[] f = DEBUG25_CAPTURE.clone();
        seal(f);
        return f;
    }

    /** {@code true} for a complete, sealed 175-byte 0x25 debug page. */
    public static boolean isDebug25(byte[] f) {
        return f.length == DEBUG_LEN
                && (f[0] & 0xFF) == 0xFA && (f[1] & 0xFF) == 0xFD
                && (f[2] & 0xFF) == (DEBUG_LEN & 0xFF) && (f[4] & 0xFF) == 0x25
                && (f[DEBUG_SEAL_INDEX] & 0xFF) == 0xFE
                && isSealed(f, 0, f.length);
    }

    /** Parses "AABB…" into bytes. */
    private static byte[] parseHex(String hex) {
        byte[] f = new byte[hex.length() / 2];
        for (int i = 0; i < f.length; i++) {
            f[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return f;
    }

    // ------------------------------------------------------- Session frames

    /** Pong of the gateway, the answer to our ping. */
    public static boolean isPong(byte[] frame) {
        return Arrays.equals(frame, PONG);
    }

    /** Init frame of the gateway: switches the installation to standard mode. */
    public static boolean isInitStandard(byte[] frame) {
        return Arrays.equals(frame, INIT_STANDARD);
    }

    /** Modbus read request of the gateway for the setpoints. */
    public static boolean isModbusRead(byte[] frame) {
        return Arrays.equals(frame, MODBUS_READ_SETPOINTS);
    }

    /** Request of the gateway for the time programming: answered with 0x23. */
    public static boolean isProgramRequest(byte[] frame) {
        return Arrays.equals(frame, PROGRAM_REQUEST);
    }

    /** Any 0x02 frame, i.e. Modbus-style without the {@code FA FD} header. */
    public static boolean isModbus(byte[] frame) {
        return frame.length > 0 && (frame[0] & 0xFF) == 0x02;
    }

    /** A 0x21 report of the installation. */
    public static boolean isReport21(byte[] frame) {
        return frame.length == REPORT_LEN && (frame[4] & 0xFF) == 0x21;
    }

    /**
     * A request of the clone (client → installation): {@code FD FA} header and
     * {@code 0xFF} at byte 3, like the 88-byte 0x10 update request.
     */
    public static boolean isClientRequest(byte[] frame) {
        return frame.length >= 7
                && (frame[0] & 0xFF) == 0xFD && (frame[1] & 0xFF) == 0xFA
                && (frame[3] & 0xFF) == 0xFF;
    }

    // -------------------------------------------------------------- Commands

    /**
     * What a 0x10 command from the clone means. Each variant has a readable
     * name for the protocol window.
     */
    public sealed interface Command permits Write10, ProgramRequest, Raw {}

    /** One zone of a 0x10 frame: {@code zone} 1..9, target in °C. */
    public record Setpoint(int zone, double celsius) {}

    /** What the vacation block (bytes 5..12) of a 0x10 frame says. */
    public enum Vacation {
        /** All bytes {@code 0xFF}: the frame leaves the dates alone. */
        UNCHANGED,
        /** All bytes {@code 00}: "away" is cleared. */
        CLEARED,
        /** DOS/FAT dates: pacsim does not simulate writing them. */
        DATES
    }

    /**
     * An 88-byte 0x10 update request. Every field the frame sets is applied
     * together; {@code 0xFF} means "unchanged" (protocol.md §7).
     */
    public record Write10(List<Setpoint> setpoints, Integer air, Integer ecs, Vacation vacation)
            implements Command {

        /** No field set at all — no command pacsim could act on. */
        public boolean isEmpty() {
            return setpoints.isEmpty() && air == null && ecs == null && vacation == Vacation.UNCHANGED;
        }

        /** Short text for the protocol pane, e.g. {@code setpoint K2 = 22.00 °C | air = 1}. */
        public String summary() {
            List<String> parts = new ArrayList<>();
            for (Setpoint s : setpoints) {
                parts.add(String.format(Locale.ROOT, "setpoint K%d = %.2f °C", s.zone(), s.celsius()));
            }
            if (air != null) {
                parts.add("air = " + air);
            }
            if (ecs != null) {
                parts.add("ecs = " + ecs);
            }
            if (vacation == Vacation.CLEARED) {
                parts.add("away cleared");
            } else if (vacation == Vacation.DATES) {
                parts.add("away dates (not simulated)");
            }
            return String.join(" | ", parts);
        }
    }

    /** Read request for the time programming; answered with a 0x23 report. */
    public record ProgramRequest() implements Command {}
    public record Raw(int opcode, String hex) implements Command {}

    /**
     * Decodes the fields of an 88-byte 0x10 frame without checking the
     * checksum; {@code null} for any other length. Fields work like
     * {@code pico._blank()}: only the set bytes carry values, everything else
     * is 0xFF.
     */
    public static Write10 write10(byte[] f) {
        if (f.length != WRITE_LEN) {
            return null;
        }
        List<Setpoint> setpoints = new ArrayList<>();
        for (int i = 0; i < ZONE_COUNT; i++) {
            int raw = le16(f, SETPOINT_BASE + 2 * i);
            if (raw != 0xFFFF) {
                setpoints.add(new Setpoint(i + 1, raw / 100.0));
            }
        }
        Integer air = (f[81] & 0xFF) == 0xFF ? null : f[81] & 0xFF;
        Integer ecs = (f[82] & 0xFF) == 0xFF ? null : f[82] & 0xFF;
        boolean cleared = true;
        boolean untouched = true;
        for (int i = 5; i < 13; i++) {
            cleared &= f[i] == 0;
            untouched &= f[i] == (byte) 0xFF;
        }
        Vacation vacation = cleared ? Vacation.CLEARED
                : untouched ? Vacation.UNCHANGED : Vacation.DATES;
        return new Write10(List.copyOf(setpoints), air, ecs, vacation);
    }

    /**
     * Reads a command delivered by the clone's framer. Unknown opcodes are
     * returned as {@link Raw} so that the log shows them instead of swallowing
     * them.
     */
    public static Command parseCommand(byte[] f) {
        if (f.length < 7 || (f[f.length - 2] & 0xFF) != 0xFE || !isSealed(f, 0, f.length)) {
            return null;
        }
        int opcode = f[4] & 0xFF;
        if (opcode == 0x42) {
            // "The ID 42 requests the report following 22" (protocol.md §6).
            return (f[5] & 0xFF) == 0x22 ? new ProgramRequest() : new Raw(opcode, hex(f));
        }
        if (opcode == 0x10) {
            Write10 write = write10(f);
            return write == null || write.isEmpty() ? new Raw(opcode, hex(f)) : write;
        }
        return new Raw(opcode, hex(f));
    }

    // ----------------------------------------------------------------- Utils

    public static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    public static int le16(byte[] d, int off) {
        return (d[off] & 0xFF) | (d[off + 1] & 0xFF) << 8;
    }

    public static int le32(byte[] d, int off) {
        return (d[off] & 0xFF) | (d[off + 1] & 0xFF) << 8 | (d[off + 2] & 0xFF) << 16 | (d[off + 3] & 0xFF) << 24;
    }

    /** Value in hundredths, or {@code 0xFFFF} for "not set". */
    private static int hundredths(double celsius) {
        if (Double.isNaN(celsius)) {
            return 0xFFFF;
        }
        // Two's complement as in the captures (-3.5 °C = 0x2604).
        final long raw = Math.round(celsius * 100.0);
        final int clipped = (int) Math.max(-32768, Math.min(32767, raw));
        return clipped & 0xFFFF;
    }

    public static void putLe16(byte[] d, int off, int v) {
        d[off] = (byte) (v & 0xFF);
        d[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    public static void putLe32(byte[] d, int off, int v) {
        d[off] = (byte) (v & 0xFF);
        d[off + 1] = (byte) ((v >>> 8) & 0xFF);
        d[off + 2] = (byte) ((v >>> 16) & 0xFF);
        d[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    /** Unseparated hex like {@code frame.hex()}. */
    public static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
            sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
        }
        return sb.toString();
    }

    /** Log line for one frame. */
    public static String describe(int opcode, byte[] frame, boolean outgoing) {
        String dir = outgoing ? "TX" : "RX";
        String sum = isSealed(frame, 0, frame.length) ? "sum=OK" : "sum=BAD";
        return String.format("%s opcode=0x%02X len=%d %s  %s", dir, opcode, frame.length, sum, spacedHex(frame));
    }

    public static String spacedHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 3);
        for (int i = 0; i < d.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", d[i] & 0xFF));
        }
        return sb.toString();
    }

    public static String toAscii(byte[] d) {
        return new String(d, StandardCharsets.ISO_8859_1);
    }
}
