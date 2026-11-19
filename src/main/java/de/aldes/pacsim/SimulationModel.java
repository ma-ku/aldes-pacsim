package de.aldes.pacsim;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Installation state of the simulation plus the rules for flashing.
 *
 * <p>Basic idea (and built so the simulator never gives anything away):
 * <ul>
 *   <li>The value pacsim <em>sent last</em> is the reference. It is repeated
 *       in every report.</li>
 *   <li>If the same value comes back in the next report, it was our echo — no
 *       flash.</li>
 *   <li>If a different value comes back, the board changed it (the clone
 *       writes either the signal output or the installation's next 0x21
 *       answer) — those controls flash.</li>
 * </ul>
 *
 * <p>A 30 s tick advances the PAC clock by 30 s and builds a new report. After
 * each tick another command may arrive; vacation, air, the ECS mode and the ECS
 * level only change on request, temperatures never change by themselves.
 *
 * <p>Not thread-safe: all calls come from the Swing event dispatch thread; the
 * link hands its events over via {@code SwingUtilities.invokeLater}.
 */
public final class SimulationModel {

    /** Send interval like the reference installation. */
    public static final long REPORT_PERIOD_SECONDS = 5;

    /** Thermostats an installation may have; the window shows K1..Kn. */
    public static final int THERMOSTATS_MIN = 3;
    /** At most the nine zone slots the 0x21 report carries. */
    public static final int THERMOSTATS_MAX = AldesProtocol.ZONE_COUNT;
    /** Default: the community installation reports six zones. */
    public static final int THERMOSTATS_DEFAULT = 6;

    /** One mutable field whose change can flash. */
    public enum Field {
        SETPOINT_K1("Setpoint K1"),
        SETPOINT_K2("Setpoint K2"),
        SETPOINT_K3("Setpoint K3"),
        SETPOINT_K4("Setpoint K4"),
        SETPOINT_K5("Setpoint K5"),
        SETPOINT_K6("Setpoint K6"),
        SETPOINT_K7("Setpoint K7"),
        SETPOINT_K8("Setpoint K8"),
        SETPOINT_K9("Setpoint K9"),

        TEMPERATURE_K1("Temperature K1"),
        TEMPERATURE_K2("Temperature K2"),
        TEMPERATURE_K3("Temperature K3"),
        TEMPERATURE_K4("Temperature K4"),
        TEMPERATURE_K5("Temperature K5"),
        TEMPERATURE_K6("Temperature K6"),
        TEMPERATURE_K7("Temperature K7"),
        TEMPERATURE_K8("Temperature K8"),
        TEMPERATURE_K9("Temperature K9"),

        AIR("Air"),
        ECS("ECS"),
        ECS_LEVEL("ECS level"),

        VACATION_DEPARTURE("Vacation departure"),
        VACATION_RETURN("Vacation return");

        public final String label;

        Field(String label) {
            this.label = label;
        }

        public static Field setpoint(int zone) {
            return values()[zone - 1];
        }

        public static Field temperature(int zone) {
            return values()[AldesProtocol.ZONE_COUNT + zone - 1];
        }
    }

