package de.aldes.pacsim;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * One program as a clickable grid: seven days (rows) over 24 hours (columns).
 *
 * <p>A click switches the hour under the pointer; dragging paints the state of
 * the first cell over every cell it crosses, so a whole evening is set in one
 * gesture. Orange means comfort, light grey reduced.
 */
final class ProgramGrid extends JComponent {

    static final int CELL_W = 22;
    static final int CELL_H = 20;
    /** Room for the day labels on the left. */
    static final int LEFT = 30;
    /** Room for the hour labels on top. */
    static final int TOP = 18;

    private static final Color ON = new Color(0xE08A2E);
    private static final Color OFF = new Color(0xF1F1F1);
    private static final Color LABEL = new Color(0x606060);
    private static final Color HOVER = new Color(0x303030);

    private final Programs programs;
    private final int program;
    private final Runnable onChange;

    private boolean painting;
    private boolean paintValue;
    private int hoverDay = -1;
    private int hoverHour = -1;

    ProgramGrid(Programs programs, int program, Runnable onChange) {
        this.programs = programs;
        this.program = program;
        this.onChange = onChange;
        setPreferredSize(new Dimension(LEFT + Programs.HOURS * CELL_W + 1,
                TOP + Programs.DAYS * CELL_H + 1));
        setBackground(Color.WHITE);
        setOpaque(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Point cell = cellAt(e.getX(), e.getY());
                if (cell == null) {
                    return;
                }
                painting = true;
                paintValue = !programs.isOn(program, cell.y, cell.x);
                apply(cell);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!painting) {
                    return;
                }
                Point cell = cellAt(e.getX(), e.getY());
                if (cell != null) {
                    apply(cell);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                painting = false;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hover(e);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverDay = -1;
                hoverHour = -1;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Repaints after the model changed from elsewhere (settings, reset). */
    void refresh() {
        repaint();
    }

    /**
     * Cell under a pixel position.
     *
     * @return {@code x} = hour, {@code y} = day, or {@code null} outside the grid
     */
    Point cellAt(int x, int y) {
        if (x < LEFT || y < TOP) {
            return null;
        }
        int hour = (x - LEFT) / CELL_W;
        int day = (y - TOP) / CELL_H;
        if (hour >= Programs.HOURS || day >= Programs.DAYS) {
            return null;
        }
        return new Point(hour, day);
    }

    private void apply(Point cell) {
        if (programs.isOn(program, cell.y, cell.x) == paintValue) {
            return;
        }
        programs.set(program, cell.y, cell.x, paintValue);
        repaint();
        onChange.run();
    }

    private void hover(MouseEvent e) {
        Point cell = cellAt(e.getX(), e.getY());
        int day = cell == null ? -1 : cell.y;
        int hour = cell == null ? -1 : cell.x;
        if (day != hoverDay || hour != hoverHour) {
            hoverDay = day;
            hoverHour = hour;
            repaint();
        }
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        Point cell = cellAt(e.getX(), e.getY());
        if (cell == null) {
            return null;
        }
        return "Program " + Programs.letter(program) + ", " + Programs.dayName(cell.y) + " "
                + cell.x + ":00 — "
                + (programs.isOn(program, cell.y, cell.x) ? "comfort" : "reduced");
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setFont(getFont().deriveFont(Font.PLAIN, 10f));
            FontMetrics fm = g2.getFontMetrics();

            g2.setColor(LABEL);
            for (int hour = 0; hour < Programs.HOURS; hour++) {
                String text = Integer.toString(hour);
                g2.drawString(text, LEFT + hour * CELL_W + (CELL_W - fm.stringWidth(text)) / 2,
                        TOP - 5);
            }
            for (int day = 0; day < Programs.DAYS; day++) {
                String text = Programs.dayName(day);
                g2.drawString(text, 4,
                        TOP + day * CELL_H + (CELL_H + fm.getAscent()) / 2 - 2);
            }

            for (int day = 0; day < Programs.DAYS; day++) {
                for (int hour = 0; hour < Programs.HOURS; hour++) {
                    g2.setColor(programs.isOn(program, day, hour) ? ON : OFF);
                    g2.fillRoundRect(LEFT + hour * CELL_W + 1, TOP + day * CELL_H + 1,
                            CELL_W - 2, CELL_H - 2, 5, 5);
                }
            }
            if (hoverDay >= 0) {
                g2.setColor(HOVER);
                g2.drawRoundRect(LEFT + hoverHour * CELL_W + 1, TOP + hoverDay * CELL_H + 1,
                        CELL_W - 2, CELL_H - 2, 5, 5);
            }
        } finally {
            g2.dispose();
        }
    }
}
