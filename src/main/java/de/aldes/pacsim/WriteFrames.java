package de.aldes.pacsim;

/**
 * Builds the 88-byte commands (0x10) the way the clone firmware and
 * {@code pico.py} do: a template with {@code 0xFF} in every field that is not
 * changed, exactly one change per frame.
 *
 * <p>Template ({@code pico.TEMPLATE}, captured 11 Jan 2026):
 *
 * <pre>
 * FD FA 58 FF 10  FF x 8 (vacation)  FF x 8 (setpoints K1..K4)  FF x 30 (21..50)
 * 00 x 20 (51..70)  FF x 13 (71..83)  FE  checksum
 * </pre>
 *
 * <p>The capture carries a setpoint ({@code 6C 07}) in bytes 13..14;
 * {@code pico.py} masks such fields to {@code FF FF} via {@code _blank()}
 * before sending. The byte layout here is that of the commands that actually
 * go out on the wire. Bytes 21..30 are {@code FF} in the template as well, so
 * the setpoints of K5..K9 (and the temperatures K1..K9 of the 0x21 report,
 * 53..70) use the same "unchanged" mask as K1..K4.
 */
public final class WriteFrames {

    private static final byte[] TEMPLATE = buildTemplate();

    private WriteFrames() {}

    private static byte[] buildTemplate() {
        byte[] t = new byte[AldesProtocol.WRITE_LEN];
        System.arraycopy(AldesProtocol.WRITE_PREFIX, 0, t, 0, 5);
        int i = 5;
        for (; i < 5 + 8 + 8; i++) {
            t[i] = (byte) 0xFF;      // vacation 5..12 and setpoints 13..20
        }
        for (; i < 51; i++) {
            t[i] = (byte) 0xFF;      // 21..50
        }
        for (; i < 71; i++) {
            t[i] = 0x00;             // 51..70
        }
        for (; i < 84; i++) {
            t[i] = (byte) 0xFF;      // 71..83
        }
        t[84] = 0x00;
        t[85] = 0x00;
        t[86] = (byte) 0xFE;
        t[87] = 0x00;
        AldesProtocol.seal(t);
        return t;
    }

    /** Copy of the template; {@code _blank()} masks setpoints, air and ECS. */
    private static byte[] blank() {
        byte[] f = TEMPLATE.clone();
        java.util.Arrays.fill(f, AldesProtocol.SETPOINT_BASE,
                AldesProtocol.SETPOINT_BASE + 2 * AldesProtocol.ZONE_COUNT, (byte) 0xFF);
        f[81] = (byte) 0xFF;
        f[82] = (byte) 0xFF;
        return f;
    }

    private static long pyRound(double v) {
        return v >= 0.0 ? (long) (v + 0.5) : -(long) (0.5 - v);
    }

    /** {@code frame_setpoint}: 0.5 °C steps, range 16..30 °C, zones K1..K9. */
    public static byte[] setpoint(int zone, double celsius) {
        if (zone < 1 || zone > AldesProtocol.ZONE_COUNT) {
            throw new IllegalArgumentException("zone 1.." + AldesProtocol.ZONE_COUNT);
        }
        if (!(celsius >= AldesProtocol.SETPOINT_MIN && celsius <= AldesProtocol.SETPOINT_MAX)) {
            throw new IllegalArgumentException("setpoint outside 16..30 °C: " + celsius);
        }
        long hundredths = pyRound(pyRound(celsius * 2.0) * 50.0);
        byte[] f = blank();
        int off = AldesProtocol.SETPOINT_BASE + 2 * (zone - 1);
        f[off] = (byte) (hundredths & 0xFF);
        f[off + 1] = (byte) ((hundredths >> 8) & 0xFF);
        AldesProtocol.seal(f);
        return f;
    }

    /** {@code frame_air}. */
    public static byte[] air(int mode) {
        if (mode < 0 || mode >= AldesProtocol.AIR_MODES.length) {
            throw new IllegalArgumentException("air mode 0..8");
        }
        byte[] f = blank();
        f[81] = (byte) mode;
        AldesProtocol.seal(f);
        return f;
    }

    /** {@code frame_ecs}. */
    public static byte[] ecs(int mode) {
        if (mode < 0 || mode >= AldesProtocol.ECS_MODES.length) {
            throw new IllegalArgumentException("ECS mode 0..2");
        }
        byte[] f = blank();
        f[82] = (byte) mode;
        AldesProtocol.seal(f);
        return f;
    }

    /** {@code frame_vacation_clear}: bytes 5..12 set to 00. */
    public static byte[] vacationClear() {
        byte[] f = blank();
        java.util.Arrays.fill(f, 5, 13, (byte) 0x00);
        AldesProtocol.seal(f);
        return f;
    }

    /** Template without a change (tests/protocol log only). */
    public static byte[] template() {
        return TEMPLATE.clone();
    }
}
