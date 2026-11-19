package de.aldes.pacsim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static de.aldes.pacsim.AldesProtocolTest.zones;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Framer: the same rules as {@code frame_feed()} in the clone plus the installation header. */
class FramerTest {

    /** Small, easy to count frame: FA FD 08 00 01 02 03 04. */
    private static byte[] shortFrame(int tag) {
        byte[] f = {(byte) 0xFA, (byte) 0xFD, 0x08, 0x00, 0x01, 0x02, 0x03, (byte) tag};
        return f;
    }

    @Test
    void oneFrameInOneGo() {
        Framer f = new Framer();
        byte[] frame = shortFrame(0x10);
        List<Framer.Frame> out = f.feed(frame, 1000);
        assertEquals(1, out.size());
        assertArrayEquals(frame, out.get(0).bytes());
        assertFalse(f.inFrame());
    }

    @Test
    void bytewiseAndInTheMiddleOfAFrame() {
        Framer f = new Framer();
        byte[] frame = shortFrame(0x11);
        for (int i = 0; i < frame.length - 1; i++) {
            assertTrue(f.feed(new byte[] {frame[i]}, 1000 + i).isEmpty(), "byte " + i);
        }
        List<Framer.Frame> out = f.feed(new byte[] {frame[frame.length - 1]}, 2000);
        assertEquals(1, out.size());
        assertArrayEquals(frame, out.get(0).bytes());
    }

    @Test
    void fillerBytesBeforeTheHeaderAreIgnored() {
        Framer f = new Framer();
        byte[] junk = {0x00, 0x12, 0x34, (byte) 0xFF};
        byte[] frame = shortFrame(0x22);
        byte[] all = new byte[junk.length + frame.length];
        System.arraycopy(junk, 0, all, 0, junk.length);
        System.arraycopy(frame, 0, all, junk.length, frame.length);
        List<Framer.Frame> out = f.feed(all, 0);
        assertEquals(1, out.size());
        assertArrayEquals(frame, out.get(0).bytes());
    }

