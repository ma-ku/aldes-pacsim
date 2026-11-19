package de.aldes.pacsim;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;

import static de.aldes.pacsim.AldesProtocolTest.hex;
import static de.aldes.pacsim.AldesProtocolTest.zones;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the simulation: the echo stops the flashing, a real change
 * triggers it, the PAC clock runs with the tick, vacation/modes behave like
 * the real installation.
 */
class SimulationModelTest {

    private static SimulationModel fresh() {
        return new SimulationModel(LocalDateTime.of(2026, 1, 11, 14, 32, 40));
    }

    @Test
    void reportRepeatsTheInstallationValues() {
        SimulationModel m = fresh();
        m.setSetpoint(2, 22.5);
        m.setTemperature(2, 21.7);
        m.setAir(4);
        m.setEcs(1);
        byte[] r = m.nextReport(0);
        AldesProtocol.State s = AldesProtocol.parseReport(r);
        assertEquals(22.5, s.setpoints()[1]);
        assertEquals(21.7, s.temperatures()[1]);
        assertEquals(4, s.air());
        assertEquals(1, s.ecs());
        assertEquals("2026-01-11T14:32:40", s.clock().iso());
        assertNull(s.vacationDeparture());
        assertEquals(1, m.reports());
    }

    @Test
    void echoOfTheBoardDoesNotFlash() {
        SimulationModel m = fresh();
        m.setSetpoint(1, 19.0);
        m.setTemperature(3, 18.2);
        byte[] r = m.nextReport(0);
        // The clone passes the report through to the bus unchanged: no flashing.
        assertEquals(0, m.compare(r).length);
        assertEquals(19.0, m.setpoint(1));
    }

    @Test
    void changeByTheBoardFlashesAndTakesOverTheValue() {
        SimulationModel m = fresh();
        // K2 has a real setpoint, the rest is reported back.
        m.setSetpoint(2, 22.5);
        m.nextReport(0);
        // The board reports other values (as after a write command to the
        // installation): K1 20.5 / 20.3, K2 "sans consigne" (00 00) / 18.0.
        AldesProtocol.State echoed = new AldesProtocol.State(
                zones(20.5, 0.0, 0.0, 0.0),
                zones(20.3, 18.0, 0.0, 0.0),
                0, 0, 0, null, null, new AldesProtocol.PacClock(2026, 1, 11, 14, 32, 40));
        byte[] changed = AldesProtocol.buildReport(echoed, 0);
        SimulationModel.Field[] fields = m.compare(changed);

        // K3/K4 stay empty, only K1 (setpoint, measurement) and K2
        // (setpoint, measurement) change.
        assertEquals(4, fields.length);
        assertEquals(SimulationModel.Field.SETPOINT_K1, fields[0]);
        assertEquals(SimulationModel.Field.TEMPERATURE_K1, fields[1]);
        assertEquals(SimulationModel.Field.SETPOINT_K2, fields[2]);
        assertEquals(SimulationModel.Field.TEMPERATURE_K2, fields[3]);
        assertEquals(20.5, m.setpoint(1));
        assertTrue(Double.isNaN(m.setpoint(2)), "00 00 = sans consigne");
        assertEquals(20.3, m.temperature(1), 1e-9);
        assertEquals(18.0, m.temperature(2), 1e-9);
        // After taking the values over, the same report does not flash again.
        assertEquals(0, m.compare(changed).length);
    }

