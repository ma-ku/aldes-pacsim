package de.aldes.pacsim;

import com.fazecast.jSerialComm.SerialPort;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Serial connection to the ESPHome board (clone of the AldesConnect Box).
 *
 * <p>Data format as on the real box: 115200 8N1, no flow control. The reader
 * thread pushes every byte through the {@link Framer}; the housekeeping job
 * drops a frame in progress after 20 ms of silence, exactly like
 * {@code frame_flush_inactivity()} in the clone firmware.
 *
 * <p>All callbacks run on background threads; the GUI hands them over to the
 * Swing event dispatch thread itself.
 */
public final class SerialLink implements AutoCloseable {

    public static final int BAUD = 2400;

    /** Link event for the protocol log. */
    public sealed interface Event permits Connected, Disconnected, Tx, Rx, Note {}

    public record Connected(String port) implements Event {}

    public record Disconnected(String reason) implements Event {}

    /** One frame pacsim sent to the board. */
    public record Tx(byte[] frame) implements Event {}

    /** One complete frame delivered by the framer. */
    public record Rx(Framer.Frame frame) implements Event {}

    /** Free text (status, framer messages). */
    public record Note(String text) implements Event {}

    private final Consumer<Event> sink;
    private final Framer framer = new Framer();
    private final ScheduledExecutorService housekeeping =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "pacsim-flush"));

    private volatile SerialPort port;
    private volatile OutputStream out;
    private volatile boolean reading;
    private volatile String name = "";

    public SerialLink(Consumer<Event> sink) {
        this.sink = sink;
        housekeeping.scheduleAtFixedRate(this::flushIdle, 5, 5, TimeUnit.MILLISECONDS);
    }

    private static @NotNull Thread daemon(Runnable r, String name) {
        var thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    // ----------------------------------------------------------- Port list

    /** Attached ports without Bluetooth placeholders, with system names. */
    public static @NotNull List<SerialPort> availablePorts() {
        List<SerialPort> result = new ArrayList<>();
        for (var port : SerialPort.getCommPorts()) {
            var desc = (port.getSystemPortName() + " " + port.getDescriptivePortName()).toLowerCase();
            if (desc.contains("bluetooth") || desc.contains("debug-console")) {
                continue;
            }
            result.add(port);
        }

        return result;
    }

    public static @NotNull String describePort(@NotNull SerialPort p) {
        return p.getSystemPortName() + " — " + p.getDescriptivePortName();
    }

    public boolean isOpen() {
        var p = port;
        return p != null && p.isOpen();
    }

    public String portName() {
        return name;
    }

    // ---------------------------------------------------------------- Open

    /** Opens the port and starts the reader thread. Throws on errors. */
    public synchronized void open(@NotNull SerialPort p) throws IOException {
        close();
        p.setComPortParameters(BAUD, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        // Like the real box: no DTR/RTS toggling that would reset the clone.
        p.setDTR();
        p.setRTS();
        if (!p.openPort()) {
            throw new IOException("port " + p.getSystemPortName() + " cannot be opened");
        }

        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 2000);
        port = p;
        out = p.getOutputStream();
        name = p.getSystemPortName();
        framer.reset();
        reading = true;
        var t = daemon(this::readLoop, "pacsim-rx");
        t.start();
        sink.accept(new Connected(name));
    }

    private void readLoop() {
        byte[] buf = new byte[512];
        while (reading) {
            SerialPort p = port;
            if (p == null) {
                break;
            }
            int n;
            try {
                n = p.readBytes(buf, buf.length);
            }
            catch (RuntimeException e) {
                disconnect("read error: " + e.getMessage());
                return;
            }
            if (n < 0) {
                break;
            }

            if (n == 0) {
                continue;
            }

            long now = System.currentTimeMillis();
            for (var f : framer.feed(buf, 0, n, now)) {
                sink.accept(new Rx(f));
            }
        }
        if (reading) {
            disconnect("port removed");
        }
    }

    private void flushIdle() {
        if (framer.flushIfIdle(System.currentTimeMillis()) && isOpen()) {
            sink.accept(new Note("frame truncated (20 ms silence) → resync"));
        }
    }

    // --------------------------------------------------------------- Send

    /** Writes one frame; an error does not close the connection. */
    public synchronized boolean send(byte[] frame) {
        OutputStream o = out;
        SerialPort p = port;
        if (o == null || p == null || !p.isOpen()) {
            sink.accept(new Note("TX dropped: no port open"));
            return false;
        }
        try {
            o.write(frame);
            o.flush();
            sink.accept(new Tx(frame.clone()));
            return true;
        } catch (IOException e) {
            sink.accept(new Note("TX failed: " + e.getMessage()));
            return false;
        }
    }

    // -------------------------------------------------------------- Close

    public synchronized void close() {
        reading = false;
        SerialPort p = port;
        port = null;
        out = null;
        if (p != null) {
            p.closePort();
        }
        framer.reset();
    }

    private void disconnect(String reason) {
        SerialPort p = port;
        reading = false;
        port = null;
        out = null;
        if (p != null) {
            p.closePort();
        }
        sink.accept(new Disconnected(reason));
    }

    /** Stops the housekeeping job (after {@link #close()}). */
    public void shutdown() {
        close();
        housekeeping.shutdownNow();
    }

    @Override
    public String toString() {
        return "SerialLink[" + name + (isOpen() ? ", open" : ", closed") + "]";
    }
}