    @Test
    void installationHeaderFdFaWithForeignLength() {
        Framer f = new Framer();
        // This is how the repository leads the 0x21 report: FD FA, then 5A FF 21 (length 90).
        byte[] report = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(20, 20, 20, 20), zones(19, 19, 19, 19), 0, 0, 0, null, null,
                new AldesProtocol.PacClock(2026, 1, 1, 0, 0, 0)), 0);
        List<Framer.Frame> out = f.feed(report, 0);
        assertEquals(1, out.size(), "FD FA header must be recognised");
        assertEquals(AldesProtocol.REPORT_LEN, out.get(0).bytes().length);
        assertEquals(0x21, out.get(0).opcode());
        assertTrue(AldesProtocol.parseReport(out.get(0).bytes()) != null);
    }

    @Test
    void installationHeaderWithWriteLength() {
        Framer f = new Framer();
        byte[] w = WriteFrames.setpoint(1, 21.0);
        List<Framer.Frame> out = f.feed(w, 0);
        assertEquals(1, out.size());
        assertArrayEquals(w, out.get(0).bytes());
        assertEquals(0x10, out.get(0).opcode());
    }

    @Test
    void absurdLengthDropsOnlyTheHeader() {
        Framer f = new Framer();
        byte[] bad = {(byte) 0xFA, (byte) 0xFD, 0x02, 0x00};
        assertTrue(f.feed(bad, 0).isEmpty());
        assertFalse(f.inFrame());
        byte[] frame = shortFrame(0x33);
        assertEquals(1, f.feed(frame, 0).size());
    }

    @Test
    void interruptedFrameIsDroppedAfterSilence() {
        Framer f = new Framer();
        byte[] frame = shortFrame(0x44);
        byte[] head = new byte[5];
        System.arraycopy(frame, 0, head, 0, head.length);
        assertTrue(f.feed(head, 1000).isEmpty());
        assertTrue(f.inFrame());
        assertFalse(f.flushIfIdle(1010), "only after 20 ms of silence");
        assertTrue(f.flushIfIdle(1025));
        assertFalse(f.inFrame());
        // can be resynchronised afterwards
        assertEquals(1, f.feed(frame, 2000).size());
    }

    @Test
    void severalFramesInOneCall() {
        Framer f = new Framer();
        byte[] a = shortFrame(0x01);
        byte[] b = shortFrame(0x02);
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        List<Framer.Frame> out = f.feed(both, 0);
        assertEquals(2, out.size());
        assertArrayEquals(a, out.get(0).bytes());
        assertArrayEquals(b, out.get(1).bytes());
    }

    @Test
    void foreignHeaderInsideTheFrameResynchronises() {
        Framer f = new Framer();
        // Absurd length (0x02 < FRAME_MIN), then a new header arrives.
        byte[] bad = {(byte) 0xFA, (byte) 0xFD, 0x02, 0x00};
        assertTrue(f.feed(bad, 0).isEmpty());
        // Absurd length: the framer hunts for a header again.
        assertFalse(f.inFrame());
        // A frame that starts with FA FD is read from the beginning.
        byte[] frame = shortFrame(0x55);
        List<Framer.Frame> out = f.feed(frame, 0);
        assertEquals(1, out.size());
        assertArrayEquals(frame, out.get(0).bytes());
    }

    @Test
    void fdFaHeaderWithoutLengthByteAndWithNonsenseIsDropped() {
        Framer f = new Framer();
        // FD FA: byte 2 is no length. If the end never becomes clear, the
        // frame must not hang, it has to give up after FRAME_MAX.
        byte[] junk = new byte[Framer.FRAME_MAX + 8];
        junk[0] = (byte) 0xFD;
        junk[1] = (byte) 0xFA;
        junk[2] = 0x58;
        junk[3] = (byte) 0xFF;
        assertTrue(f.feed(junk, 0).isEmpty());
        assertFalse(f.inFrame());
        byte[] frame = shortFrame(0x66);
        assertEquals(1, f.feed(frame, 0).size());
    }

    @Test
    void fdFaReportIsRecognisedByteWise() {
        Framer f = new Framer();
        byte[] report = AldesProtocol.buildReport(new AldesProtocol.State(
                zones(20, 20, 20, 20), zones(19, 19, 19, 19), 0, 0, 0, null, null,
                new AldesProtocol.PacClock(2026, 1, 1, 0, 0, 0)), 0);
        int n = 0;
        for (int i = 0; i < report.length; i++) {
            List<Framer.Frame> out = f.feed(new byte[] {report[i]}, i);
            n += out.size();
            if (i < report.length - 1) {
                assertTrue(out.isEmpty(), "not complete yet at byte " + i);
            } else {
                assertArrayEquals(report, out.get(0).bytes());
            }
        }
        assertEquals(1, n);
        assertFalse(f.inFrame());
    }

    @Test
    void pongIsFramedAsFDfaFrame() {
        Framer f = new Framer();
        List<Framer.Frame> out = f.feed(AldesProtocol.PONG, 0);
        assertEquals(1, out.size(), "pong FD FA 07 FF 13 FE F2");
        assertArrayEquals(AldesProtocol.PONG, out.get(0).bytes());
        assertTrue(AldesProtocol.isPong(out.get(0).bytes()));
        assertFalse(f.inFrame());
    }

    @Test
    void modbusAndInitFramesUseTheZeroTwoHeader() {
        for (byte[] expected : AldesProtocol.MODBUS_FRAMES) {
            Framer f = new Framer();
            List<Framer.Frame> out = f.feed(expected, 0);
            assertEquals(1, out.size(), AldesProtocol.hex(expected));
            assertArrayEquals(expected, out.get(0).bytes());
            assertFalse(f.inFrame());
        }
        assertTrue(AldesProtocol.isInitStandard(
                new Framer().feed(AldesProtocol.INIT_STANDARD, 0).get(0).bytes()));
    }

    @Test
    void modbusFrameIsRecognisedByteWise() {
        Framer f = new Framer();
        byte[] init = AldesProtocol.INIT_STANDARD;
        for (int i = 0; i < init.length - 1; i++) {
            assertTrue(f.feed(new byte[] {init[i]}, i).isEmpty(), "byte " + i);
        }
        List<Framer.Frame> out = f.feed(new byte[] {init[init.length - 1]}, 100);
        assertEquals(1, out.size());
        assertArrayEquals(init, out.get(0).bytes());
    }

    @Test
    void unknownZeroTwoFrameIsDropped() {
        Framer f = new Framer();
        // Same prefix as the documented frames, but byte 5 is neither 0x01 nor
        // 0x02: the attempt ends. The last byte is no header, so the framer
        // hunts again instead of restarting on 0x02.
        byte[] junk = {0x02, 0x03, 0x04, 0x20, 0x00, 0x07};
        assertTrue(f.feed(junk, 0).isEmpty());
        assertFalse(f.inFrame());
        // Afterwards a normal frame is read again.
        assertEquals(1, f.feed(shortFrame(0x77), 0).size());
    }

    @Test
    void zeroFeInsideAStandardReportDoesNotBlockTheFramer() {
        Framer f = new Framer();
        // The 0x22 trailer carries a Unix timestamp, i.e. arbitrary bytes. This
        // one puts a 0xFE at index 102 — inside the last quarter, in front of
        // the seal mark at 110.
        byte[] report = AldesProtocol.buildStandard22(0x00FE_0000L);
        assertTrue(AldesProtocol.isStandard22(report));
        assertEquals((byte) 0xFE, report[102], "0xFE in the payload");
        List<Framer.Frame> out = f.feed(report, 0);
        assertEquals(1, out.size(), "0xFE in the payload must not abort the frame");
        assertArrayEquals(report, out.get(0).bytes());
        assertFalse(f.inFrame());
    }
}
