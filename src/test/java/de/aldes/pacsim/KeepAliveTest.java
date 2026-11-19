package de.aldes.pacsim;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keep-alive dialogue from protocol.md §3: the installation pings every
 * 2..20 s, the gateway answers with a pong and may switch the installation to
 * standard mode with the init frame.
 */
class KeepAliveTest {

    private static KeepAlive seeded() {
        return new KeepAlive(new Random(42));
    }

    private static KeepAlive enabled() {
        KeepAlive k = seeded();
        k.setEnabled(true);
        return k;
    }

    @Test
    void keepAliveIsOffByDefault() {
        KeepAlive k = seeded();

        assertFalse(k.enabled());
        assertFalse(k.pingDue(0), "no ping while the dialogue is off");
        assertFalse(k.pingDue(60_000));
        assertTrue(k.statusText(0).contains("keep-alive off"), k.statusText(0));
    }

    @Test
    void switchingStartsAndEndsTheSession() {
        KeepAlive k = seeded();
        k.setEnabled(true);
        assertTrue(k.enabled());
        assertTrue(k.pingDue(0), "the first ping goes out at once");

        k.onPingSent(0);
        k.onFrame(AldesProtocol.INIT_STANDARD, 10);
        assertEquals(KeepAlive.Mode.REPORT, k.mode());

        k.setEnabled(false);
        assertFalse(k.enabled());
        assertFalse(k.pingDue(60_000), "no ping after switching off");
        assertEquals(KeepAlive.Mode.REPORT, k.mode(), "switching off ends the session");
        assertEquals(0, k.pings());
    }

    @Test
    void firstPingGoesOutImmediately() {
        KeepAlive k = enabled();
        assertTrue(k.pingDue(1_000), "first ping without waiting");
        k.onPingSent(1_000);
        assertEquals(1, k.pings());
        assertFalse(k.pingDue(1_001));
    }

    @Test
    void intervalStaysInsideTheDocumentedWindow() {
        KeepAlive k = enabled();
        long now = 0;
        for (int i = 0; i < 200; i++) {
            k.onPingSent(now);
            long interval = k.intervalMs();
            assertTrue(interval >= KeepAlive.MIN_INTERVAL_MS && interval <= KeepAlive.MAX_INTERVAL_MS,
                    "interval " + interval + " outside 2..20 s");
            assertFalse(k.pingDue(now + interval - 1));
            assertTrue(k.pingDue(now + interval));
            now += interval;
        }
        assertEquals(200, k.pings());
    }

    @Test
    void theInitFrameLeavesDebugModeBehind() {
        KeepAlive k = seeded();
        assertEquals(KeepAlive.Mode.REPORT, k.mode(), "a session starts with the 0x21 report");
        assertEquals(KeepAlive.FrameKind.PONG, k.onFrame(AldesProtocol.PONG, 5_000));
        assertEquals(1, k.pongs());
        assertEquals(KeepAlive.Mode.REPORT, k.mode(), "a pong does not change the datagram");

        k.setMode(KeepAlive.Mode.DEBUG);
        assertEquals("debug (0x25, 175 B)", k.modeText());

        assertEquals(KeepAlive.FrameKind.INIT_STANDARD, k.onFrame(AldesProtocol.INIT_STANDARD, 6_000));
        assertEquals(1, k.inits());
        assertEquals(KeepAlive.Mode.REPORT, k.mode(), "the init frame ends debug mode");
        assertEquals("report (0x21, 90 B)", k.modeText());
    }

    @Test
    void theWindowCanSwitchTheDatagramAtAnyTime() {
        KeepAlive k = enabled();
        assertEquals("report (0x21, 90 B)", k.modeText());

        k.setMode(KeepAlive.Mode.STANDARD);
        assertEquals("standard (0x22, 112 B)", k.modeText());

        k.setMode(KeepAlive.Mode.DEBUG);
        assertEquals("debug (0x25, 175 B)", k.modeText());
        assertTrue(k.statusText(0).contains("debug (0x25, 175 B)"), k.statusText(0));
    }

    @Test
    void modbusReadIsCountedAndOtherFramesPassThrough() {
        KeepAlive k = seeded();
        assertEquals(KeepAlive.FrameKind.MODBUS_READ, k.onFrame(AldesProtocol.MODBUS_READ_SETPOINTS, 0));
        assertEquals(1, k.modbusReads());
        assertEquals(KeepAlive.Mode.REPORT, k.mode());

        byte[] report = AldesProtocol.buildReport(new AldesProtocol.State(
                AldesProtocolTest.zones(20, 20, 20, 20),
                AldesProtocolTest.zones(19, 19, 19, 19), 0, 0, 0, null, null,
                new AldesProtocol.PacClock(2026, 1, 1, 0, 0, 0)), 0);
        assertEquals(KeepAlive.FrameKind.OTHER, k.onFrame(report, 0));
    }

    @Test
    void newSessionStartsInReportModeWithoutHistory() {
        KeepAlive k = enabled();
        k.onPingSent(1_000);
        k.onFrame(AldesProtocol.PONG, 2_000);
        k.setMode(KeepAlive.Mode.STANDARD);
        assertEquals(KeepAlive.Mode.STANDARD, k.mode());

        k.resetSession();
        assertEquals(KeepAlive.Mode.REPORT, k.mode());
        assertEquals(0, k.pings());
        assertEquals(0, k.pongs());
        assertEquals(0, k.inits());
        assertEquals(0, k.modbusReads());
        assertTrue(k.pingDue(4_000), "the next connection pings again");
    }

    @Test
    void statusTextReportsPingsPongsAndMode() {
        KeepAlive k = enabled();
        assertTrue(k.statusText(0).contains("no ping sent yet"));
        k.onPingSent(1_000);
        assertTrue(k.statusText(1_500).contains("no pong yet"));
        k.onFrame(AldesProtocol.PONG, 3_000);
        String text = k.statusText(8_000);
        assertTrue(text.contains("1 ping"), text);
        assertTrue(text.contains("1 pong"), text);
        assertTrue(text.contains("5 s ago"), text);
        assertTrue(text.contains("mode report (0x21, 90 B)"), text);
    }
}