    /** K1 starts at 21 °C like the reference; K2–K9 are {@code sans consigne} (NaN). */
    private final double[] setpoints = {21.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    private final double[] temperatures = {19.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    private int air = 0;
    private int ecs = 0;
    /** ECS level in percent (0..100), byte 73 of the 0x21 report. */
    private int ecsLevel;
    /** Only K1..Kn are shown; the 0x21 report keeps its nine slots. */
    private int thermostats = THERMOSTATS_DEFAULT;

    /** DOS/FAT value, or {@code null} = not set. */
    private Integer vacationDeparture;
    private Integer vacationReturn;
    private AldesProtocol.PacClock clock;

    /** Time programming A–D; it is read by the client (0x42 → 0x23), not part of the report. */
    private final Programs programs = new Programs();

    /** Last sent values, as the real installation reports them back. */
    private final double[] sentSetpoints = setpoints.clone();
    private final double[] sentTemperatures = temperatures.clone();
    private int sentAir;
    private int sentEcs;
    private int sentEcsLevel;
    private Integer sentDeparture;
    private Integer sentReturn;

    private long reports;
    private long commandsApplied;
    private long lastReportAtMs;

    public SimulationModel() {
        this(LocalDateTime.now());
    }

    public SimulationModel(LocalDateTime start) {
        this.clock = AldesProtocol.PacClock.of(start.withNano(0));
        // Away is the initial state: "away" then leaves the fields alone and a
        // vacation can be set exactly once.
        this.vacationDeparture = null;
        this.vacationReturn = null;
        markSent();
    }

    // -------------------------------------------------------------- Getters

    public double setpoint(int zone) {
        return setpoints[zone - 1];
    }

    /** {@code NaN} = no setpoint; the installation reports that as {@code 00 00}. */
    public double temperature(int zone) {
        return temperatures[zone - 1];
    }

    public int air() {
        return air;
    }

    /** Programs A–D (0x23), edited in the second tab. */
    public Programs programs() {
        return programs;
    }

    public int ecs() {
        return ecs;
    }

    /** ECS level in percent (0..100). */
    public int ecsLevel() {
        return ecsLevel;
    }

    /**
     * Thermostats the installation has: the window shows setpoint and measured
     * temperature for K1..Kn only. The wire format does not change, the report
     * keeps its nine zone slots.
     */
    public int thermostats() {
        return thermostats;
    }

    /** ISO text of the PAC clock, never host time. */
    public String clockIso() {
        return clock.iso();
    }

    public AldesProtocol.PacClock clock() {
        return clock;
    }

    public String vacationDepartureText() {
        return formatDate(vacationDeparture);
    }

    public String vacationReturnText() {
        return formatDate(vacationReturn);
    }

    public Integer vacationDeparture() {
        return vacationDeparture;
    }

    public Integer vacationReturn() {
        return vacationReturn;
    }

    public boolean onVacation() {
        return vacationDeparture != null || vacationReturn != null;
    }

    /**
     * Hors-gel: departure set, no return. The installation then only keeps the
     * frost protection running — the state the "hors-gel" preset produces
     * (start = today, end = 0 = not set).
     */
    public boolean onFreezeProtect() {
        return vacationDeparture != null && vacationReturn == null;
    }

    public long reports() {
        return reports;
    }

    public long commandsApplied() {
        return commandsApplied;
    }

    public long lastReportAtMs() {
        return lastReportAtMs;
    }

    private static String formatDate(Integer fat) {
        if (fat == null) {
            return "";
        }
        AldesProtocol.PacClock c = AldesProtocol.decodeFat(fat);
        return String.format("%04d-%02d-%02d", c.year(), c.month(), c.day());
    }

    // -------------------------------------------------------------- Setters

    /** Sets a setpoint locally (reported with the next report). */
    public void setSetpoint(int zone, double celsius) {
        setpoints[zone - 1] = clampSetpoint(celsius);
    }

    public void setTemperature(int zone, double celsius) {
        double v = Double.isNaN(celsius) ? Double.NaN : Math.round(celsius * 10.0) / 10.0;
        if (!Double.isNaN(v) && (v < -40.0 || v > 60.0)) {
            throw new IllegalArgumentException("temperature outside -40..60 °C");
        }
        temperatures[zone - 1] = v;
    }

    public void setAir(int mode) {
        if (mode < 0 || mode >= AldesProtocol.AIR_MODES.length) {
            throw new IllegalArgumentException("air mode 0..8");
        }
        air = mode;
    }

    public void setEcs(int mode) {
        if (mode < 0 || mode >= AldesProtocol.ECS_MODES.length) {
            throw new IllegalArgumentException("ECS mode 0..2");
        }
        ecs = mode;
    }

    /** Sets the ECS level the installation reports, {@value AldesProtocol#ECS_LEVEL_MIN}..{@value AldesProtocol#ECS_LEVEL_MAX} percent. */
    public void setEcsLevel(int percent) {
        if (percent < AldesProtocol.ECS_LEVEL_MIN || percent > AldesProtocol.ECS_LEVEL_MAX) {
            throw new IllegalArgumentException("ECS level 0..100");
        }
        ecsLevel = percent;
    }

    /** Clamps to {@value #THERMOSTATS_MIN}..{@value #THERMOSTATS_MAX}. */
    public void setThermostats(int count) {
        thermostats = Math.max(THERMOSTATS_MIN, Math.min(THERMOSTATS_MAX, count));
    }

    /** {@code text} as {@code YYYY-MM-DD}; empty = not set. */
    public void setVacationDeparture(String text) {
        vacationDeparture = parseDate(text);
    }

    public void setVacationReturn(String text) {
        vacationReturn = parseDate(text);
    }

    /** {@code YYYY-MM-DD} (or {@code YYYY-MM-DDTHH:MM}) to DOS/FAT. */
    public static Integer parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            LocalDateTime d = LocalDateTime.of(
                    Integer.parseInt(t.substring(0, 4)),
                    Integer.parseInt(t.substring(5, 7)),
                    Integer.parseInt(t.substring(8, 10)), 0, 0);
            if (t.length() >= 16 && t.charAt(10) == 'T') {
                d = d.withHour(Integer.parseInt(t.substring(11, 13)))
                        .withMinute(Integer.parseInt(t.substring(14, 16)));
            }
            return AldesProtocol.encodeFat(d.getYear(), d.getMonthValue(), d.getDayOfMonth(),
                    d.getHour(), d.getMinute(), 0);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD: " + text, e);
        }
    }

