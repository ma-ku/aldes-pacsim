package de.aldes.pacsim;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * The editable values of the window in one properties file.
 *
 * <p>Written after every change and read once at start, so pacsim continues
 * where it was stopped: setpoints K1–K9 (empty = no setpoint), measured
 * temperatures, air and ECS mode, the ECS level, the number of thermostats,
 * away dates and the keep-alive switch. The PAC clock is not stored — a
 * simulator starts with the current time.
 *
 * <p>Missing entries keep the model's default, unreadable ones are skipped: a
 * hand-edited file must not stop the simulation.
 */
final class Settings {

    /** {@code -Dpacsim.config=…} wins, otherwise {@code ~/.pacsim.properties}. */
    static Path defaultPath() {
        String override = System.getProperty("pacsim.config");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".pacsim.properties");
    }

    /**
     * Applies the stored values to the model.
     *
     * @return the stored keep-alive flag; {@code false} when nothing is stored
     */
    static boolean load(Path path, SimulationModel model) throws IOException {
        if (!Files.exists(path)) {
            return false;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        for (int zone = 1; zone <= AldesProtocol.ZONE_COUNT; zone++) {
            Double setpoint = number(p.getProperty("setpoint.K" + zone));
            if (setpoint != null) {
                model.setSetpoint(zone, setpoint);
            }
            Double temperature = number(p.getProperty("temperature.K" + zone));
            if (temperature != null) {
                try {
                    model.setTemperature(zone, temperature);
                } catch (IllegalArgumentException e) {
                    // outside -40..60 °C: keep the default
                }
            }
        }
        Integer air = integer(p.getProperty("air"));
        if (air != null && air >= 0 && air < AldesProtocol.AIR_MODES.length) {
            model.setAir(air);
        }
        Integer ecs = integer(p.getProperty("ecs"));
        if (ecs != null && ecs >= 0 && ecs < AldesProtocol.ECS_MODES.length) {
            model.setEcs(ecs);
        }
        Integer ecsLevel = integer(p.getProperty("ecsLevel"));
        if (ecsLevel != null && ecsLevel >= AldesProtocol.ECS_LEVEL_MIN
                && ecsLevel <= AldesProtocol.ECS_LEVEL_MAX) {
            model.setEcsLevel(ecsLevel);
        }
        Integer thermostats = integer(p.getProperty("thermostats"));
        if (thermostats != null) {
            // Clamped to 3..9, so a hand-edited file cannot hide every row.
            model.setThermostats(thermostats);
        }
        date(p.getProperty("away.departure"), model::setVacationDeparture);
        date(p.getProperty("away.return"), model::setVacationReturn);
        for (int program = 0; program < Programs.COUNT; program++) {
            for (int day = 0; day < Programs.DAYS; day++) {
                Integer bits = integer(p.getProperty(programKey(program, day)));
                if (bits != null) {
                    model.programs().setDayBits(program, day, bits);
                }
            }
        }
        return Boolean.parseBoolean(p.getProperty("keepAlive", "false"));
    }

    /** Writes every value of the model, plus the keep-alive switch. */
    static void save(Path path, SimulationModel model, boolean keepAlive) throws IOException {
        Properties p = new Properties();
        for (int zone = 1; zone <= AldesProtocol.ZONE_COUNT; zone++) {
            p.setProperty("setpoint.K" + zone, text(model.setpoint(zone)));
            p.setProperty("temperature.K" + zone, text(model.temperature(zone)));
        }
        p.setProperty("air", Integer.toString(model.air()));
        p.setProperty("ecs", Integer.toString(model.ecs()));
        p.setProperty("ecsLevel", Integer.toString(model.ecsLevel()));
        p.setProperty("thermostats", Integer.toString(model.thermostats()));
        p.setProperty("away.departure", model.vacationDepartureText());
        p.setProperty("away.return", model.vacationReturnText());
        p.setProperty("keepAlive", Boolean.toString(keepAlive));
        for (int program = 0; program < Programs.COUNT; program++) {
            for (int day = 0; day < Programs.DAYS; day++) {
                p.setProperty(programKey(program, day),
                        Integer.toString(model.programs().dayBits(program, day)));
            }
        }

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            p.store(w, "pacsim - values of the window");
        }
    }

    private static String programKey(int program, int day) {
        return "program." + Programs.letter(program) + "." + (day + 1);
    }

    private static String text(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static Double number(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(String text) {
        Double value = number(text);
        return value == null ? null : value.intValue();
    }

    private static void date(String text, java.util.function.Consumer<String> setter) {
        try {
            setter.accept(text == null ? "" : text.trim());
        } catch (IllegalArgumentException e) {
            // not a YYYY-MM-DD date: keep the default
        }
    }

    private Settings() {}
}
