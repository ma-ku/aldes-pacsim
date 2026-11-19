package de.aldes.pacsim;

import javax.swing.AbstractSpinnerModel;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.Objects;

/**
 * Spinner for a decimal value that can also be empty.
 *
 * <p>An empty editor text means "no value" and reaches the caller as
 * {@link Double#NaN}: the model uses NaN for {@code sans consigne} (a setpoint
 * of {@code 00 00}) and for a measured value the installation does not report.
 * A non-empty text is snapped to the step grid of the range, so the arrows and
 * typed values always land on the values the model accepts.
 *
 * <p>The editor commits with Enter and when it loses the focus; the arrows step
 * immediately. Nothing here talks to the model — {@link PacSimApp} owns that.
 */
final class ValueSpinner extends JSpinner {

    private ValueSpinner(double min, double max, double step) {
        super(new ValueModel(min, max, step));
        DefaultEditor editor = new DefaultEditor(this);
        JFormattedTextField field = editor.getTextField();
        field.setFormatterFactory(new DefaultFormatterFactory(new ValueFormatter()));
        field.setHorizontalAlignment(JTextField.RIGHT);
        field.setColumns(4);
        field.setValue(getValue());
        setEditor(editor);
    }

    /** Spinner for {@code min..max} in {@code step} increments. */
    static ValueSpinner of(double min, double max, double step) {
        return new ValueSpinner(min, max, step);
    }

    /** The current value in °C; {@link Double#NaN} while the field is empty. */
    double celsius() {
        Double value = (Double) getValue();
        return value == null ? Double.NaN : value;
    }

    /** Shows {@code celsius}; {@code NaN} empties the field. */
    void show(double celsius) {
        Double value = Double.isNaN(celsius) ? null : celsius;
        if (!Objects.equals(getValue(), value)) {
            setValue(value);
        }
    }

    /** {@code true} while the user types in the editor. */
    boolean editing() {
        return getEditor() instanceof DefaultEditor editor && editor.getTextField().hasFocus();
    }

    /** The text field that shows the value (highlight target). */
    JTextField textField() {
        return getEditor() instanceof DefaultEditor editor ? editor.getTextField() : null;
    }

    // An arrow press must step from the value that is still in the editor,
    // otherwise a typed value would be lost.

    @Override
    public Object getNextValue() {
        commitPendingText();
        return super.getNextValue();
    }

    @Override
    public Object getPreviousValue() {
        commitPendingText();
        return super.getPreviousValue();
    }

    /** Folds text that is still in the editor into the model. */
    private void commitPendingText() {
        if (getEditor() instanceof DefaultEditor editor) {
            try {
                editor.commitEdit();
            } catch (ParseException e) {
                editor.getTextField().setValue(getValue());
            }
        }
    }

    /** Formats the value; an empty text is "no value". */
    private static final class ValueFormatter extends NumberFormatter {

        ValueFormatter() {
            super(new DecimalFormat("0.0"));
            setAllowsInvalid(true);
        }

        @Override
        public String valueToString(Object value) throws ParseException {
            return value == null ? "" : super.valueToString(value);
        }

        @Override
        public Object stringToValue(String text) throws ParseException {
            return text == null || text.isBlank() ? null : super.stringToValue(text);
        }
    }

    /** {@code null} = no value; everything else is clamped and snapped. */
    private static final class ValueModel extends AbstractSpinnerModel {

        private final double min;
        private final double max;
        private final double step;
        private Double value;

        ValueModel(double min, double max, double step) {
            this.min = min;
            this.max = max;
            this.step = step;
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public void setValue(Object v) {
            Double next = null;
            if (v != null) {
                double d = ((Number) v).doubleValue();
                next = Double.isNaN(d) ? null : snap(d);
            }
            if (!Objects.equals(value, next)) {
                value = next;
                fireStateChanged();
            }
        }

        @Override
        public Object getNextValue() {
            if (value == null) {
                return min;
            }
            return value >= max ? null : snap(value + step);
        }

        @Override
        public Object getPreviousValue() {
            if (value == null) {
                return min;
            }
            return value <= min ? null : snap(value - step);
        }

        private double snap(double v) {
            double clamped = Math.max(min, Math.min(max, v));
            double steps = Math.round((clamped - min) / step);
            return Math.round((min + steps * step) * 100.0) / 100.0;
        }
    }
}