    private static double clampSetpoint(double celsius) {
        if (Double.isNaN(celsius)) {
            return Double.NaN;
        }
        return Math.max(AldesProtocol.SETPOINT_MIN, Math.min(AldesProtocol.SETPOINT_MAX,
                Math.round(celsius * 2.0) / 2.0));
    }

    // --------------------------------------------------------------- Report

    /** Builds the next 0x21 report and remembers the sent values. */
    public byte[] nextReport(long nowMs) {
        // An empty setpoint goes on the wire as 00 00: that is how the
        // installation reports "sans consigne" (README, K2 on 13 Sep 2026),
        // and only then the echo stays equal (0xFFFF counts as "not set" in
        // {@link #compare}, while a real installation would come back as
        // 00 00). Empty temperatures stay 0xFFFF, because 00 00 would mean
        // 0.00 °C there.
        double[] sent = new double[AldesProtocol.ZONE_COUNT];
        for (int i = 0; i < AldesProtocol.ZONE_COUNT; i++) {
            sent[i] = Double.isNaN(setpoints[i]) ? 0.0 : setpoints[i];
        }
        AldesProtocol.State state = new AldesProtocol.State(
                sent, temperatures.clone(), air, ecs, ecsLevel,
                vacationDeparture, vacationReturn, clock);
        byte[] frame = AldesProtocol.buildReport(state, reports);
        reports++;
        lastReportAtMs = nowMs;
        markSent();
        return frame;
    }

    /** Advances the PAC clock by the seconds of one interval. */
    public void advanceClockSeconds(long seconds) {
        clock = clock.plusPacSeconds(seconds);
    }

    private void markSent() {
        System.arraycopy(setpoints, 0, sentSetpoints, 0, AldesProtocol.ZONE_COUNT);
        System.arraycopy(temperatures, 0, sentTemperatures, 0, AldesProtocol.ZONE_COUNT);
        sentAir = air;
        sentEcs = ecs;
        sentEcsLevel = ecsLevel;
        sentDeparture = vacationDeparture;
        sentReturn = vacationReturn;
    }

    // -------------------------------------------------------------- Command

    /**
     * Result of a command from the board.
     *
     * @param description text for the log
     * @param changed     fields that changed because of it (flash)
     */
    public record Applied(String description, Field[] changed, boolean ignored) {
        public boolean ok() {
            return !ignored;
        }
    }

