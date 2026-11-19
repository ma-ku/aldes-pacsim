package de.aldes.pacsim;

import java.util.Random;

/**
 * Keep-alive dialogue from {@code protocol.md} §3.
 *
 * <p>The installation (pacsim) pings every 2 to 20 s with
 * {@link AldesProtocol#PING}; the gateway answers with
 * {@link AldesProtocol#PONG}. Afterwards the gateway may send the init frame
 * {@link AldesProtocol#INIT_STANDARD}, which forces the installation out of
 * debug mode (175 bytes) into the standard dialogue — pacsim then reports the
 * 0x21 setpoint/temperature frame instead of the 0x25 debug page.
 *
 * <p>Three datagrams are possible: the verbose 0x25 debug page, the 0x21
 * report the clone decodes (the state after a session starts and after the
 * init frame) and the 0x22 monitoring page. The window switches between them
 * at any time.
 *
 * <p>The ping cadence is drawn per ping, because the reference installation
 * pings irregularly inside the documented window. The dialogue is off until
 * the window switches it on: a simulator that pings on its own is the
 * exception, not the rule.
 *
 * <p>Not thread-safe: called from the Swing event dispatch thread only.
 */
public final class KeepAlive {

    /** Window from protocol.md §3: a ping every 2 to 20 s. */
    public static final long MIN_INTERVAL_MS = 2_000;
    public static final long MAX_INTERVAL_MS = 20_000;

    /** Which datagram the installation sends. */
    public enum Mode {
        /** 0x25, 175 bytes — the verbose debug page (protocol.md §3). */
        DEBUG,
        /** 0x21, 90 bytes — the report the clone decodes; the start state. */
        REPORT,
        /** 0x22, 112 bytes — the standard monitoring page. */
        STANDARD
    }

    /** What a received frame meant for the session. */
    public enum FrameKind { PONG, INIT_STANDARD, MODBUS_READ, OTHER }

    private final Random random;

    private boolean enabled;
    private Mode mode = Mode.REPORT;
    private boolean pinged;
    private long lastPingAtMs;
    private long lastPongAtMs;
    private long intervalMs = MAX_INTERVAL_MS;
    private int pings;
    private int pongs;
    private int inits;
    private int modbusReads;

    public KeepAlive() {
        this(new Random());
    }

    /** Seeded source for tests. */
    public KeepAlive(Random random) {
        this.random = random;
    }

    /** A new connection is a new session: report mode, no ping history. */
    public void resetSession() {
        mode = Mode.REPORT;
        pinged = false;
        lastPingAtMs = 0;
        lastPongAtMs = 0;
        intervalMs = MAX_INTERVAL_MS;
        pings = 0;
        pongs = 0;
        inits = 0;
        modbusReads = 0;
    }

    public Mode mode() {
        return mode;
    }

    /** Off by default: the window switches the dialogue on. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Switches the dialogue on or off. Both directions start a new session, so
     * switching on pings at once and switching off leaves no history behind.
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        resetSession();
    }

    /** GUI: switches the datagram without waiting for a new session. */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** The first ping goes out immediately, afterwards every 2..20 s. */
    public boolean pingDue(long nowMs) {
        return enabled && (!pinged || nowMs - lastPingAtMs >= intervalMs);
    }

    /** Records the ping and draws the interval until the next one. */
    public void onPingSent(long nowMs) {
        pinged = true;
        lastPingAtMs = nowMs;
        pings++;
        intervalMs = MIN_INTERVAL_MS
                + (long) (random.nextDouble() * (MAX_INTERVAL_MS - MIN_INTERVAL_MS + 1));
    }

    /** Interval that was drawn for the next ping (ms). */
    public long intervalMs() {
        return intervalMs;
    }

    /**
     * Classifies a received frame and updates the session: a pong refreshes the
     * contact, the init frame leaves debug mode behind and switches to the
     * 0x21 reports.
     */
    public FrameKind onFrame(byte[] frame, long nowMs) {
        if (AldesProtocol.isPong(frame)) {
            pongs++;
            lastPongAtMs = nowMs;
            return FrameKind.PONG;
        }
        if (AldesProtocol.isInitStandard(frame)) {
            inits++;
            mode = Mode.REPORT;
            return FrameKind.INIT_STANDARD;
        }
        if (AldesProtocol.isModbusRead(frame)) {
            modbusReads++;
            return FrameKind.MODBUS_READ;
        }
        return FrameKind.OTHER;
    }

    public int pings() {
        return pings;
    }

    public int pongs() {
        return pongs;
    }

    public int inits() {
        return inits;
    }

    public int modbusReads() {
        return modbusReads;
    }

    /** "debug (0x25, 175 B)", "report (0x21, 90 B)" or "standard (0x22, 112 B)". */
    public String modeText() {
        return switch (mode) {
            case DEBUG -> "debug (0x25, 175 B)";
            case REPORT -> "report (0x21, 90 B)";
            case STANDARD -> "standard (0x22, 112 B)";
        };
    }

    /** One line for the window: pings, pongs, mode. */
    public String statusText(long nowMs) {
        if (!enabled) {
            return "keep-alive off — no pings";
        }
        StringBuilder sb = new StringBuilder("keep-alive: ");
        if (pings == 0) {
            sb.append("no ping sent yet");
        } else {
            sb.append(pings).append(pings == 1 ? " ping" : " pings");
            if (pongs == 0) {
                sb.append(", no pong yet");
            } else {
                sb.append(", ").append(pongs).append(pongs == 1 ? " pong" : " pongs")
                        .append(", last ").append(Math.max(0, (nowMs - lastPongAtMs) / 1000)).append(" s ago");
            }
        }
        if (inits > 0) {
            sb.append(", ").append(inits).append(inits == 1 ? " init frame" : " init frames");
        }
        if (modbusReads > 0) {
            sb.append(", ").append(modbusReads).append(modbusReads == 1 ? " Modbus read" : " Modbus reads");
        }
        return sb.append(" — mode ").append(modeText()).toString();
    }
}
