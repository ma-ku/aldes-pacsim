package de.aldes.pacsim;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * pacsim window: values, controls and protocol log.
 *
 * <p>Every control edits the {@link SimulationModel} state; the frame goes out
 * with the next 30 s tick, just like the real installation (there, a control
 * change reaches the bus only with the next report).
 *
 * <p>Flashing: after each incoming 0x21, {@link SimulationModel#compare}
 * returns the fields the board changed; their controls are highlighted in
 * yellow for a short moment.
 *
 * <p>All methods except {@link #onLinkEvent(SerialLink.Event)} run on the
 * Swing event dispatch thread. The serial link reports from its own threads
 * and hands its events over via {@link SwingUtilities#invokeLater}.
 */
public final class PacSimApp {

    /** How long the yellow highlight stays visible. */
    private static final int BLINK_MS = 1200;
    private static final Color BLINK_BACKGROUND = new Color(0xFF, 0xE6, 0x80);
    private static final Color SECTION_COLOR = new Color(0x1F, 0x4E, 0x79);
    private static final Color HINT_COLOR = new Color(0x55, 0x55, 0x55);
    private static final Color OK_COLOR = new Color(0x1A, 0x7F, 0x37);
    private static final Color ERROR_COLOR = new Color(0xB4, 0x23, 0x18);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    /** Delay before the report that confirms a client update (ms). */
    private static final int UPDATE_REPORT_DELAY_MS = 200;

    /** Editor steps: a setpoint in half degrees, a measurement in tenths. */
    private static final double SETPOINT_STEP = 0.5;
    private static final double TEMPERATURE_STEP = 0.1;
    private static final double TEMPERATURE_MIN = -40.0;
    private static final double TEMPERATURE_MAX = 60.0;

    private final SimulationModel model = new SimulationModel();
    private final KeepAlive keepAlive = new KeepAlive();
    private final Map<SimulationModel.Field, JComponent> blinkTargets =
            new EnumMap<>(SimulationModel.Field.class);

    private final JFrame frame = new JFrame("pacsim — Aldes T.One simulation (ESPHome clone)");

    // Header bar
    private final JComboBox<String> portChoice = new JComboBox<>();
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");
    private final JLabel connectionLabel = new JLabel("not connected");
    private final JLabel periodLabel =
            new JLabel("tick " + SimulationModel.REPORT_PERIOD_SECONDS + " s");
    private final JLabel nextRunLabel = new JLabel();

    // Values
    private final ValueSpinner[] setpointSpinners = new ValueSpinner[AldesProtocol.ZONE_COUNT];
    private final JButton[] clearSetpointButtons = new JButton[AldesProtocol.ZONE_COUNT];
    private final ValueSpinner[] temperatureSpinners = new ValueSpinner[AldesProtocol.ZONE_COUNT];
    private final JLabel[] zoneLabels = new JLabel[AldesProtocol.ZONE_COUNT];
    private final JPanel[] setpointBoxes = new JPanel[AldesProtocol.ZONE_COUNT];
    private final JPanel[] temperatureBoxes = new JPanel[AldesProtocol.ZONE_COUNT];
    private final JComboBox<String> thermostatBox = new JComboBox<>();
    private final JComboBox<String> airBox = new JComboBox<>();
    private final JComboBox<String> ecsBox = new JComboBox<>();
    /** ECS level in percent (0..100), byte 73 of the 0x21 report. */
    private final ValueSpinner ecsLevelSpinner =
            ValueSpinner.of(AldesProtocol.ECS_LEVEL_MIN, AldesProtocol.ECS_LEVEL_MAX, 1.0);
    private final JComboBox<String> vacationPreset = new JComboBox<>();
    private final JTextField vacationFrom = new JTextField();
    private final JTextField vacationTo = new JTextField();
    private final JLabel clockValue = new JLabel("—");
    private final JLabel keepAliveValue = new JLabel("—");
    private final JCheckBox keepAliveToggle = new JCheckBox("on", false);
    /** Index == {@link KeepAlive.Mode#ordinal()}. */
    private final JComboBox<String> datagramBox = new JComboBox<>(new String[] {
            "debug 0x25 (175 B)", "report 0x21 (90 B)", "standard 0x22 (112 B)" });
    private final JLabel statsValue = new JLabel();
    private final ProgramGrid[] programGrids = new ProgramGrid[Programs.COUNT];
    private final JLabel applyHint = new JLabel("Changed values are sent with the next report ("
            + SimulationModel.REPORT_PERIOD_SECONDS + " s) and stored for the next start.");

    // Log
    private final JTextArea logArea = new JTextArea(24, 80);
    private final JCheckBox autoScroll = new JCheckBox("Autoscroll", true);
    private final JCheckBox showHex = new JCheckBox("Raw bytes", false);

    /** {@code true} while the GUI writes values (suppresses callbacks). */
    private boolean updating;

    /** Set by {@link Main} once the link exists. */
    private LinkControl link;

    /** Where the values live between two runs. */
    private final Path settingsPath = Settings.defaultPath();

    /** Sends the confirmation report shortly after a client update. */
    private final javax.swing.Timer updateReport =
            new javax.swing.Timer(UPDATE_REPORT_DELAY_MS, e -> sendUpdateReport());

    public PacSimApp() {
        buildLayout();
        updateReport.setRepeats(false);
        refreshPorts();
        loadSettings();
        syncControlsFromModel();
    }

    /** Reads the values of the last run; a missing file keeps the defaults. */
    private void loadSettings() {
        try {
            boolean stored = Files.exists(settingsPath);
            keepAlive.setEnabled(Settings.load(settingsPath, model));
            refreshPrograms();
            if (stored) {
                log("INFO", "values loaded from " + settingsPath);
            }
        } catch (IOException e) {
            log("ERROR", "cannot read " + settingsPath + ": " + e.getMessage());
        }
    }

    /** Writes every value after a change, so the next start continues here. */
    private void persist() {
        try {
            Settings.save(settingsPath, model, keepAlive.enabled());
        } catch (IOException e) {
            log("ERROR", "cannot write " + settingsPath + ": " + e.getMessage());
        }
    }

    /** The top-level window (not shown yet). */
    public JFrame frame() {
        return frame;
    }

    /** Closes the serial link when the user closes the window. */
    public void onClose() {
        updateReport.stop();
        if (link != null) {
            link.disconnect();
        }
    }

    // ---------------------------------------------------------------- Layout

    private void buildLayout() {
        JPanel values = buildValues();
        JPanel log = buildLogPane();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Installation", scrolled(values));
        tabs.addTab("Programs A–D", scrolled(buildPrograms()));

        // The log sits below the tabs; the divider is draggable and the
        // one-touch arrows collapse the pane down to its title bar.
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, log);
        split.setResizeWeight(0.72);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setDividerSize(8);
        split.setBorder(null);
        split.setDividerLocation(470);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(buildHeader(), BorderLayout.NORTH);
        frame.getContentPane().add(split, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1320, 760);
        frame.setLocationByPlatform(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });
    }

    /** Keeps a tab's content at the top and scrolls once it gets long. */
    private static JScrollPane scrolled(JPanel content) {
        JPanel top = new JPanel(new BorderLayout());
        top.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(top);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** Second tab: the four time programs, each as its own clickable grid. */
    private JPanel buildPrograms() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel hint = new JLabel("Click an hour to switch between comfort and reduced; "
                + "dragging paints over the cells it crosses.");
        hint.setForeground(HINT_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;
        c.insets = new Insets(0, 0, 2, 0);
        panel.add(hint, c);

        for (int p = 0; p < Programs.COUNT; p++) {
            JLabel title = new JLabel("Program " + Programs.letter(p) + " — " + programKind(p));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            c.gridy++;
            c.insets = new Insets(14, 0, 2, 0);
            panel.add(title, c);

            ProgramGrid grid = new ProgramGrid(model.programs(), p, this::persist);
            programGrids[p] = grid;
            c.gridy++;
            c.insets = new Insets(0, 0, 0, 0);
            panel.add(grid, c);
        }
        return panel;
    }

    private static String programKind(int program) {
        return switch (program) {
            case 0 -> "heating";
            case 1 -> "heating, midday comfort";
            case 2 -> "cooling";
            default -> "cooling, midday comfort";
        };
    }

    private void refreshPrograms() {
        for (ProgramGrid grid : programGrids) {
            if (grid != null) {
                grid.refresh();
            }
        }
    }

    private JPanel buildValues() {
        JPanel values = new JPanel(new GridBagLayout());
        values.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        int[] row = {0};

        addSection(values, row, "Zones");
        addCell(values, header("Zone"), 0, row);
        addCell(values, header("Setpoint (°C, 16–30)"), 1, row);
        addCell(values, header("Measured temperature (°C)"), 2, row);
        row[0]++;
        for (int z = 1; z <= AldesProtocol.ZONE_COUNT; z++) {
            buildZoneRow(values, row, z);
        }

        String[] thermostatCounts =
                new String[SimulationModel.THERMOSTATS_MAX - SimulationModel.THERMOSTATS_MIN + 1];
        for (int i = 0; i < thermostatCounts.length; i++) {
            thermostatCounts[i] = Integer.toString(SimulationModel.THERMOSTATS_MIN + i);
        }
        thermostatBox.setModel(new DefaultComboBoxModel<>(thermostatCounts));
        thermostatBox.setToolTipText("Thermostats of the installation: only K1..Kn are shown, "
                + "the 0x21 report keeps its nine slots");
        thermostatBox.addActionListener(e -> {
            if (!updating && thermostatBox.getSelectedIndex() >= 0) {
                model.setThermostats(SimulationModel.THERMOSTATS_MIN
                        + thermostatBox.getSelectedIndex());
                applyThermostatCount();
                log("INFO", "thermostats: K1..K" + model.thermostats());
                persist();
            }
        });
        addCell(values, new JLabel("Thermostats"), 0, row);
        addCell(values, thermostatBox, 1, row);
        row[0]++;

        addSection(values, row, "Air and ECS modes");
        airBox.setModel(new DefaultComboBoxModel<>(labels(AldesProtocol.AIR_MODES)));
        ecsBox.setModel(new DefaultComboBoxModel<>(labels(AldesProtocol.ECS_MODES)));
        airBox.addActionListener(e -> {
            if (!updating && airBox.getSelectedIndex() >= 0) {
                model.setAir(airBox.getSelectedIndex());
                persist();
            }
        });
        ecsBox.addActionListener(e -> {
            if (!updating && ecsBox.getSelectedIndex() >= 0) {
                model.setEcs(ecsBox.getSelectedIndex());
                persist();
            }
        });
        addCell(values, new JLabel("Air"), 0, row);
        addCell(values, airBox, 1, row);
        blinkTargets.put(SimulationModel.Field.AIR, airBox);
        row[0]++;
        addCell(values, new JLabel("ECS"), 0, row);
        addCell(values, ecsBox, 1, row);
        blinkTargets.put(SimulationModel.Field.ECS, ecsBox);
        row[0]++;
        ecsLevelSpinner.setToolTipText("ECS level in percent (0..100), byte 73 of the 0x21 report");
        ecsLevelSpinner.addChangeListener(e -> commitEcsLevel(ecsLevelSpinner.celsius()));
        JPanel ecsLevelBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ecsLevelBox.add(ecsLevelSpinner);
        ecsLevelBox.add(new JLabel("%"));
        addCell(values, new JLabel("ECS level"), 0, row);
        addCell(values, ecsLevelBox, 1, row);
        blinkTargets.put(SimulationModel.Field.ECS_LEVEL, ecsLevelSpinner);
        row[0]++;

        addSection(values, row, "Away (vacation)");
        vacationPreset.setModel(new DefaultComboBoxModel<>(new String[] {
                "present", "away: hors-gel (today, no return)", "away: today + 3 d",
                "away: today + 7 d", "away: today + 14 d", "away: fixed holidays"}));
        vacationPreset.setSelectedIndex(0);
        vacationPreset.addActionListener(e -> {
            if (!updating) {
                applyVacationPreset();
            }
        });
        addCell(values, new JLabel("Preset"), 0, row);
        addCell(values, vacationPreset, 1, row);
        row[0]++;

        vacationFrom.setToolTipText("Departure: YYYY-MM-DD or YYYY-MM-DDTHH:MM");
        vacationTo.setToolTipText("Return: YYYY-MM-DD or YYYY-MM-DDTHH:MM");
        vacationFrom.addActionListener(e -> commitVacation());
        vacationTo.addActionListener(e -> commitVacation());
        vacationFrom.addFocusListener(focusLost(this::commitVacation));
        vacationTo.addFocusListener(focusLost(this::commitVacation));
        addCell(values, new JLabel("Departure"), 0, row);
        addCell(values, vacationFrom, 1, row);
        blinkTargets.put(SimulationModel.Field.VACATION_DEPARTURE, vacationFrom);
        row[0]++;
        addCell(values, new JLabel("Return"), 0, row);
        addCell(values, vacationTo, 1, row);
        blinkTargets.put(SimulationModel.Field.VACATION_RETURN, vacationTo);
        row[0]++;

        addSection(values, row, "Installation");
        clockValue.setFont(MONO);
        statsValue.setForeground(HINT_COLOR);
        applyHint.setForeground(HINT_COLOR);
        keepAliveValue.setFont(MONO);
        keepAliveValue.setForeground(HINT_COLOR);
        keepAliveToggle.setToolTipText(
                "Ping the gateway every 2–20 s (protocol.md §3) — off by default");
        keepAliveToggle.addActionListener(e -> {
            if (!updating) {
                keepAlive.setEnabled(keepAliveToggle.isSelected());
                updateSessionLabels(System.currentTimeMillis());
                log("INFO", keepAlive.enabled()
                        ? "keep-alive on: ping every 2–20 s"
                        : "keep-alive off: no pings");
                persist();
            }
        });
        datagramBox.setToolTipText("Debug 0x25 (175 B), report 0x21 (90 B) or standard 0x22 "
                + "(112 B) — the init frame leaves debug mode behind");
        datagramBox.setSelectedIndex(keepAlive.mode().ordinal());
        datagramBox.addActionListener(e -> {
            int index = datagramBox.getSelectedIndex();
            if (!updating && index >= 0) {
                keepAlive.setMode(KeepAlive.Mode.values()[index]);
                updateSessionLabels(System.currentTimeMillis());
                log("INFO", "datagram switched: " + keepAlive.modeText());
            }
        });
        addCell(values, new JLabel("PAC clock"), 0, row);
        addCell(values, clockValue, 1, row);
        row[0]++;
        addCell(values, new JLabel("Datagram"), 0, row);
        addCell(values, datagramBox, 1, row);
        row[0]++;
        addCell(values, new JLabel("Keep-alive"), 0, row);
        addCell(values, keepAliveToggle, 1, row);
        row[0]++;
        addCell(values, new JLabel("Session"), 0, row);
        addCell(values, keepAliveValue, 1, row);
        row[0]++;
        addCell(values, new JLabel("Status"), 0, row);
        addCell(values, statsValue, 1, row);
        row[0]++;
        addCell(values, applyHint, 0, row);
        row[0]++;

        // Keep the grid at the top.
        GridBagConstraints glue = new GridBagConstraints();
        glue.gridx = 0;
        glue.gridy = row[0];
        glue.weighty = 1.0;
        glue.fill = GridBagConstraints.VERTICAL;
        values.add(new JLabel(""), glue);
        return values;
    }

    private void buildZoneRow(JPanel values, int[] row, int zone) {
        ValueSpinner setpoint = ValueSpinner.of(
                AldesProtocol.SETPOINT_MIN, AldesProtocol.SETPOINT_MAX, SETPOINT_STEP);
        setpoint.setToolTipText("Setpoint 16–30 °C, step 0.5 °C — empty = no setpoint");
        setpoint.addChangeListener(e -> {
            if (!updating) {
                commitSetpoint(zone, setpoint.celsius());
            }
        });

        JButton clear = new JButton("\u00D7");
        clear.setToolTipText("Clear setpoint (no setpoint)");
        clear.addActionListener(e -> {
            if (!updating) {
                setpoint.setValue(null);
            }
        });

        JPanel setpointBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setpointBox.add(setpoint);
        setpointBox.add(clear);
        blinkTargets.put(SimulationModel.Field.setpoint(zone), setpoint);

        ValueSpinner temperature = ValueSpinner.of(
                TEMPERATURE_MIN, TEMPERATURE_MAX, TEMPERATURE_STEP);
        temperature.setToolTipText("Measured temperature, step 0.1 °C — empty = no value");
        temperature.addChangeListener(e -> {
            if (!updating) {
                commitTemperature(zone, temperature.celsius());
            }
        });
        JPanel temperatureBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        temperatureBox.add(temperature);
        temperatureBox.add(new JLabel("°C"));
        blinkTargets.put(SimulationModel.Field.temperature(zone), temperature);

        setpointSpinners[zone - 1] = setpoint;
        clearSetpointButtons[zone - 1] = clear;
        temperatureSpinners[zone - 1] = temperature;
        zoneLabels[zone - 1] = new JLabel("K" + zone);
        setpointBoxes[zone - 1] = setpointBox;
        temperatureBoxes[zone - 1] = temperatureBox;

        addCell(values, zoneLabels[zone - 1], 0, row);
        addCell(values, setpointBox, 1, row);
        addCell(values, temperatureBox, 2, row);
        row[0]++;
    }

    private static FocusAdapter focusLost(Runnable action) {
        return new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                action.run();
            }
        };
    }

    private static void addCell(JPanel grid, JComponent component, int column, int[] row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = row[0];
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(2, 4, 2, 8);
        grid.add(component, c);
    }

    private static void addSection(JPanel grid, int[] row, String title) {
        if (row[0] > 0) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row[0]++;
            c.gridwidth = GridBagConstraints.REMAINDER;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(10, 0, 4, 0);
            grid.add(new JSeparator(), c);
        }
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row[0]++;
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 4, 0);
        grid.add(section(title), c);
    }

    private static JComponent header(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setForeground(HINT_COLOR);
        return l;
    }

    private static JComponent section(String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() + 1f));
        l.setForeground(SECTION_COLOR);
        return l;
    }

    /** "3 — Clim Programme C" style entries for the mode boxes. */
    private static String[] labels(String[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = i + " — " + values[i];
        }
        return out;
    }

    private JPanel buildHeader() {
        portChoice.setPreferredSize(new Dimension(340, 26));
        connectButton.addActionListener(e -> onConnect());
        disconnectButton.addActionListener(e -> onDisconnect());
        disconnectButton.setEnabled(false);
        JButton refresh = new JButton("↻");
        refresh.setToolTipText("Search for ports again");
        refresh.addActionListener(e -> refreshPorts());

        JButton reportNow = new JButton("Report now");
        reportNow.setToolTipText("Send a report immediately and restart the "
                + SimulationModel.REPORT_PERIOD_SECONDS + " s tick");
        reportNow.addActionListener(e -> onReportNow());

        connectionLabel.setFont(MONO);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bar.setBackground(new Color(0xF2, 0xF4, 0xF7));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xD0, 0xD4, 0xDA)));
        bar.add(new JLabel("Port"));
        bar.add(portChoice);
        bar.add(refresh);
        bar.add(connectButton);
        bar.add(disconnectButton);
        bar.add(verticalSeparator());
        bar.add(connectionLabel);
        bar.add(verticalSeparator());
        bar.add(periodLabel);
        bar.add(nextRunLabel);
        bar.add(verticalSeparator());
        bar.add(reportNow);
        return bar;
    }

    private static JComponent verticalSeparator() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(8, 22));
        return s;
    }

    private JPanel buildLogPane() {
        logArea.setEditable(false);
        logArea.setFont(MONO);
        logArea.setLineWrap(false);
        DefaultCaret caret = (DefaultCaret) logArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> logArea.setText(""));
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> {
            logArea.selectAll();
            logArea.copy();
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
        JPanel tools = new JPanel(new BorderLayout());
        JPanel boxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        boxes.add(autoScroll);
        boxes.add(showHex);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(clear);
        buttons.add(copy);
        tools.add(boxes, BorderLayout.WEST);
        tools.add(buttons, BorderLayout.EAST);

        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setBorder(BorderFactory.createEmptyBorder(8, 8, 12, 12));
        box.add(section("Protocol"), BorderLayout.NORTH);
        box.add(new JScrollPane(logArea), BorderLayout.CENTER);
        box.add(tools, BorderLayout.SOUTH);
        box.setPreferredSize(new Dimension(0, 240));
        box.setMinimumSize(new Dimension(0, 80));
        return box;
    }

    // ---------------------------------------------------------------- Ports

    public void refreshPorts() {
        portChoice.removeAllItems();
        for (var port : SerialLink.availablePorts()) {
            portChoice.addItem(SerialLink.describePort(port));
        }
        if (portChoice.getItemCount() > 0) {
            portChoice.setSelectedIndex(0);
        }
    }

    /** Interface so the GUI stays testable without a serial port. */
    public interface LinkControl {
        boolean connect(int index) throws Exception;

        void disconnect();

        boolean connected();

        String name();

        /** Writes one frame (report). Returns success. */
        boolean send(byte[] frame);
    }

    public void setLinkControl(LinkControl link) {
        this.link = link;
        disconnectButton.setEnabled(link.connected());
        connectButton.setEnabled(!link.connected());
        connectionLabel.setText(link.connected() ? "connected: " + link.name() : "not connected");
    }

    private void onConnect() {
        int idx = portChoice.getSelectedIndex();
        if (idx < 0) {
            log("INFO", "No port selected — connect the board via USB");
            refreshPorts();
            return;
        }
        try {
            connectIndex(idx);
        } catch (Exception ex) {
            log("ERROR", "Opening failed: " + ex.getMessage());
        }
    }

    /** Connects the port with this index (also used by {@code --auto}). */
    public void connectIndex(int index) throws Exception {
        if (link == null) {
            throw new IllegalStateException("no link configured");
        }
        try {
            link.connect(index);
            log("INFO", "Port connected: " + link.name());
        } finally {
            applyConnectionState();
        }
    }

    private void onDisconnect() {
        if (link != null) {
            link.disconnect();
        }
        applyConnectionState();
    }

    /** Call after every connection change. */
    public void applyConnectionState() {
        boolean c = link != null && link.connected();
        connectButton.setEnabled(!c);
        disconnectButton.setEnabled(c);
        portChoice.setEnabled(!c);
        connectionLabel.setText(c ? "connected: " + link.name() : "not connected");
        connectionLabel.setForeground(c ? OK_COLOR : ERROR_COLOR);
        if (!c) {
            // A closed port ends the session: the next connection pings again
            // and starts with the 0x21 report.
            keepAlive.resetSession();
            updateSessionLabels(System.currentTimeMillis());
        }
    }

    private void onReportNow() {
        boolean sent = tick() != null;
        log("INFO", sent ? "Report sent manually" : "Report built but not sent (no port open)");
    }

    /**
     * One second of the keep-alive dialogue: sends the ping once the drawn
     * interval (2..20 s) is over. The session runs in both modes, because it is
     * independent of the report interval.
     */
    public void keepAliveBeat(long nowMs) {
        if (link == null || !link.connected() || !keepAlive.pingDue(nowMs)) {
            return;
        }
        if (link.send(AldesProtocol.PING)) {
            keepAlive.onPingSent(nowMs);
            updateSessionLabels(nowMs);
        }
    }

    private void updateSessionLabels(long nowMs) {
        boolean wasUpdating = updating;
        updating = true;
        try {
            datagramBox.setSelectedIndex(keepAlive.mode().ordinal());
            keepAliveToggle.setSelected(keepAlive.enabled());
        } finally {
            updating = wasUpdating;
        }
        keepAliveValue.setText(keepAlive.statusText(nowMs));
    }

    // ------------------------------------------------------------- Control

    /**
     * One tick: advance the PAC clock by {@code elapsedSeconds} (the
     * simulation clock stands still unless someone ticks), build and send the
     * report, then refresh the controls.
     *
     * @return the sent frame, or {@code null} when no port is open
     */
    public byte[] tick(long elapsedSeconds) {
        model.advanceClockSeconds(elapsedSeconds);
        // Debug mode sends the verbose 0x25 page; the start of a session and the
        // gateway's init frame lead to the 0x21 report, standard mode to the 0x22
        // monitoring page (protocol.md §3).
        byte[] report = switch (keepAlive.mode()) {
            case DEBUG -> AldesProtocol.buildDebug25();
            case REPORT -> model.nextReport(System.currentTimeMillis());
            case STANDARD -> AldesProtocol.buildStandard22(System.currentTimeMillis() / 1000);
        };
        boolean sent = link != null && link.connected();
        if (sent) {
            link.send(report);
        }
        syncControlsFromModel();
        return sent ? report : null;
    }

    /** First tick (clock already advanced by one period). */
    public byte[] tick() {
        return tick(SimulationModel.REPORT_PERIOD_SECONDS);
    }

    /** Hands a link event over to the Swing event dispatch thread. */
    public void onLinkEvent(SerialLink.Event event) {
        SwingUtilities.invokeLater(() -> {
            switch (event) {
                case SerialLink.Connected c -> {
                    log("INFO", "Port opened: " + c.port() + " (" + SerialLink.BAUD + " 8N1)");
                    applyConnectionState();
                }
                case SerialLink.Disconnected d -> {
                    log("INFO", "Connection lost: " + d.reason());
                    applyConnectionState();
                }
                case SerialLink.Tx t -> logFrame("TX", t.frame());
                case SerialLink.Rx r -> {
                    logFrame("RX", r.frame().bytes());
                    onFrameReceived(r.frame().bytes());
                }
                case SerialLink.Note n -> log("INFO", n.text());
            }
        });
    }

    /** SimulationModel behind the window (for tests). */
    public SimulationModel modelForTests() {
        return model;
    }

    public JTextArea logForTests() {
        return logArea;
    }

    /** The link received a frame. */
    public void onFrameReceived(byte[] frame) {
        final long now = System.currentTimeMillis();
        switch (keepAlive.onFrame(frame, now)) {
            case PONG -> {
                log("INFO", "pong received — keep-alive confirmed");
                updateSessionLabels(now);
                return;
            }
            case INIT_STANDARD -> {
                log("INFO", "init frame received — debug mode left, 0x21 reports (90 B)");
                updateSessionLabels(now);
                return;
            }
            case MODBUS_READ -> {
                log("INFO", "Modbus read request for the setpoints (not answered)");
                updateSessionLabels(now);
                return;
            }
            case OTHER -> {
                // Everything else is handled below.
            }
        }
        if (AldesProtocol.isProgramRequest(frame)) {
            // The client polls the time programming: it is the only trigger for
            // a 0x23 frame (protocol.md §6).
            log("INFO", "request for the time programming — answering with 0x23");
            sendProgramReport();
            return;
        }
        if (AldesProtocol.isClientRequest(frame)) {
            // The client's update request: take the value over, flash it and
            // answer with an updated report shortly afterwards (protocol.md §7).
            SimulationModel.Applied applied = model.applyFrame(frame);
            syncControlsFromModel();
            if (applied.ok()) {
                flash(applied.changed());
                log("INFO", "command applied: " + applied.description());
                persist();
                updateReport.restart();
            } else {
                log("WARN", "command ignored: " + applied.description());
            }
            return;
        }
        if (AldesProtocol.isReport21(frame)) {
            SimulationModel.Field[] changed = model.compare(frame);
            syncControlsFromModel();
            if (changed.length > 0) {
                flash(changed);
                log("INFO", "board changed: " + names(changed));
                persist();
            }
        }
    }

    /** The report that answers a client update, sent once the burst settled. */
    private void sendUpdateReport() {
        byte[] report = tick(0);
        log("INFO", report == null
                ? "updated report built, but no port is open"
                : "updated report sent after the client's change");
    }

    /**
     * Answers the clone's 0x42 request with the 0x23 time programming. The
     * frame goes out unchanged in every datagram mode, because the client asks
     * for it explicitly.
     */
    private void sendProgramReport() {
        byte[] report = AldesProtocol.buildProgram23(model.programs());
        if (link == null || !link.connected()) {
            log("INFO", "0x23 program report built, but no port is open");
            return;
        }
        link.send(report);
    }

    private static String names(SimulationModel.Field[] fields) {
        StringBuilder sb = new StringBuilder();
        for (SimulationModel.Field f : fields) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(f.label);
        }
        return sb.toString();
    }

    private void flash(SimulationModel.Field[] fields) {
        for (SimulationModel.Field f : fields) {
            JComponent target = blinkTargets.get(f);
            if (target == null) {
                continue;
            }
            highlight(target);
        }
    }

    /**
     * Paints a control yellow for {@link #BLINK_MS} and restores the previous
     * colors afterwards. Only the background changes, so the layout does not
     * move; a spinner highlights its editor field.
     */
    private static void highlight(JComponent target) {
        JTextField field = target instanceof ValueSpinner spinner ? spinner.textField() : null;
        JComponent paint = field != null ? field : target;
        Color oldBackground = paint.getBackground();
        boolean opaque = paint.isOpaque();
        paint.setOpaque(true);
        paint.setBackground(BLINK_BACKGROUND);
        paint.repaint();
        javax.swing.Timer timer = new javax.swing.Timer(BLINK_MS, e -> {
            paint.setBackground(oldBackground);
            paint.setOpaque(opaque);
            paint.repaint();
        });
        timer.setRepeats(false);
        timer.start();
    }

    // ------------------------------------------------------------ Controls

    private void commitSetpoint(int zone, double celsius) {
        if (updating) {
            return;
        }
        if (!Double.isNaN(celsius)
                && (celsius < AldesProtocol.SETPOINT_MIN || celsius > AldesProtocol.SETPOINT_MAX)) {
            log("ERROR", "Setpoint K" + zone + " must be between 16 and 30 °C: " + celsius);
        } else {
            model.setSetpoint(zone, celsius);
        }
        syncControlsFromModel();
        persist();
    }

    private void commitTemperature(int zone, double celsius) {
        if (updating) {
            return;
        }
        try {
            model.setTemperature(zone, celsius);
        } catch (IllegalArgumentException e) {
            log("ERROR", "Temperature K" + zone + " invalid (-40..60 °C): " + celsius);
        }
        syncControlsFromModel();
        persist();
    }

    /** The level is always reported, so an empty field falls back to the model. */
    private void commitEcsLevel(double percent) {
        if (updating) {
            return;
        }
        if (Double.isNaN(percent)) {
            syncControlsFromModel();
            return;
        }
        model.setEcsLevel((int) Math.round(percent));
        syncControlsFromModel();
        persist();
    }

    private void commitVacation() {
        if (updating) {
            return;
        }
        try {
            Integer dep = SimulationModel.parseDate(vacationFrom.getText());
            Integer ret = SimulationModel.parseDate(vacationTo.getText());
            if (dep != null && ret != null && ret < dep) {
                log("ERROR", "Return before departure — not applied");
                syncControlsFromModel();
                return;
            }
            model.setVacationDeparture(vacationFrom.getText());
            model.setVacationReturn(vacationTo.getText());
            syncControlsFromModel();
        } catch (IllegalArgumentException e) {
            log("ERROR", e.getMessage());
            syncControlsFromModel();
        }
        persist();
    }

    private void applyVacationPreset() {
        int idx = vacationPreset.getSelectedIndex();
        switch (idx) {
            case 0 -> {
                model.setVacationDeparture("");
                model.setVacationReturn("");
            }
            case 1 -> {
                // Hors-gel: departure = today, return = 0 ("not set"), so the
                // installation keeps the frost protection running without an end.
                model.setVacationDeparture(formatFatInput(LocalDate.now().atStartOfDay()));
                model.setVacationReturn("");
            }
            case 2, 3, 4 -> {
                int days = idx == 2 ? 3 : idx == 3 ? 7 : 14;
                LocalDate today = LocalDate.now();
                model.setVacationDeparture(formatFatInput(today.atStartOfDay()));
                model.setVacationReturn(formatFatInput(today.plusDays(days).atStartOfDay()));
            }
            case 5 -> {
                model.setVacationDeparture(formatFatInput(
                        LocalDateTime.of(2026, 12, 24, 8, 0)));
                model.setVacationReturn(formatFatInput(
                        LocalDateTime.of(2027, 1, 6, 18, 0)));
            }
            default -> {
                // nothing
            }
        }
        syncControlsFromModel();
        persist();
    }

    private static String formatFatInput(LocalDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }

    /** Pulls every control to the model state (no callbacks). */
    public void syncControlsFromModel() {
        updating = true;
        try {
            for (int z = 1; z <= AldesProtocol.ZONE_COUNT; z++) {
                showUnlessEditing(setpointSpinners[z - 1], model.setpoint(z));
                showUnlessEditing(temperatureSpinners[z - 1], model.temperature(z));
            }
            thermostatBox.setSelectedIndex(model.thermostats() - SimulationModel.THERMOSTATS_MIN);
            applyThermostatCount();
            airBox.setSelectedIndex(model.air());
            ecsBox.setSelectedIndex(model.ecs());
            showUnlessEditing(ecsLevelSpinner, model.ecsLevel());
            vacationFrom.setText(fatDateText(model.vacationDeparture()));
            vacationTo.setText(fatDateText(model.vacationReturn()));
            clockValue.setText(model.clockIso());
            updateSessionLabels(System.currentTimeMillis());
            statsValue.setText(model.reports() + " reports sent, "
                    + model.commandsApplied() + " commands applied, "
                    + (model.onFreezeProtect() ? "hors-gel (no return)"
                    : model.onVacation() ? "away programmed" : "present"));
            int preset = model.onVacation() ? -1 : 0;
            if (vacationPreset.getSelectedIndex() != preset) {
                vacationPreset.setSelectedIndex(preset);
            }
        } finally {
            updating = false;
        }
    }

    /** Shows only K1..Kn; hidden rows keep their values and stay in the report. */
    private void applyThermostatCount() {
        for (int z = 1; z <= AldesProtocol.ZONE_COUNT; z++) {
            boolean visible = z <= model.thermostats();
            zoneLabels[z - 1].setVisible(visible);
            setpointBoxes[z - 1].setVisible(visible);
            temperatureBoxes[z - 1].setVisible(visible);
        }
    }

    /** Refreshes a spinner, but never the one the user is typing in. */
    private static void showUnlessEditing(ValueSpinner spinner, double celsius) {
        if (!spinner.editing()) {
            spinner.show(celsius);
        }
    }

    private static String fatDateText(Integer fat) {
        if (fat == null) {
            return "";
        }
        AldesProtocol.PacClock c = AldesProtocol.decodeFat(fat);
        return String.format("%04d-%02d-%02d", c.year(), c.month(), Math.max(1, c.day()));
    }

    /** For the 30 s tick: seconds left until the next report. */
    public void showCountdown(long secondsLeft) {
        nextRunLabel.setText(String.format("next report in %2d s", Math.max(0, secondsLeft)));
    }

    // -------------------------------------------------------------- Protocol

    public void log(String tag, String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> append(tag, text));
            return;
        }
        append(tag, text);
    }

    private void append(String tag, String text) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        logArea.append(String.format("[%s] %-6s %s%n", time, tag, text));
        if (autoScroll.isSelected()) {
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    /** Frame line plus optional hex line. */
    public void logFrame(String tag, byte[] frame) {
        final int op = frame.length > 4 ? frame[4] & 0xFF : 0xFF;
        final boolean tx = tag.toUpperCase(Locale.ROOT).startsWith("TX");
        final boolean withHex = showHex.isSelected();
        Runnable write = () -> {
            append(tag, AldesProtocol.describe(op, frame, tx) + detail(op, frame));
            if (withHex) {
                logArea.append("             " + wrapHex(AldesProtocol.spacedHex(frame)) + "\n");
                if (autoScroll.isSelected()) {
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            }
        };
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(write);
        } else {
            write.run();
        }
    }

    /** Wraps a space-separated hex string after 16 bytes. */
    private static String wrapHex(String hex) {
        String[] parts = hex.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0 && i % 16 == 0) {
                out.append("\n             ");
            } else if (i > 0) {
                out.append(' ');
            }
            out.append(parts[i]);
        }
        return out.toString();
    }

    /** Short text after the frame line: decoded fields for 0x21/0x22/0x23/0x25/0x10/0x42. */
    private String detail(int op, byte[] frame) {
        if (op == 0x42 && AldesProtocol.isProgramRequest(frame)) {
            return "  | request for the time programming (answers with 0x23)";
        }
        if (op == 0x23 && AldesProtocol.isProgram23(frame)) {
            StringBuilder sb = new StringBuilder("  | time programming 0x23");
            for (int p = 0; p < Programs.COUNT; p++) {
                int hours = 0;
                for (int d = 0; d < Programs.DAYS; d++) {
                    hours += Integer.bitCount(
                            AldesProtocol.programDayBits(frame, p, d) & Programs.ALL_HOURS);
                }
                sb.append(" | ").append(Programs.letter(p)).append(' ').append(hours).append(" h");
            }
            return sb.toString();
        }
        if (op == 0x25 && AldesProtocol.isDebug25(frame)) {
            return "  | full debug 0x25 | " + frame.length
                    + " B | 15 consumption blocks of 24 B";
        }
        if (op == 0x22 && AldesProtocol.isStandard22(frame)) {
            StringBuilder sb = new StringBuilder("  | standard 0x22");
            Integer ts = AldesProtocol.standard22Timestamp(frame);
            sb.append(" | unix ").append(ts == null ? "—" : ts.toString());
            for (AldesProtocol.Sensor s : AldesProtocol.STANDARD_SENSORS) {
                int raw = AldesProtocol.standard22Sensor(frame, s.offset());
                String value = raw == 0xFFFF ? "—"
                        : s.hundredths() ? String.format(Locale.ROOT, "%.2f", (short) raw / 100.0)
                        : Integer.toString((short) raw);
                sb.append(" | ").append(s.label()).append(' ').append(value).append(s.unit());
            }
            return sb.toString();
        }
        if (op == 0x21 && AldesProtocol.isReport21(frame)) {
            AldesProtocol.State s = AldesProtocol.parseReport(frame);
            if (s == null) {
                return "  (invalid)";
            }
            StringBuilder sb = new StringBuilder("  | ");
            for (int i = 0; i < AldesProtocol.ZONE_COUNT; i++) {
                double sp = s.setpoints()[i];
                double t = s.temperatures()[i];
                sb.append("K").append(i + 1).append(' ')
                        .append(Double.isNaN(sp) ? "—" : String.format(Locale.ROOT, "%.1f", sp))
                        .append('/')
                        .append(Double.isNaN(t) ? "—" : String.format(Locale.ROOT, "%.1f", t))
                        .append(' ');
            }
            sb.append("| air=").append(s.air()).append(" ecs=").append(s.ecs())
                    .append(" level=").append(s.ecsLevel()).append('%')
                    .append(" | PAC ").append(s.clock() == null ? "—" : s.clock().iso());
            if (s.vacationDeparture() != null || s.vacationReturn() != null) {
                sb.append(" | away ").append(isoFat(s.vacationDeparture())).append("→")
                        .append(isoFat(s.vacationReturn()));
            }
            return sb.toString();
        }
        if (op == 0x10) {
            AldesProtocol.Write10 write = AldesProtocol.write10(frame);
            String text = write == null ? "" : write.summary();
            return text.isEmpty() ? "" : "  | " + text;
        }
        return "";
    }

    private static String isoFat(Integer fat) {
        if (fat == null) {
            return "—";
        }
        AldesProtocol.PacClock c = AldesProtocol.decodeFat(fat);
        return String.format("%04d-%02d-%02d", c.year(), c.month(), c.day());
    }
}
