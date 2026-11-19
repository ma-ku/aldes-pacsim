package de.aldes.pacsim;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte level against the references of the community repository:
 * {@code report21.py}, {@code report_clock.py}, {@code pico.TEMPLATE} and the
 * test vectors from {@code esphome/tests/aldes_host_tests.c}.
 */
class AldesProtocolTest {

    @Test
    void templateMatchesPicoByteForByte() {
        // pico.TEMPLATE, captured 11 Jan 2026, 88 bytes:
        // 0..4 header, 5..50 FF, 51..70 00, 71..83 FF, 84..85 00, 86 FE, 87 checksum.
        byte[] expected = new byte[88];
        System.arraycopy(hex("fdfa58ff10"), 0, expected, 0, 5);
        java.util.Arrays.fill(expected, 5, 51, (byte) 0xFF);
        java.util.Arrays.fill(expected, 51, 71, (byte) 0x00);
        java.util.Arrays.fill(expected, 71, 84, (byte) 0xFF);
        expected[84] = 0x00;
        expected[85] = 0x00;
        expected[86] = (byte) 0xFE;
        expected[87] = (byte) -AldesProtocol.checksum(expected, 0, 87);
        assertArrayEquals(expected, WriteFrames.template());
        assertEquals((byte) 88, WriteFrames.template()[2], "length 0x58");
        assertEquals(0, AldesProtocol.checksum(expected, 0, expected.length));
    }

    @Test
    void setpointGridAndLimits() {
        byte[] f = WriteFrames.setpoint(1, 16.0);
        assertEquals((byte) 0x40, f[13]);
        assertEquals((byte) 0x06, f[14]);

        f = WriteFrames.setpoint(4, 30.0);
        assertEquals((byte) 0xB8, f[19]);
        assertEquals((byte) 0x0B, f[20]);

        // K9 uses the bytes the reference template leaves at 0xFF (29/30).
        f = WriteFrames.setpoint(9, 22.0);
        assertEquals((byte) 0x98, f[29]);
        assertEquals((byte) 0x08, f[30]);

        for (int zone = 1; zone <= AldesProtocol.ZONE_COUNT; zone++) {
            for (int half = 0; half <= 28; half++) {
                double t = 16.0 + 0.5 * half;
                f = WriteFrames.setpoint(zone, t);
                int off = AldesProtocol.SETPOINT_BASE + 2 * (zone - 1);
                assertEquals((int) Math.round(t * 100), AldesProtocol.le16(f, off), "zone " + zone + " " + t);
                assertTrue(AldesProtocol.isSealed(f, 0, f.length));
                assertEquals((byte) 0xFF, f[81]);
                assertEquals((byte) 0xFF, f[82]);
            }
        }

        assertThrows(IllegalArgumentException.class, () -> WriteFrames.setpoint(0, 20.0));
        assertThrows(IllegalArgumentException.class, () -> WriteFrames.setpoint(10, 20.0));
        assertThrows(IllegalArgumentException.class, () -> WriteFrames.setpoint(1, 15.5));
        assertThrows(IllegalArgumentException.class, () -> WriteFrames.setpoint(1, 30.5));
        assertThrows(IllegalArgumentException.class, () -> WriteFrames.setpoint(1, Double.NaN));
        // 21.3 is rounded to 21.5 as in frame_setpoint().
        assertEquals(2150, AldesProtocol.le16(WriteFrames.setpoint(2, 21.3), 15));
    }

    @Test
    void airEcsAndVacation() {
        for (int m = 0; m < AldesProtocol.AIR_MODES.length; m++) {
            byte[] f = WriteFrames.air(m);
            assertEquals((byte) m, f[81]);
            assertEquals((byte) 0xFF, f[82]);
            assertTrue(AldesProtocol.isSealed(f, 0, f.length));
        }
        for (int m = 0; m < AldesProtocol.ECS_MODES.length; m++) {
            byte[] f = WriteFrames.ecs(m);
            assertEquals((byte) m, f[82]);
            assertTrue(AldesProtocol.isSealed(f, 0, f.length));
        }
        assertThrows(IllegalArgumentException.class, () -> WriteFrames.air(9));
        assertThrows(IllegalArgumentException.class, () -> WriteFrames.ecs(3));

        byte[] clear = WriteFrames.vacationClear();
        for (int i = 5; i < 13; i++) {
            assertEquals(0, clear[i], "byte " + i);
        }
        assertTrue(AldesProtocol.isSealed(clear, 0, clear.length));
        assertTrue(AldesProtocol.parseCommand(clear) instanceof AldesProtocol.Write10 w
                && w.vacation() == AldesProtocol.Vacation.CLEARED
                && w.setpoints().isEmpty() && w.air() == null && w.ecs() == null);
    }