    @Test
    void vacationFlashesOnlyOnce() {
        SimulationModel m = fresh();
        m.nextReport(0);
        int dep = AldesProtocol.encodeFat(2026, 12, 24, 8, 0, 0);
        int ret = AldesProtocol.encodeFat(2027, 1, 6, 18, 0, 0);
        byte[] withVacation = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(21, 0, 0, 0), zones(19.5, 0, 0, 0), 0, 0, 0, dep, ret,
                new AldesProtocol.PacClock(2026, 1, 11, 14, 32, 40)), 0);
        SimulationModel.Field[] f = m.compare(withVacation);
        assertEquals(2, f.length);
        assertEquals("2026-12-24", m.vacationDepartureText());
        assertEquals("2027-01-06", m.vacationReturnText());
        assertEquals(0, m.compare(withVacation).length);
    }

    @Test
    void commandsChangeExactlyOneField() {
        SimulationModel m = fresh();
        SimulationModel.Applied a = m.applyFrame(WriteFrames.setpoint(3, 23.0));
        assertTrue(a.ok());
        assertArrayEquals(new SimulationModel.Field[] {SimulationModel.Field.SETPOINT_K3}, a.changed());
        assertEquals(23.0, m.setpoint(3));

        a = m.applyFrame(WriteFrames.air(7));
        assertArrayEquals(new SimulationModel.Field[] {SimulationModel.Field.AIR}, a.changed());
        assertEquals(7, m.air());

        a = m.applyFrame(WriteFrames.ecs(2));
        assertEquals(2, m.ecs());

        // Clearing a vacation when none is set: nothing flashes
        a = m.applyFrame(WriteFrames.vacationClear());
        assertTrue(a.ok());
        assertEquals(0, a.changed().length);
        assertEquals(4, m.commandsApplied());
    }

    /** 88-byte 0x10 frame like the clone's: only the set fields, everything else FF. */
    private static byte[] writeFrame(int[] zones, double[] celsius, Integer air, Integer ecs,
                                     boolean awayClear) {
        byte[] f = WriteFrames.template();
        for (int i = 0; i < zones.length; i++) {
            int raw = (int) Math.round(celsius[i] * 100.0);
            int off = AldesProtocol.SETPOINT_BASE + 2 * (zones[i] - 1);
            f[off] = (byte) (raw & 0xFF);
            f[off + 1] = (byte) ((raw >> 8) & 0xFF);
        }
        if (air != null) {
            f[81] = (byte) (int) air;
        }
        if (ecs != null) {
            f[82] = (byte) (int) ecs;
        }
        if (awayClear) {
            Arrays.fill(f, 5, 13, (byte) 0x00);
        }
        AldesProtocol.seal(f);
        return f;
    }

    @Test
    void oneFrameMaySetSeveralFields() {
        SimulationModel m = fresh();
        m.setVacationDeparture("2026-12-24");
        m.setVacationReturn("2027-01-06");

        // The full parameter list of protocol.md §7: two setpoints, air, ECS
        // and the vacation block cleared in one frame.
        byte[] frame = writeFrame(new int[] {2, 5}, new double[] {22.0, 23.5}, 1, 2, true);
        SimulationModel.Applied a = m.applyFrame(frame);

        assertNotNull(a);
        assertTrue(a.ok(), a.description());
        assertEquals(22.0, m.setpoint(2));
        assertEquals(23.5, m.setpoint(5));
        assertEquals(1, m.air());
        assertEquals(2, m.ecs());
        assertNull(m.vacationDeparture());
        assertNull(m.vacationReturn());
        assertArrayEquals(new SimulationModel.Field[] {
                SimulationModel.Field.SETPOINT_K2, SimulationModel.Field.SETPOINT_K5,
                SimulationModel.Field.AIR, SimulationModel.Field.ECS,
                SimulationModel.Field.VACATION_DEPARTURE,
                SimulationModel.Field.VACATION_RETURN}, a.changed());
        assertEquals(1, m.commandsApplied(), "one frame is one command");
        assertTrue(a.description().contains("Setpoint K2 = 22.0 °C"), a.description());
        assertTrue(a.description().contains("Air = 1"), a.description());
        assertTrue(a.description().contains("ECS = 2"), a.description());
        assertTrue(a.description().contains("away cleared"), a.description());
    }

    @Test
    void oneUnusableFieldRejectsTheWholeFrame() {
        SimulationModel m = fresh();
        // K2 = 22.0 °C is fine, K5 = 40.0 °C is outside 16..30 °C: nothing changes.
        byte[] frame = writeFrame(new int[] {2, 5}, new double[] {22.0, 40.0}, 1, null, false);
        SimulationModel.Applied a = m.applyFrame(frame);

        assertNotNull(a);
        assertFalse(a.ok());
        assertTrue(a.description().contains("outside 16..30"), a.description());
        assertTrue(Double.isNaN(m.setpoint(2)), "K2 untouched");
        assertTrue(Double.isNaN(m.setpoint(5)), "K5 untouched");
        assertEquals(0, m.air());
        assertEquals(0, m.commandsApplied());
    }

    @Test
    void vacationDatesAreNotSimulated() {
        SimulationModel m = fresh();
        byte[] frame = WriteFrames.template();
        frame[5] = 0x21;        // a DOS/FAT date where FF (unchanged) or 00 (clear) belongs
        AldesProtocol.seal(frame);
        SimulationModel.Applied a = m.applyFrame(frame);

        assertNotNull(a);
        assertFalse(a.ok());
        assertEquals("vacation dates not simulated", a.description());
        assertEquals(0, m.commandsApplied());
    }

    @Test
    void onlyRequestFramesAreApplied() {
        SimulationModel m = fresh();

        assertNull(m.applyFrame(m.nextReport(0)), "a 0x21 report is no request");
        assertNull(m.applyFrame(AldesProtocol.INIT_STANDARD), "a 0x02 session frame is no request");
        assertFalse(m.applyFrame(AldesProtocol.PONG).ok(), "a pong is logged, not applied");
        assertEquals(0, m.commandsApplied());

        SimulationModel.Applied a = m.applyFrame(WriteFrames.setpoint(5, 23.0));
        assertNotNull(a, "the 88-byte 0x10 update request is a request");
        assertTrue(a.ok());
        assertEquals(23.0, m.setpoint(5));
        assertEquals(1, m.commandsApplied());
    }

    @Test
    void invalidCommandIsNotGuessed() {
        SimulationModel m = fresh();
        byte[] broken = WriteFrames.setpoint(1, 20.0);
        broken[30] ^= 0x10;
        SimulationModel.Applied a = m.applyFrame(broken);
        assertFalse(a.ok());
        assertEquals(0, m.commandsApplied());
        assertEquals(21.0, m.setpoint(1));

        // Unknown opcode: valid frame, but not simulated.
        // FD FA | length 0x0C | FF 10 | 5x00 | FE | checksum
        byte[] unknown = hex("fdfa0cff11" + "00".repeat(5) + "fe00");
        assertEquals(12, unknown.length);
        AldesProtocol.seal(unknown);
        a = m.applyFrame(unknown);
        assertFalse(a.ok());
    }

    @Test
    void pacClockFollowsTheTick() {
        SimulationModel m = fresh();
        m.advanceClockSeconds(30);
        assertEquals("2026-01-11T14:33:10", m.clockIso());
        m.advanceClockSeconds(30 * 120);
        assertEquals("2026-01-11T15:33:10", m.clockIso());
        // Host time does not matter: only the seconds count.
    }

    @Test
    void setpointIsLimitedToGridAndRange() {
        SimulationModel m = fresh();
        m.setSetpoint(1, 21.3);
        assertEquals(21.5, m.setpoint(1));
        m.setSetpoint(1, 40.0);
        assertEquals(30.0, m.setpoint(1));
        m.setSetpoint(1, -5.0);
        assertEquals(16.0, m.setpoint(1));
    }

    @Test
    void dateInputSetsBothBoundsIndependently() {
        SimulationModel m = fresh();
        m.setVacationDeparture("2026-12-24");
        m.setVacationReturn("2027-01-06");
        m.setVacationDeparture("");
        assertNull(m.vacationDeparture());
        assertEquals("2027-01-06", m.vacationReturnText());
    }

    @Test
    void horsGelKeepsTheDepartureWithoutReturn() {
        SimulationModel m = fresh();
        // The "hors-gel" preset: start = today, end = 0 (not set).
        m.setVacationDeparture("2026-01-11");
        m.setVacationReturn("");
        assertTrue(m.onVacation());
        assertTrue(m.onFreezeProtect());
        assertEquals("2026-01-11", m.vacationDepartureText());
        assertNull(m.vacationReturn());
        assertEquals("", m.vacationReturnText());

        // A complete vacation is not hors-gel.
        m.setVacationReturn("2026-01-18");
        assertTrue(m.onVacation());
        assertFalse(m.onFreezeProtect());

        // Neither is "present".
        m.setVacationDeparture("");
        m.setVacationReturn("");
        assertFalse(m.onVacation());
        assertFalse(m.onFreezeProtect());
    }

    @Test
    void zonesUpToK9AreReportedAndCommandsReachThem() {
        SimulationModel m = fresh();
        m.setSetpoint(9, 24.5);
        m.setTemperature(9, 21.2);
        byte[] r = m.nextReport(0);
        AldesProtocol.State s = AldesProtocol.parseReport(r);
        assertEquals(24.5, s.setpoints()[8]);
        assertEquals(21.2, s.temperatures()[8]);
        // K1..K4 keep their place in the report.
        assertEquals(21.0, s.setpoints()[0]);

        SimulationModel.Applied a = m.applyFrame(WriteFrames.setpoint(9, 19.0));
        assertArrayEquals(new SimulationModel.Field[] {SimulationModel.Field.SETPOINT_K9}, a.changed());
        assertEquals(19.0, m.setpoint(9));

        // One report to make the current state the reference, then a K7
        // measurement change: only K7 flashes.
        m.nextReport(0);
        byte[] changed = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(21.0, 0, 0, 0, 0, 0, 0, 0, 19.0),
                zones(19.5, 0, 0, 0, 0, 0, 18.4, 0, 21.2),
                0, 0, 0, null, null, m.clock()), 1);
        SimulationModel.Field[] fields = m.compare(changed);
        assertEquals(1, fields.length);
        assertEquals(SimulationModel.Field.TEMPERATURE_K7, fields[0]);
        assertEquals(18.4, m.temperature(7), 1e-9);
    }

    @Test
    void ecsLevelIsReportedAndFlashes() {
        SimulationModel m = fresh();
        m.setEcsLevel(60);
        byte[] r = m.nextReport(0);
        assertEquals(60, r[AldesProtocol.ECS_LEVEL_OFFSET] & 0xFF);
        assertEquals(60, AldesProtocol.parseReport(r).ecsLevel());
        assertEquals(0, m.compare(r).length, "the echo must not flash");

        // The board reports another level: the field flashes and is taken over.
        byte[] changed = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(21, 0, 0, 0), zones(19.5, 0, 0, 0), 0, 0, 45, null, null, m.clock()), 1);
        SimulationModel.Field[] fields = m.compare(changed);
        assertEquals(1, fields.length);
        assertEquals(SimulationModel.Field.ECS_LEVEL, fields[0]);
        assertEquals(45, m.ecsLevel());
        assertEquals(0, m.compare(changed).length);

        assertThrows(IllegalArgumentException.class, () -> m.setEcsLevel(101));
        assertEquals(45, m.ecsLevel(), "a rejected level leaves the value alone");
    }
}
