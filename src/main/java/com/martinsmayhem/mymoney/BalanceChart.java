package com.martinsmayhem.mymoney;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.time.DayOfWeek;
import java.time.LocalDate;
import javax.swing.JComponent;
import javax.swing.ToolTipManager;

/**
 * An account's balance over time (the past 12 months; for a loan, what's left
 * to pay since it was made), as a line: amount up the
 * side, time along the bottom, on a light gray background whatever the theme.
 * The line is black where the balance is zero or more and red where it's
 * negative, changing color exactly where it crosses the black zero line.
 * Along the bottom, a tall tick (labeled with the month number) starts each
 * month, with half-height ticks for the Sundays in between. Hovering shows
 * the date and balance.
 */
final class BalanceChart extends JComponent {

    // Light theme: dark ink on light gray. Dark theme: light ink on dark gray.
    private static final Color[] BACKGROUND = {new Color(0xE6E6E6), new Color(0x17181A)};
    private static final Color[] POSITIVE = {Color.BLACK, new Color(0xE8E8E8)};
    private static final Color[] NEGATIVE = {new Color(0xD01F1F), new Color(0xFF6B61)};      // red
    private static final Color[] GRID = {new Color(0xC8C8C8), new Color(0x3A3C40)};
    private static final Color[] TEXT = {new Color(0x333333), new Color(0xBBBBBB)};
    private static final int MONTH_TICK = 8;
    private static final int SUNDAY_TICK = MONTH_TICK / 2;                     // half as tall

    private LocalDate start;
    private long[] balances = new long[0];
    // Plot area and scale, from the last paint (for tooltips).
    private int left;
    private int right;
    private int top;
    private int bottom;
    private long minY;
    private long maxY;

    BalanceChart() {
        setPreferredSize(new Dimension(420, 120));
        setMinimumSize(new Dimension(200, 90));
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    /** The balances to draw: one per day, the first on start (none: an empty chart). */
    void setData(LocalDate start, long[] balances) {
        this.start = start;
        this.balances = balances == null ? new long[0] : balances;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        int ink = Theme.isDark() ? 1 : 0;           // which colors: light theme 0, dark 1
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(BACKGROUND[ink]);
        g2.fillRect(0, 0, getWidth(), getHeight());
        if (balances.length < 2 || start == null) {
            g2.dispose();
            return;
        }
        Font small = getFont().deriveFont(Math.max(9f, getFont().getSize2D() - 2));
        g2.setFont(small);
        FontMetrics fm = g2.getFontMetrics();

        // The amount range always includes zero, rounded out to clean tick values.
        long lo = 0;
        long hi = 0;
        for (long b : balances) {
            lo = Math.min(lo, b);
            hi = Math.max(hi, b);
        }
        if (lo == hi) {
            hi = lo + 10_000;                           // a flat $0 line still gets a scale
        }
        long step = niceStep((hi - lo) / 3.0);
        minY = Math.floorDiv(lo, step) * step;
        maxY = -Math.floorDiv(-hi, step) * step;

        // Amount labels on the left set the plot's left edge.
        int labelWidth = 0;
        for (long v = minY; v <= maxY; v += step) {
            labelWidth = Math.max(labelWidth, fm.stringWidth(axisMoney(v)));
        }
        left = labelWidth + 10;
        right = getWidth() - 8;
        top = 6;
        bottom = getHeight() - fm.getHeight() - MONTH_TICK - 2;
        if (right - left < 20 || bottom - top < 20) {
            g2.dispose();
            return;
        }

        // Amount gridlines and labels (a recessive gray), then the zero line in black.
        for (long v = minY; v <= maxY; v += step) {
            int y = y(v);
            g2.setColor(GRID[ink]);
            g2.drawLine(left, y, right, y);
            g2.setColor(TEXT[ink]);
            String label = axisMoney(v);
            g2.drawString(label, left - 6 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
        }
        g2.setColor(POSITIVE[ink]);
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(left, y(0), right, y(0));

        // Time along the bottom: month ticks (labeled) and half-height Sunday ticks. Over a
        // long stretch (a loan's whole life) the Sundays are left out once they'd crowd, and
        // only the month labels with room for them are drawn.
        g2.setColor(TEXT[ink]);
        g2.drawLine(left, bottom, right, bottom);
        boolean sundays = (right - left) * 7.0 / balances.length >= 3;
        int labelEnd = Integer.MIN_VALUE;
        for (int i = 0; i < balances.length; i++) {
            LocalDate d = start.plusDays(i);
            int x = x(i);
            if (d.getDayOfMonth() == 1) {
                g2.drawLine(x, bottom, x, bottom + MONTH_TICK);
                String m = String.valueOf(d.getMonthValue());
                int lx = Math.max(left, Math.min(right - fm.stringWidth(m), x - fm.stringWidth(m) / 2));
                if (lx > labelEnd + 4) {
                    g2.drawString(m, lx, bottom + MONTH_TICK + fm.getAscent());
                    labelEnd = lx + fm.stringWidth(m);
                }
            } else if (sundays && d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                g2.drawLine(x, bottom, x, bottom + SUNDAY_TICK);
            }
        }

        // The balance: black at zero or above, red below, split where it crosses zero.
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i < balances.length; i++) {
            long a = balances[i - 1];
            long b = balances[i];
            double xa = x(i - 1);
            double xb = x(i);
            if ((a < 0) == (b < 0)) {
                g2.setColor(b < 0 ? NEGATIVE[ink] : POSITIVE[ink]);
                g2.draw(new Line2D.Double(xa, y(a), xb, y(b)));
            } else {
                double t = (double) -a / (b - a);           // where it crosses zero
                double xz = xa + t * (xb - xa);
                g2.setColor(a < 0 ? NEGATIVE[ink] : POSITIVE[ink]);
                g2.draw(new Line2D.Double(xa, y(a), xz, y(0)));
                g2.setColor(b < 0 ? NEGATIVE[ink] : POSITIVE[ink]);
                g2.draw(new Line2D.Double(xz, y(0), xb, y(b)));
            }
        }
        g2.dispose();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (balances.length < 2 || start == null || right <= left || e.getX() < left || e.getX() > right) {
            return null;
        }
        int i = (int) Math.round((e.getX() - left) * (balances.length - 1.0) / (right - left));
        i = Math.max(0, Math.min(balances.length - 1, i));
        return start.plusDays(i).format(LedgerTableModel.DISPLAY_DATE) + ": " + Money.format(balances[i]);
    }

    private int x(int day) {
        return left + (int) Math.round((right - left) * (double) day / (balances.length - 1));
    }

    private int y(long cents) {
        return bottom - (int) Math.round((bottom - top) * (double) (cents - minY) / (maxY - minY));
    }

    /** A clean step near raw (1, 2 or 5 times a power of ten), in cents. */
    private static long niceStep(double raw) {
        double r = Math.max(raw, 100);
        double mag = Math.pow(10, Math.floor(Math.log10(r)));
        double n = r / mag;
        double nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
        return (long) (nice * mag);
    }

    /** "$1,500" / "-$2,000" (whole dollars when they are whole). */
    private static String axisMoney(long cents) {
        String s = Money.format(cents);
        return s.endsWith(".00") ? s.substring(0, s.length() - 3) : s;
    }
}