    @Test
    void commandsAreRecognised() {
        assertTrue(AldesProtocol.parseCommand(WriteFrames.setpoint(3, 22.5))
                instanceof AldesProtocol.Write10 s
                && s.setpoints().equals(List.of(new AldesProtocol.Setpoint(3, 22.5))));
        assertTrue(AldesProtocol.parseCommand(WriteFrames.setpoint(9, 19.5))
                instanceof AldesProtocol.Write10 s9
                && s9.setpoints().equals(List.of(new AldesProtocol.Setpoint(9, 19.5))));
        assertTrue(AldesProtocol.parseCommand(WriteFrames.air(6))
                instanceof AldesProtocol.Write10 a && a.air() == 6 && a.ecs() == null);
        assertTrue(AldesProtocol.parseCommand(WriteFrames.ecs(2))
                instanceof AldesProtocol.Write10 e && e.ecs() == 2 && e.air() == null);

        byte[] broken = WriteFrames.setpoint(1, 20.0);
        broken[40] ^= 0x01;
        assertNull(AldesProtocol.parseCommand(broken), "broken checksum must be rejected");
    }

    @Test
    void dosFatTimeLikeDecodeDate() {
        int v = AldesProtocol.encodeFat(2026, 1, 11, 14, 32, 40);
        // fat_clock() from aldes_host_tests.c
        int expected = (2026 - 1980) << 25 | 1 << 21 | 11 << 16 | 14 << 11 | 32 << 5 | 40 / 2;
        assertEquals(expected, v);
        AldesProtocol.PacClock c = AldesProtocol.decodeFat(v);
        assertEquals(2026, c.year());
        assertEquals(1, c.month());
        assertEquals(11, c.day());
        assertEquals(14, c.hour());
        assertEquals(32, c.minute());
        assertEquals(40, c.second());
        assertNull(AldesProtocol.decodeFat(0), "0 = not set");
        // Odd seconds are truncated, as the bit field does.
        assertEquals(40, AldesProtocol.decodeFat(AldesProtocol.encodeFat(2026, 1, 11, 14, 32, 41)).second());
    }

    @Test
    void pacClockOnlyMovesForward() {
        AldesProtocol.PacClock c = new AldesProtocol.PacClock(2026, 12, 31, 23, 59, 40);
        AldesProtocol.PacClock next = c.plusPacSeconds(30);
        assertEquals("2027-01-01T00:00:10", next.iso());
    }

