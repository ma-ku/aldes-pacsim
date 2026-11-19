package de.aldes.pacsim;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static de.aldes.pacsim.AldesProtocolTest.zones;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulation without hardware: pacsim sends to a built-in counterpart
 * simulator that returns the report unchanged (like the clone in pass-through
 * mode) — then nothing may flash — and afterwards a changed report (like a
 * real installation), which must flash.
 *
 * <p>The counterpart is byte logic only here; the serial path itself
 * ({@link SerialLink}) needs a connected board and is checked manually with
 * {@code run.sh}.
 */
class LoopbackTest {

    @Test
    void echoAndChangeThroughTheFramer() {
        SimulationModel model = new SimulationModel(LocalDateTime.of(2026, 1, 11, 14, 32, 40));
        Framer framer = new Framer();

        byte[] report = model.nextReport(0);
        framer.feed(report, 0);
        // The counterpart received the report: echo it back.
        var echoed = framer.feed(report, 30_000);
        assertEquals(1, echoed.size());
        assertEquals(0, model.compare(echoed.get(0).bytes()).length, "echo must not flash");

        // The counterpart reports a different setpoint (like a real installation).
        byte[] changed = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(18.0, 0.0, 0.0, 0.0),
                zones(19.5, 0.0, 0.0, 0.0), 0, 0, 0, null, null,
                new AldesProtocol.PacClock(2026, 1, 11, 14, 33, 10)), 1);
        var frames = framer.feed(changed, 60_000);
        assertEquals(1, frames.size());
        SimulationModel.Field[] fields = model.compare(frames.get(0).bytes());
        assertEquals(1, fields.length, "exactly K1");
        assertEquals(SimulationModel.Field.SETPOINT_K1, fields[0]);
        assertEquals(18.0, model.setpoint(1));
        assertEquals(0, framer.feed(new byte[0], 1).size());
    }

    @Test
    void updateRequestThroughTheFramer() {
        SimulationModel model = new SimulationModel(LocalDateTime.of(2026, 1, 11, 14, 32, 40));
        Framer framer = new Framer();

        // The client writes "consigne K7 = 22.5": 88 bytes in small chunks.
        byte[] command = WriteFrames.setpoint(7, 22.5);
        var frames = new java.util.ArrayList<Framer.Frame>();
        for (int off = 0; off < command.length; off += 10) {
            frames.addAll(framer.feed(command, off, Math.min(10, command.length - off), off));
        }
        assertEquals(1, frames.size(), "one 88-byte frame, no matter how the bytes arrive");
        assertEquals(AldesProtocol.WRITE_LEN, frames.get(0).bytes().length);

        SimulationModel.Applied a = model.applyFrame(frames.get(0).bytes());
        assertNotNull(a, "a 0x10 request must reach the model");
        assertTrue(a.ok(), a.description());
        assertEquals(22.5, model.setpoint(7));
        assertEquals(1, model.commandsApplied());
    }

    @Test
    void programRequestThroughTheFramer() {
        SimulationModel model = new SimulationModel(LocalDateTime.of(2026, 1, 11, 14, 32, 40));
        Framer framer = new Framer();

        var request = framer.feed(AldesProtocol.PROGRAM_REQUEST, 0);
        assertEquals(1, request.size(), "the 0x42 request is one 8-byte frame");
        assertEquals(0x42, request.get(0).opcode());
        SimulationModel.Applied a = model.applyFrame(request.get(0).bytes());
        assertNotNull(a, "a 0x42 request must reach the model");
        assertTrue(a.ok(), a.description());
        assertEquals(0, a.changed().length, "a request changes nothing");

        // The answer: 119 bytes, framed in one piece although byte 2 is no length.
        byte[] answer = AldesProtocol.buildProgram23(model.programs());
        var frames = framer.feed(answer, 1_000);
        assertEquals(1, frames.size());
        assertEquals(AldesProtocol.PROGRAM_LEN, frames.get(0).bytes().length);
        assertEquals(0x23, frames.get(0).opcode());
        assertEquals(model.programs().dayBits(2, 3),
                AldesProtocol.programDayBits(frames.get(0).bytes(), 2, 3));
    }

    @Test
    void boardReportIsAnsweredThroughTheCommand() {
        SimulationModel model = new SimulationModel(LocalDateTime.of(2026, 1, 11, 14, 32, 40));
        // The board sends "consigne K4 = 27.5" — emulated via the installation
        SimulationModel.Applied a = model.applyFrame(WriteFrames.setpoint(4, 27.5));
        assertEquals(1, a.changed().length);
        byte[] next = model.nextReport(0);
        AldesProtocol.State s = AldesProtocol.parseReport(next);
        assertEquals(27.5, s.setpoints()[3]);
        // The installation echoes the setpoint: nothing flashes afterwards.
        assertEquals(0, model.compare(next).length);
    }
}
