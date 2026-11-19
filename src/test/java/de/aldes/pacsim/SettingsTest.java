package de.aldes.pacsim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The values of the window survive a restart: the properties file keeps
 * setpoints K1–K9, temperatures, air/ECS mode, away dates and the keep-alive
 * switch, and a hand-edited file must not stop the simulation.
 */
class SettingsTest {

    private static SimulationModel fresh() {
        return new SimulationModel(LocalDateTime.of(2026, 1, 11, 14, 32, 40));
    }

    @Test
    void allValuesSurviveARestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pacsim.properties");
        SimulationModel before = fresh();
        before.setSetpoint(1, 21.5);
        before.setSetpoint(5, 23.0);
        before.setTemperature(2, 19.4);
        before.setTemperature(9, -3.5);
        before.setAir(5);
        before.setEcs(2);
        before.setEcsLevel(55);
        before.setThermostats(4);
        before.setVacationDeparture("2026-12-24T08:00");
        before.setVacationReturn("2027-01-06T18:00");
        before.programs().set(0, 0, 3, true);
        before.programs().set(2, 6, 22, false);
        Settings.save(file, before, true);

        SimulationModel after = fresh();
        assertTrue(Settings.load(file, after), "the keep-alive switch was on");

        assertEquals(21.5, after.setpoint(1));
        assertEquals(23.0, after.setpoint(5));
        assertTrue(Double.isNaN(after.setpoint(2)), "unset zones stay empty");
        assertEquals(19.4, after.temperature(2), 1e-9);
        assertEquals(-3.5, after.temperature(9), 1e-9);
        assertEquals(5, after.air());
        assertEquals(2, after.ecs());
        assertEquals(55, after.ecsLevel());
        assertEquals(4, after.thermostats());
        assertEquals("2026-12-24", after.vacationDepartureText());
        assertEquals("2027-01-06", after.vacationReturnText());
        assertTrue(after.programs().isOn(0, 0, 3), "program A, Monday 3:00 was switched on");
        assertFalse(after.programs().isOn(2, 6, 22), "program C, Sunday 22:00 was switched off");
        assertTrue(after.programs().isOn(1, 0, 12), "the other hours keep their default");
    }

    @Test
    void savingTwiceKeepsTheFileReadable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pacsim.properties");
        SimulationModel model = fresh();
        model.setSetpoint(3, 24.0);
        Settings.save(file, model, false);
        model.setSetpoint(3, 19.0);
        Settings.save(file, model, false);

        SimulationModel after = fresh();
        assertFalse(Settings.load(file, after));
        assertEquals(19.0, after.setpoint(3));
    }

    @Test
    void missingFileKeepsTheDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nothing.properties");
        SimulationModel model = fresh();
        double k1 = model.setpoint(1);

        assertFalse(Settings.load(file, model), "nothing stored, keep-alive stays off");
        assertEquals(k1, model.setpoint(1));
        assertFalse(Files.exists(file), "loading does not create the file");
    }

    @Test
    void thermostatCountIsClampedToThreeToNine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pacsim.properties");
        SimulationModel model = fresh();
        assertEquals(SimulationModel.THERMOSTATS_DEFAULT, model.thermostats());

        Files.writeString(file, "thermostats=4\n");
        Settings.load(file, model);
        assertEquals(4, model.thermostats());

        Files.writeString(file, "thermostats=9\n");
        Settings.load(file, model);
        assertEquals(9, model.thermostats(), "all nine zone slots can be shown");

        Files.writeString(file, "thermostats=12\n");
        Settings.load(file, model);
        assertEquals(SimulationModel.THERMOSTATS_MAX, model.thermostats(), "12 is clamped to 9");

        Files.writeString(file, "thermostats=1\n");
        Settings.load(file, model);
        assertEquals(SimulationModel.THERMOSTATS_MIN, model.thermostats(), "1 is clamped to 3");

        Files.writeString(file, "thermostats=abc\n");
        Settings.load(file, model);
        assertEquals(SimulationModel.THERMOSTATS_MIN, model.thermostats(), "garbage keeps the value");
    }

    @Test
    void brokenEntriesAreSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pacsim.properties");
        Files.writeString(file, String.join("\n",
                "setpoint.K1=21,5",
                "setpoint.K2=abc",
                "temperature.K3=99.0",
                "air=99",
                "ecs=1",
                "ecsLevel=101",
                "away.departure=24.12.2026",
                "keepAlive=yes"));

        SimulationModel model = fresh();
        assertFalse(Settings.load(file, model), "only 'true' switches keep-alive on");
        assertEquals(21.5, model.setpoint(1), "comma as decimal separator is accepted");
        assertTrue(Double.isNaN(model.setpoint(2)), "garbage keeps the default");
        assertEquals(fresh().temperature(3), model.temperature(3), 1e-9,
                "99 °C is out of range and keeps the default");
        assertEquals(0, model.air(), "an unknown air mode keeps the default");
        assertEquals(1, model.ecs());
        assertEquals(0, model.ecsLevel(), "101 % is out of range and keeps the default");
        assertEquals("", model.vacationDepartureText(), "a broken date keeps the default");
    }
}
