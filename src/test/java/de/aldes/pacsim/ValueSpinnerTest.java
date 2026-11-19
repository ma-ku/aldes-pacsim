package de.aldes.pacsim;

import org.junit.jupiter.api.Test;

import javax.swing.JFormattedTextField;
import java.text.NumberFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The zone spinners: empty = no value, typed values snap, arrows step. */
class ValueSpinnerTest {

    private static ValueSpinner setpoint() {
        return ValueSpinner.of(AldesProtocol.SETPOINT_MIN, AldesProtocol.SETPOINT_MAX, 0.5);
    }

    private static JFormattedTextField editor(ValueSpinner spinner) {
        return (JFormattedTextField) spinner.textField();
    }

    @Test
    void emptyEditorIsNoValue() {
        ValueSpinner s = setpoint();
        s.show(Double.NaN);

        assertTrue(Double.isNaN(s.celsius()));
        assertEquals("", editor(s).getText());
    }

    @Test
    void clearingTheEditorCommitsNoValue() throws Exception {
        ValueSpinner s = setpoint();
        s.show(21.0);

        editor(s).setText("");
        editor(s).commitEdit();

        assertTrue(Double.isNaN(s.celsius()), "an empty field is no setpoint");
        assertEquals("", editor(s).getText());
    }

    @Test
    void typedValueIsSnappedToTheStepGrid() throws Exception {
        ValueSpinner s = setpoint();

        editor(s).setText(NumberFormat.getInstance().format(21.3));
        editor(s).commitEdit();

        assertEquals(21.5, s.celsius(), 1e-9);
    }

    @Test
    void typedValueIsClampedToTheRange() throws Exception {
        ValueSpinner s = setpoint();

        editor(s).setText(NumberFormat.getInstance().format(45.0));
        editor(s).commitEdit();

        assertEquals(AldesProtocol.SETPOINT_MAX, s.celsius(), 1e-9);
    }

    @Test
    void arrowsStepByTheIncrement() {
        ValueSpinner s = setpoint();
        s.show(21.0);

        s.setValue(s.getNextValue());
        assertEquals(21.5, s.celsius(), 1e-9);
        s.setValue(s.getPreviousValue());
        assertEquals(21.0, s.celsius(), 1e-9);
    }

    @Test
    void arrowsStopAtTheBounds() {
        ValueSpinner s = setpoint();
        s.show(AldesProtocol.SETPOINT_MAX);
        assertNull(s.getNextValue(), "no step above the maximum");
        assertEquals(AldesProtocol.SETPOINT_MAX, s.celsius(), 1e-9);

        s.show(AldesProtocol.SETPOINT_MIN);
        assertNull(s.getPreviousValue(), "no step below the minimum");
        assertEquals(AldesProtocol.SETPOINT_MIN, s.celsius(), 1e-9);
    }

    @Test
    void arrowsStepFromTypedText() throws Exception {
        ValueSpinner s = setpoint();

        editor(s).setText(NumberFormat.getInstance().format(23.5));
        s.setValue(s.getNextValue());

        assertEquals(24.0, s.celsius(), 1e-9);
    }

    @Test
    void emptyFieldStartsAtTheMinimum() {
        ValueSpinner s = setpoint();
        s.show(Double.NaN);

        s.setValue(s.getNextValue());
        assertEquals(AldesProtocol.SETPOINT_MIN, s.celsius(), 1e-9);
    }

    @Test
    void temperatureStepsInTenths() {
        ValueSpinner s = ValueSpinner.of(-40.0, 60.0, 0.1);
        s.show(19.5);

        s.setValue(s.getNextValue());
        assertEquals(19.6, s.celsius(), 1e-9);
        s.setValue(s.getPreviousValue());
        assertEquals(19.5, s.celsius(), 1e-9);
    }
}