    @Test
    void reportRoundtrip() {
        AldesProtocol.State s = new AldesProtocol.State(
                zones(20.0, 21.5, Double.NaN, 30.0, 18.0, 19.0, 20.5, 22.0, 23.5),
                zones(19.4, 22.1, Double.NaN, -3.5, 16.0, 17.25, 18.0, 19.0, 20.0),
                3, 1, 42,
                AldesProtocol.encodeFat(2026, 12, 24, 8, 0, 0),
                AldesProtocol.encodeFat(2027, 1, 6, 18, 0, 0),
                new AldesProtocol.PacClock(2026, 1, 11, 14, 32, 40));
        byte[] r = AldesProtocol.buildReport(s, 0);
        assertEquals(90, r.length);
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFD, 0x5A, (byte) 0xFF, 0x21},
                Arrays.copyOf(r, 5));
        // Like report21.py: 0xFE at index 88, checksum at 89.
        assertEquals((byte) 0xFE, r[88]);
        // The header is fixed (report21.py), so byte 2 is the tag 0x5A.
        assertEquals((byte) 0x5A, r[AldesProtocol.LENGTH_INDEX]);
        assertTrue(AldesProtocol.isSealed(r, 0, r.length));

        AldesProtocol.State back = AldesProtocol.parseReport(r);
        assertNotNull(back);
        for (int i = 0; i < AldesProtocol.ZONE_COUNT; i++) {
            if (Double.isNaN(s.setpoints()[i])) {
                assertTrue(Double.isNaN(back.setpoints()[i]));
            } else {
                assertEquals(s.setpoints()[i], back.setpoints()[i], 1e-9);
            }
            if (Double.isNaN(s.temperatures()[i])) {
                // 0xFFFF must not show up as -0.01 °C
                assertTrue(Double.isNaN(back.temperatures()[i]));
            } else {
                assertEquals(s.temperatures()[i], back.temperatures()[i], 1e-9);
            }
        }
        assertEquals(3, back.air());
        assertEquals(1, back.ecs());
        assertEquals(42, back.ecsLevel());
        assertEquals(42, r[AldesProtocol.ECS_LEVEL_OFFSET] & 0xFF);
        assertEquals(s.vacationDeparture(), back.vacationDeparture());
        assertEquals(s.vacationReturn(), back.vacationReturn());
        // Seconds are halved in the bit field: 40 fits on both sides.
        assertEquals(s.clock().iso(), back.clock().iso());
    }

    @Test
    void reportAndCommandAreToldApart() {
        byte[] report = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(20, 20, 20, 20), zones(19, 19, 19, 19), 1, 1, 0, null, null,
                new AldesProtocol.PacClock(2026, 1, 1, 0, 0, 0)), 0);
        byte[] command = WriteFrames.setpoint(5, 23.0);

        assertTrue(AldesProtocol.isReport21(report));
        assertFalse(AldesProtocol.isClientRequest(report));

        assertTrue(AldesProtocol.isClientRequest(command), "88 bytes, opcode 0x10");
        assertFalse(AldesProtocol.isReport21(command));

        assertFalse(AldesProtocol.isClientRequest(AldesProtocol.INIT_STANDARD));
        assertFalse(AldesProtocol.isClientRequest(AldesProtocol.buildStandard22(0)));
        assertFalse(AldesProtocol.isClientRequest(new byte[0]));
    }

    @Test
    void damagedReportIsRejected() {
        byte[] r = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(20, 20, 20, 20), zones(19, 19, 19, 19), 1, 1, 0, null, null,
                new AldesProtocol.PacClock(2026, 1, 1, 0, 0, 0)), 0);
        byte[] broken = r.clone();
        broken[20] ^= 0x04;
        assertNull(AldesProtocol.parseReport(broken));
        byte[] shortFrame = Arrays.copyOf(r, 89);
        assertNull(AldesProtocol.parseReport(shortFrame));
        byte[] noFe = r.clone();
        noFe[88] = 0x00;
        AldesProtocol.seal(noFe);
        assertNull(AldesProtocol.parseReport(noFe));
    }

    @Test
    void ecsLevelSitsAtByte73() {
        byte[] r = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(20, 20, 20, 20), zones(19, 19, 19, 19), 1, 2, 65, null, null,
                new AldesProtocol.PacClock(2026, 1, 1, 0, 0, 0)), 0);
        assertEquals(65, r[AldesProtocol.ECS_LEVEL_OFFSET] & 0xFF);
        // The bytes around the level stay reserved (00).
        for (int i = 71; i < 77; i++) {
            if (i != AldesProtocol.ECS_LEVEL_OFFSET) {
                assertEquals(0, r[i], "byte " + i + " is reserved");
            }
        }
        AldesProtocol.State back = AldesProtocol.parseReport(r);
        assertNotNull(back);
        assertEquals(65, back.ecsLevel());
        assertEquals(0, AldesProtocol.buildReport(new AldesProtocol.State(
                zones(20, 20, 20, 20), zones(19, 19, 19, 19), 0, 0, 0, null, null,
                new AldesProtocol.PacClock(2026, 1, 1, 0, 0, 0)), 0)[AldesProtocol.ECS_LEVEL_OFFSET]);
        assertThrows(IllegalArgumentException.class, () -> AldesProtocol.buildReport(
                new AldesProtocol.State(zones(20, 20, 20, 20), zones(19, 19, 19, 19), 0, 0, 101,
                        null, null, new AldesProtocol.PacClock(2026, 1, 1, 0, 0, 0)), 0),
                "101 % is outside 0..100");
    }

    @Test
    void hexAndDescription() {
        assertEquals("FFFD5AFF21", AldesProtocol.hex(Arrays.copyOf(AldesProtocol.REPORT_PREFIX, 5)));
        byte[] f = {0x01, 0x02};
        assertEquals("01 02", AldesProtocol.spacedHex(f));
        assertTrue(AldesProtocol.describe(0x01, f, true).startsWith("TX opcode=0x01 len=2"));
    }

    @Test
    void standardReport22FollowsProtocolMd() {
        byte[] f = AldesProtocol.buildStandard22(1_763_000_000L);
        assertEquals(AldesProtocol.STANDARD_LEN, f.length);
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFD, 0x70, (byte) 0xFF, 0x22},
                Arrays.copyOf(f, 5));
        // 26-byte trailer: 0xFE in front of the checksum, zero sum.
        assertEquals((byte) 0xFE, f[110]);
        assertEquals(0, AldesProtocol.checksum(f, 0, f.length));
        assertTrue(AldesProtocol.isStandard22(f));

        // Unix timestamp at trailer offset 14 (frame bytes 100..103).
        assertEquals(1_763_000_000, AldesProtocol.standard22Timestamp(f));
        assertEquals(1_763_000_000, AldesProtocol.le32(f, 100));

        // Sensors from the 0x24 notes, offsets relative to the sensor page.
        for (AldesProtocol.Sensor s : AldesProtocol.STANDARD_SENSORS) {
            assertEquals(s.raw() & 0xFFFF, AldesProtocol.standard22Sensor(f, s.offset()), s.label());
        }
        assertEquals(1200, AldesProtocol.standard22Sensor(f, 45));
        assertEquals(-4000, (short) AldesProtocol.standard22Sensor(f, 65));
        // Unassigned slots stay "not set".
        assertEquals(0xFFFF, AldesProtocol.standard22Sensor(f, 51));

        byte[] broken = f.clone();
        broken[100] ^= 0x01;
        assertFalse(AldesProtocol.isStandard22(broken));
        assertNull(AldesProtocol.standard22Timestamp(broken));
    }

    @Test
    void debugPage25IsTheCapturedDatagram() {
        byte[] f = AldesProtocol.buildDebug25();
        assertEquals(175, f.length);
        assertEquals(AldesProtocol.DEBUG_LEN, f.length);
        assertArrayEquals(new byte[] {(byte) 0xFA, (byte) 0xFD, (byte) 0xAF, (byte) 0xFF, 0x25},
                Arrays.copyOf(f, 5));
        assertEquals((byte) 0xFE, f[173]);
        assertEquals(0, AldesProtocol.checksum(f, 0, f.length));
        assertTrue(AldesProtocol.isDebug25(f));

        // Byte for byte the capture from protocol.md §3.
        byte[] capture = hex("FAFD"
                + "AFFF2509000000000000002B000000C5070000000000001F250000090000"
                + "00000000002A000000C507000000000000422400002000000000000000660000"
                + "00C019000000000000145200001D000000000000002700000056170000000000"
                + "00621F00001C00000000000000010000008816000000000000CE000000120000"
                + "0000000000020000007C0E0000000000009C0100001700000000000000000000"
                + "00821200000000000000000000FE29");
        assertEquals(175, capture.length);
        assertArrayEquals(capture, f);
        assertEquals(0x29, f[174] & 0xFF, "documented checksum");

        // Building twice yields the same page, and a broken one is rejected.
        assertArrayEquals(f, AldesProtocol.buildDebug25());
        byte[] broken = f.clone();
        broken[10] ^= 0x01;
        assertFalse(AldesProtocol.isDebug25(broken));
        assertFalse(AldesProtocol.isDebug25(AldesProtocol.buildStandard22(0)),
                "the 0x22 page is not a debug page");
    }

    @Test
    void sessionFramesAreRecognised() {
        assertTrue(AldesProtocol.isPong(AldesProtocol.PONG));
        assertTrue(AldesProtocol.isSealed(AldesProtocol.PONG, 0, AldesProtocol.PONG.length));
        assertEquals(7, AldesProtocol.PONG.length);
        assertTrue(AldesProtocol.isSealed(AldesProtocol.PING, 0, AldesProtocol.PING.length));
        assertEquals(7, AldesProtocol.PING[2], "length byte of the ping");
        assertTrue(AldesProtocol.isInitStandard(AldesProtocol.INIT_STANDARD));
        assertTrue(AldesProtocol.isModbusRead(AldesProtocol.MODBUS_READ_SETPOINTS));
        assertTrue(AldesProtocol.isModbus(AldesProtocol.INIT_STANDARD));
        assertFalse(AldesProtocol.isPong(AldesProtocol.PING));
    }

    @Test
    void programReport23FollowsProtocolMd() {
        Programs programs = new Programs();
        byte[] f = AldesProtocol.buildProgram23(programs);

        assertEquals(119, f.length);
        assertEquals(AldesProtocol.PROGRAM_LEN, f.length);
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFD, 0x77, (byte) 0xFF, 0x23},
                Arrays.copyOf(f, 5));
        // The grid fills the frame up to 116: only 0xFE and the checksum follow.
        assertEquals((byte) 0xFE, f[117]);
        assertEquals(0, AldesProtocol.checksum(f, 0, f.length));
        assertTrue(AldesProtocol.isProgram23(f));

        // 4 programs × 7 days × 4 LE bytes from offset 5, program-major.
        for (int p = 0; p < Programs.COUNT; p++) {
            for (int d = 0; d < Programs.DAYS; d++) {
                assertEquals(programs.dayBits(p, d), AldesProtocol.programDayBits(f, p, d),
                        "program " + Programs.letter(p) + " " + Programs.dayName(d));
            }
        }
        // Documented defaults: A on Monday 7–9 and 17–22, 8–23 at the weekend.
        assertEquals(0x3E_0180, AldesProtocol.programDayBits(f, 0, 0));
        assertEquals(0x007F_FF00, AldesProtocol.programDayBits(f, 0, 5));
        // B adds 12–14 on weekdays.
        assertEquals(0x3E_3180, AldesProtocol.programDayBits(f, 1, 0));

        // An edited hour reaches the wire, a damaged frame is rejected.
        programs.set(2, 6, 3, true);
        assertEquals(programs.dayBits(2, 6), AldesProtocol.programDayBits(
                AldesProtocol.buildProgram23(programs), 2, 6));
        byte[] broken = f.clone();
        broken[40] ^= 0x01;
        assertFalse(AldesProtocol.isProgram23(broken));
        assertFalse(AldesProtocol.isProgram23(AldesProtocol.buildStandard22(0)),
                "the 0x22 page is not a program report");
        assertFalse(AldesProtocol.isReport21(f));
    }

    @Test
    void write10CarriesEverySetField() {
        byte[] frame = WriteFrames.template();
        frame[13] = (byte) 0xD0;        // K1 20.00 °C (2000 = 0x07D0)
        frame[14] = 0x07;
        frame[21] = (byte) 0x2E;        // K5 23.50 °C (2350 = 0x092E)
        frame[22] = 0x09;
        frame[81] = 5;
        Arrays.fill(frame, 5, 13, (byte) 0x00);
        AldesProtocol.seal(frame);

        AldesProtocol.Command cmd = AldesProtocol.parseCommand(frame);
        assertTrue(cmd instanceof AldesProtocol.Write10 w
                && w.setpoints().equals(List.of(
                        new AldesProtocol.Setpoint(1, 20.0),
                        new AldesProtocol.Setpoint(5, 23.5)))
                && w.air() == 5
                && w.ecs() == null
                && w.vacation() == AldesProtocol.Vacation.CLEARED, String.valueOf(cmd));
        assertEquals("setpoint K1 = 20.00 °C | setpoint K5 = 23.50 °C | air = 5 | away cleared",
                ((AldesProtocol.Write10) cmd).summary());
    }

    @Test
    void write10WithoutChangesStaysRaw() {
        byte[] frame = WriteFrames.template();
        AldesProtocol.seal(frame);
        assertTrue(AldesProtocol.parseCommand(frame) instanceof AldesProtocol.Raw raw
                && raw.opcode() == 0x10);
    }

    @Test
    void programRequestAsksForTheProgramReport() {
        byte[] request = AldesProtocol.PROGRAM_REQUEST;
        assertEquals(8, request.length);
        assertEquals(0xA0, request[7] & 0xFF, "documented checksum");
        assertTrue(AldesProtocol.isSealed(request, 0, request.length));
        assertTrue(AldesProtocol.isProgramRequest(request));
        // FD FA and 0xFF at byte 3: the request also counts as a client
        // request, so it must be told apart from the 0x10 writes.
        assertTrue(AldesProtocol.isClientRequest(request));
        assertTrue(AldesProtocol.parseCommand(request) instanceof AldesProtocol.ProgramRequest);

        assertFalse(AldesProtocol.isProgramRequest(WriteFrames.setpoint(1, 20.0)));
        // 0x42 with another payload stays visible as a raw command.
        byte[] other = request.clone();
        other[5] = 0x21;
        AldesProtocol.seal(other);
        assertTrue(AldesProtocol.parseCommand(other) instanceof AldesProtocol.Raw raw
                && raw.opcode() == 0x42);
    }

    /** Zone arrays for {@link AldesProtocol.State}: padded with 0.0 to K9. */
    static double[] zones(double... values) {
        double[] out = new double[AldesProtocol.ZONE_COUNT];
        System.arraycopy(values, 0, out, 0, values.length);
        return out;
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Test
    void dateInputAsInTheGui() {
        assertEquals(2026, AldesProtocol.decodeFat(SimulationModel.parseDate("2026-03-01")).year());
        assertEquals(9, AldesProtocol.decodeFat(SimulationModel.parseDate("2026-03-01T09:30")).hour());
        assertEquals(0, AldesProtocol.decodeFat(SimulationModel.parseDate("2026-03-01")).minute());
        assertNull(SimulationModel.parseDate(""));
        assertNull(SimulationModel.parseDate("   "));
        assertThrows(IllegalArgumentException.class, () -> SimulationModel.parseDate("01.03.2026"));
        assertThrows(IllegalArgumentException.class, () -> SimulationModel.parseDate("2026-13-01"));
    }
}
