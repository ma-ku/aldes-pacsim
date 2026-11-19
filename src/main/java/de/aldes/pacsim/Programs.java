package de.aldes.pacsim;

/**
 * Time programming from protocol.md: four programs A–D, seven days starting on
 * Monday, 24 hours each. One bit per hour, {@code 1} = comfort, {@code 0} =
 * reduced — the same packing the installation uses in its 0x23 frame (four LE
 * bytes per day).
 *
 * <p>The defaults are the pattern documented for this installation: A and C
 * heat/cool from 7 to 9 and from 17 to 22 on weekdays, B and D additionally
 * from 12 to 14, and all four from 8 to 23 at the weekend. Ranges end before
 * the second hour, so "7–9" means 7:00 and 8:00.
 */
public final class Programs {

    public static final int COUNT = 4;
    public static final int DAYS = 7;
    public static final int HOURS = 24;

    /** All 24 hours of a day. */
    static final int ALL_HOURS = 0xFF_FFFF;

    private static final String[] LETTERS = {"A", "B", "C", "D"};
    private static final String[] DAY_NAMES = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};

    /** Bit h of {@code days[program][day]} is hour h of that day. */
    private final int[][] days = new int[COUNT][DAYS];

    public Programs() {
        reset();
    }

    /** Back to the documented pattern. */
    public void reset() {
        for (int program = 0; program < COUNT; program++) {
            for (int day = 0; day < DAYS; day++) {
                if (day >= 5) {
                    days[program][day] = range(8, 23);
                } else {
                    days[program][day] = range(7, 9) | range(17, 22)
                            | (program % 2 == 1 ? range(12, 14) : 0);
                }
            }
        }
    }

    public boolean isOn(int program, int day, int hour) {
        return (days[program][day] & bit(hour)) != 0;
    }

    public void set(int program, int day, int hour, boolean on) {
        if (on) {
            days[program][day] |= bit(hour);
        } else {
            days[program][day] &= ~bit(hour);
        }
    }

    /** The 24 hour bits of one day (bit 0 = 0 h). */
    public int dayBits(int program, int day) {
        return days[program][day];
    }

    /** Restores a stored day; bits above hour 23 are dropped. */
    public void setDayBits(int program, int day, int bits) {
        days[program][day] = bits & ALL_HOURS;
    }

    /** How many hours of a program are comfort on that day. */
    public int onHours(int program, int day) {
        return Integer.bitCount(days[program][day]);
    }

    /** {@code "A"} … {@code "D"}. */
    public static String letter(int program) {
        return LETTERS[program];
    }

    /** {@code "Mo"} … {@code "Su"}. */
    public static String dayName(int day) {
        return DAY_NAMES[day];
    }

    private static int bit(int hour) {
        return 1 << hour;
    }

    /** Hours {@code from} (inclusive) to {@code to} (exclusive). */
    private static int range(int from, int to) {
        return ((1 << to) - 1) & ~((1 << from) - 1);
    }
}