    /**
     * Applies a 0x10 command. Fields with {@code 0xFF} mean "unchanged"; every
     * field the frame sets is taken over together, just as the real
     * installation would show it after the next 0x21. A 0x42 only asks for the
     * time programming and changes nothing.
     */
    public Applied apply(AldesProtocol.Command cmd) {
        return switch (cmd) {
            case AldesProtocol.Write10 w -> applyWrite10(w);
            // A read request, not a change: PacSimApp answers the frame with
            // the 0x23 report (protocol.md §6).
            case AldesProtocol.ProgramRequest request ->
                    new Applied("time programming requested", new Field[0], false);
            // Unknown command: never guess. The installation would execute it;
            // pacsim only shows it in the log.
            case AldesProtocol.Raw r -> ignore(String.format("0x%02X not simulated", r.opcode()));
        };
    }

    /**
     * Takes over every field an 0x10 frame sets. The frame is validated as a
     * whole first: one unusable field rejects the command, so it never ends up
     * half applied.
     */
    private Applied applyWrite10(AldesProtocol.Write10 w) {
        if (w.vacation() == AldesProtocol.Vacation.DATES) {
            return ignore("vacation dates not simulated");
        }
        for (AldesProtocol.Setpoint s : w.setpoints()) {
            if (!(s.celsius() >= AldesProtocol.SETPOINT_MIN && s.celsius() <= AldesProtocol.SETPOINT_MAX)) {
                return ignore("setpoint K" + s.zone() + " = " + s.celsius() + " outside 16..30 °C");
            }
        }
        if (w.air() != null && w.air() >= AldesProtocol.AIR_MODES.length) {
            return ignore("air mode " + w.air() + " unknown");
        }
        if (w.ecs() != null && w.ecs() >= AldesProtocol.ECS_MODES.length) {
            return ignore("ECS mode " + w.ecs() + " unknown");
        }

        List<String> parts = new ArrayList<>();
        List<Field> changed = new ArrayList<>();
        for (AldesProtocol.Setpoint s : w.setpoints()) {
            setpoints[s.zone() - 1] = clampSetpoint(s.celsius());
            parts.add("Setpoint K" + s.zone() + " = " + s.celsius() + " °C");
            changed.add(Field.setpoint(s.zone()));
        }
        if (w.air() != null) {
            air = w.air();
            parts.add("Air = " + w.air() + " " + AldesProtocol.AIR_MODES[w.air()]);
            changed.add(Field.AIR);
        }
        if (w.ecs() != null) {
            ecs = w.ecs();
            parts.add("ECS = " + w.ecs() + " " + AldesProtocol.ECS_MODES[w.ecs()]);
            changed.add(Field.ECS);
        }
        if (w.vacation() == AldesProtocol.Vacation.CLEARED) {
            boolean had = vacationDeparture != null || vacationReturn != null;
            vacationDeparture = null;
            vacationReturn = null;
            parts.add("away cleared");
            if (had) {
                changed.add(Field.VACATION_DEPARTURE);
                changed.add(Field.VACATION_RETURN);
            }
        }
        commandsApplied++;
        return new Applied(String.join(", ", parts), changed.toArray(new Field[0]), false);
    }

    /**
     * Convenience entry: one request frame of the clone. {@code null} when the
     * frame is no such request (a 0x21 report or a 0x02 session frame, for
     * example); a request that cannot be used comes back as
     * {@link Applied#ignored()} with the reason for the log.
     */
    public Applied applyFrame(byte[] frame) {
        if (!AldesProtocol.isClientRequest(frame)) {
            return null;
        }
        AldesProtocol.Command cmd = AldesProtocol.parseCommand(frame);
        if (cmd == null) {
            return ignore("invalid frame (length or checksum)");
        }
        return apply(cmd);
    }

    private static Applied ignore(String why) {
        return new Applied(why, new Field[0], true);
    }

    // -------------------------------------------------------------- Flashing

