package de.aldes.pacsim;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The grid maps pixels to hours and days, and clicking/dragging edits the model. */
class ProgramGridTest {

    @Test
    void pixelsMapToHourAndDay() {
        ProgramGrid grid = new ProgramGrid(new Programs(), 0, () -> {});

        assertEquals(new Point(0, 0), grid.cellAt(ProgramGrid.LEFT, ProgramGrid.TOP));
        assertEquals(new Point(23, 6), grid.cellAt(
                ProgramGrid.LEFT + 23 * ProgramGrid.CELL_W + 1,
                ProgramGrid.TOP + 6 * ProgramGrid.CELL_H + 1));

        assertNull(grid.cellAt(ProgramGrid.LEFT - 1, ProgramGrid.TOP), "left of the grid");
        assertNull(grid.cellAt(ProgramGrid.LEFT, ProgramGrid.TOP - 1), "above the grid");
        assertNull(grid.cellAt(ProgramGrid.LEFT + 24 * ProgramGrid.CELL_W, ProgramGrid.TOP),
                "past the last hour");
        assertNull(grid.cellAt(ProgramGrid.LEFT, ProgramGrid.TOP + 7 * ProgramGrid.CELL_H),
                "past the last day");
    }

    @Test
    void clickSwitchesOneHour() {
        Programs programs = new Programs();
        int[] changes = {0};
        ProgramGrid grid = new ProgramGrid(programs, 0, () -> changes[0]++);

        assertFalse(programs.isOn(0, 0, 3), "Monday 3:00 starts reduced");
        click(grid, cell(0, 3));
        assertTrue(programs.isOn(0, 0, 3));
        assertEquals(8, programs.onHours(0, 0), "exactly one hour was added");
        assertEquals(1, changes[0]);

        click(grid, cell(0, 3));
        assertFalse(programs.isOn(0, 0, 3));
        assertEquals(2, changes[0]);
        assertEquals(7, programs.onHours(0, 0), "back to the default of program A");
    }

    @Test
    void draggingPaintsTheStateOfTheFirstCell() {
        Programs programs = new Programs();
        ProgramGrid grid = new ProgramGrid(programs, 1, () -> {});

        press(grid, cell(0, 4));
        drag(grid, cell(0, 5));
        drag(grid, cell(0, 6));
        release(grid);

        assertTrue(programs.isOn(1, 0, 4));
        assertTrue(programs.isOn(1, 0, 5));
        assertTrue(programs.isOn(1, 0, 6));
    }

    @Test
    void draggingFromAComfortHourClearsTheCellsItCrosses() {
        Programs programs = new Programs();
        ProgramGrid grid = new ProgramGrid(programs, 0, () -> {});

        press(grid, cell(0, 7));
        drag(grid, cell(0, 8));
        release(grid);

        assertFalse(programs.isOn(0, 0, 7));
        assertFalse(programs.isOn(0, 0, 8));
        assertTrue(programs.isOn(0, 0, 17), "the evening block stays");
    }

    private static Point cell(int day, int hour) {
        return new Point(ProgramGrid.LEFT + hour * ProgramGrid.CELL_W + 3,
                ProgramGrid.TOP + day * ProgramGrid.CELL_H + 3);
    }

    private static void click(ProgramGrid grid, Point p) {
        press(grid, p);
        release(grid);
    }

    private static void press(ProgramGrid grid, Point p) {
        dispatch(grid, p, MouseEvent.MOUSE_PRESSED);
    }

    private static void drag(ProgramGrid grid, Point p) {
        dispatch(grid, p, MouseEvent.MOUSE_DRAGGED);
    }

    private static void release(ProgramGrid grid) {
        dispatch(grid, new Point(0, 0), MouseEvent.MOUSE_RELEASED);
    }

    private static void dispatch(ProgramGrid grid, Point p, int id) {
        grid.dispatchEvent(new MouseEvent(grid, id, 0L, 0, p.x, p.y, 1, false,
                MouseEvent.BUTTON1));
    }
}
