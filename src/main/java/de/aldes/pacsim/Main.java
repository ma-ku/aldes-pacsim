package de.aldes.pacsim;

import com.fazecast.jSerialComm.SerialPort;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Connects {@link PacSimApp}, {@link SimulationModel} and {@link SerialLink}
 * and drives the 30 s tick on the Swing event dispatch thread.
 *
 * <p>Per tick: the model builds the 0x21 report (PAC clock +30 s), the link
 * writes it and the controls are refreshed. If the board sends back a
 * different 0x21, the affected controls flash.
 */
public final class Main {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Main() {}

    public static void main(String[] args) {
        boolean auto = false;
        String forcedPort = null;
        for (String a : args) {
            switch (a) {
                case "--auto" -> auto = true;
                case "--help", "-h" -> {
                    System.out.println("""
                            pacsim — Aldes simulator for the AldesConnect clone (ESP32-S31)

                            Usage: pacsim [--auto] [--port NAME]

                              --auto        connect the first serial port automatically
                              --port NAME   connect THIS port at startup (e.g. cu.usbmodem1101)
                            """);
                    return;
                }
                default -> {
                    if (a.startsWith("--port=")) {
                        forcedPort = a.substring("--port=".length());
                    }
                }
            }
        }
        final boolean autoMode = auto;
        final String portArg = forcedPort;
        SwingUtilities.invokeLater(() -> start(autoMode, portArg));
    }

    /** Builds the window and the tick; also usable from tests. */
    public static Takt start(boolean auto, String forcedPort) {
        var app = new PacSimApp();
        var model = app.modelForTests();
        var linkHolder = new SerialLink[1];

        List<SerialPort> ports = SerialLink.availablePorts();
        int forcedIndex = -1;
        if (forcedPort != null) {
            for (int i = 0; i < ports.size(); i++) {
                if (ports.get(i).getSystemPortName().equalsIgnoreCase(forcedPort)) {
                    forcedIndex = i;
                }
            }
        }

        app.setLinkControl(new PacSimApp.LinkControl() {
            @Override
            public boolean connect(int index) throws Exception {
                if (linkHolder[0] != null) {
                    linkHolder[0].shutdown();
                    linkHolder[0] = null;
                }
                List<SerialPort> available = SerialLink.availablePorts();
                if (index < 0 || index >= available.size()) {
                    throw new IOException("Port " + index + " no longer present — reload the list");
                }
                var link = new SerialLink(app::onLinkEvent);
                try {
                    link.open(available.get(index));
                } catch (IOException | RuntimeException e) {
                    link.shutdown();
                    throw e;
                }
                linkHolder[0] = link;
                return true;
            }

            @Override
            public void disconnect() {
                if (linkHolder[0] != null) {
                    linkHolder[0].shutdown();
                    linkHolder[0] = null;
                }
            }

            @Override
            public boolean connected() {
                return linkHolder[0] != null && linkHolder[0].isOpen();
            }

            @Override
            public String name() {
                return linkHolder[0] == null ? "" : linkHolder[0].portName();
            }

            @Override
            public boolean send(byte[] frame) {
                return linkHolder[0] != null && linkHolder[0].send(frame);
            }
        });

        Takt takt = new Takt(app);

        app.frame().setVisible(true);

        takt.start();
        takt.setRunning(auto || forcedPort != null);
        int idx = forcedIndex >= 0 ? forcedIndex : (auto ? 0 : -1);
        if (idx >= 0 && idx < ports.size()) {
            try {
                app.connectIndex(idx);
            } catch (Exception e) {
                app.log("ERROR", "Connection failed: " + e.getMessage());
            }
        }
        return takt;
    }

    /** The 30 s tick: {@code --auto} sends on its own, manual mode waits. */
    public static final class Takt {

        private final PacSimApp app;
        private final Timer timer;
        private int secondsLeft = (int) SimulationModel.REPORT_PERIOD_SECONDS;
        /** {@code true} in automatic mode ({@code --auto}): the tick sends. */
        private boolean running;

        Takt(PacSimApp app) {
            this.app = app;
            this.timer = new Timer(1000, e -> beat());
            this.timer.setRepeats(true);
        }

        public void start() {
            app.showCountdown(secondsLeft);
            timer.start();
        }

        public void stop() {
            timer.stop();
        }

        /** Automatic mode only: the tick triggers sending. */
        public void setRunning(boolean running) {
            this.running = running;
        }

        public boolean running() {
            return running;
        }

        public PacSimApp app() {
            return app;
        }

        /** Also for tests/manual mode: one full 30 s tick without waiting. */
        public byte[] tickNow() {
            secondsLeft = (int) SimulationModel.REPORT_PERIOD_SECONDS;
            byte[] sent = app.tick();
            app.showCountdown(secondsLeft);
            return sent;
        }

        private void beat() {
            // The keep-alive dialogue runs whenever a port is open — in manual
            // mode too, because it belongs to the session, not to the report
            // interval (protocol.md §3).
            app.keepAliveBeat(System.currentTimeMillis());
            if (!running) {
                return;
            }
            secondsLeft--;
            if (secondsLeft > 0) {
                app.showCountdown(secondsLeft);
                return;
            }
            secondsLeft = (int) SimulationModel.REPORT_PERIOD_SECONDS;
            app.tick(SimulationModel.REPORT_PERIOD_SECONDS);
            app.showCountdown(secondsLeft);
        }

        public static String stamp() {
            return LocalDateTime.now().format(LOG_TIME);
        }
    }
}