    /**
     * Compares a received 0x21 with what pacsim sent last and returns the
     * fields the board changed.
     *
     * <p>Call directly after every received report: as long as the board only
     * passes the report through, all values are equal and nothing flashes.
     */
    public Field[] compare(byte[] report21) {
        AldesProtocol.State s = AldesProtocol.parseReport(report21);
        if (s == null) {
            return new Field[0];
        }
        Field[] out = new Field[2 * AldesProtocol.ZONE_COUNT + 5];
        int n = 0;
        for (int i = 0; i < AldesProtocol.ZONE_COUNT; i++) {
            if (!sameValue(s.setpoints()[i], sentSetpoints[i])) {
                // 00 00 means "sans consigne" on the installation (README, K2
                // on 13 Sep 2026): pacsim stores NaN for it, so the value also
                // disappears from the GUI.
                setpoints[i] = s.setpoints()[i] == 0.0 || Double.isNaN(s.setpoints()[i])
                        ? Double.NaN : s.setpoints()[i];
                out[n++] = Field.setpoint(i + 1);
            }
            if (!sameValue(s.temperatures()[i], sentTemperatures[i])) {
                // Same rule for measured values: 00 00 is "no value".
                temperatures[i] = s.temperatures()[i] == 0.0 || Double.isNaN(s.temperatures()[i])
                        ? Double.NaN : s.temperatures()[i];
                out[n++] = Field.temperature(i + 1);
            }
        }
        if (s.air() != sentAir) {
            air = s.air();
            out[n++] = Field.AIR;
        }
        if (s.ecs() != sentEcs) {
            ecs = s.ecs();
            out[n++] = Field.ECS;
        }
        if (s.ecsLevel() != sentEcsLevel) {
            ecsLevel = s.ecsLevel();
            out[n++] = Field.ECS_LEVEL;
        }
        int dep = s.vacationDeparture() == null ? 0 : s.vacationDeparture();
        int sentDep = sentDeparture == null ? 0 : sentDeparture;
        if (dep != sentDep) {
            vacationDeparture = s.vacationDeparture();
            out[n++] = Field.VACATION_DEPARTURE;
        }
        int ret = s.vacationReturn() == null ? 0 : s.vacationReturn();
        int sentRet = sentReturn == null ? 0 : sentReturn;
        if (ret != sentRet) {
            vacationReturn = s.vacationReturn();
            out[n++] = Field.VACATION_RETURN;
        }
        // The reference moves along: the next report echoes the new state.
        markSent();
        return Arrays.copyOf(out, n);
    }

    /**
     * Compares two setpoints or measured values. Empty and 0.0 (00 00) count
     * as the same value: an empty setpoint goes on the wire as 0xFFFF and an
     * installation answers with 0xFFFF or 00 00 depending on the zone. What
     * pacsim holds as empty therefore stays empty on the echo and does not
     * flash.
     */
    private static boolean sameValue(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Math.abs((Double.isNaN(a) ? 0.0 : a) - (Double.isNaN(b) ? 0.0 : b)) < 1e-9;
        }
        return Math.abs(a - b) < 1e-9;
    }

    // ----------------------------------------------------------------- Text

    @Override
    public String toString() {
        return "SimulationModel{reports=" + reports + ", commands=" + commandsApplied + "}";
    }

    /** Debug/tests only: descriptive text of all fields. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= AldesProtocol.ZONE_COUNT; i++) {
            sb.append("K").append(i).append(": ");
            double s = setpoint(i);
            sb.append(Double.isNaN(s) ? "no setpoint" : String.format("%.2f", s)).append(" / ");
            double t = temperature(i);
            sb.append(Double.isNaN(t) ? "—" : String.format("%.2f", t)).append(" °C\n");
        }
        sb.append("Air=").append(air).append(" ECS=").append(ecs)
                .append(" (level ").append(ecsLevel).append(" %)")
                .append(" Vac=").append(vacationDepartureText()).append("→").append(vacationReturnText())
                .append(" PAC=").append(clockIso());
        return sb.toString();
    }
}
