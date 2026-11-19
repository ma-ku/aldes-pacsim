package de.aldes.pacsim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four time programs follow the pattern documented for the installation
 * (protocol.md: A/C 7–9 and 17–22, B/D additionally 12–14, all four 8–23 at the
 * weekend) and can be edited hour by hour.
 */
class ProgramsTest {

    @Test
    void weekdaysFollowTheDocumentedPattern() {
        Programs p = new Programs();

        // Program A, Monday: 7, 8 and 17..21
        assertFalse(p.isOn(0, 0, 6));
        assertTrue(p.isOn(0, 0, 7));
        assertTrue(p.isOn(0, 0, 8));
        assertFalse(p.isOn(0, 0, 9));
        assertTrue(p.isOn(0, 0, 17));
        assertTrue(p.isOn(0, 0, 21));
        assertFalse(p.isOn(0, 0, 22));
        assertEquals(7, p.onHours(0, 0));

        // Program B additionally covers midday.
        assertTrue(p.isOn(1, 0, 12));
        assertTrue(p.isOn(1, 0, 13));
        assertFalse(p.isOn(1, 0, 14));
        assertEquals(9, p.onHours(1, 0));

        // C and D are the cooling twins of A and B.
        assertEquals(p.dayBits(0, 0), p.dayBits(2, 0));
        assertEquals(p.dayBits(1, 0), p.dayBits(3, 0));
    }

    @Test
    void weekendIsTheSameForEveryProgram() {
        Programs p = new Programs();
        for (int program = 0; program < Programs.COUNT; program++) {
            assertEquals(15, p.onHours(program, 5), "Saturday, program " + Programs.letter(program));
            assertFalse(p.isOn(program, 5, 7));
            assertTrue(p.isOn(program, 5, 8));
            assertTrue(p.isOn(program, 5, 22));
            assertFalse(p.isOn(program, 5, 23));
            assertEquals(p.dayBits(program, 5), p.dayBits(program, 6), "Sunday like Saturday");
        }
    }

    @Test
    void hoursAreSwitchedOneByOne() {
        Programs p = new Programs();
        p.set(2, 6, 22, false);
        assertFalse(p.isOn(2, 6, 22));
        assertTrue(p.isOn(2, 6, 21), "neighbours stay untouched");

        p.set(2, 6, 3, true);
        assertTrue(p.isOn(2, 6, 3));
        assertEquals(15, p.onHours(2, 6), "one off, one on");
    }

    @Test
    void storedDaysIgnoreBitsBeyondHour23() {
        Programs p = new Programs();
        p.setDayBits(3, 4, 0x1FF_FFFF);
        assertEquals(Programs.ALL_HOURS, p.dayBits(3, 4));

        p.setDayBits(3, 4, 0);
        assertEquals(0, p.onHours(3, 4));
    }

    @Test
    void resetRestoresTheDefaults() {
        Programs p = new Programs();
        p.setDayBits(0, 0, 0);
        assertEquals(0, p.onHours(0, 0));

        p.reset();
        assertEquals(7, p.onHours(0, 0));
    }

    @Test
    void namesAreStable() {
        assertEquals("A", Programs.letter(0));
        assertEquals("D", Programs.letter(3));
        assertEquals("Mo", Programs.dayName(0));
        assertEquals("Su", Programs.dayName(6));
    }
}
