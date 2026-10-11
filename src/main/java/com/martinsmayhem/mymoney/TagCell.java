package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.IntFunction;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/** Draws the ledger's Tag column: the tag name on a rounded "pill" in the tag's color. */
public final class TagCell {

    private TagCell() {
        // static helper only
    }

    /** @param colorOfRow the tag color for a table row, or null for none */
    public static TableCellRenderer renderer(IntFunction<Color> colorOfRow) {
        return new DefaultTableCellRenderer() {
            private Color pill;

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String text = value == null ? "" : value.toString();
                pill = text.isEmpty() ? null : colorOfRow.apply(row);
                return this;
            }

            @Override
            protected void paintComponent(Graphics g) {
                if (pill == null) {
                    super.paintComponent(g);            // no tag, or not saved yet: plain text
                    return;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());

                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int pad = 6;
                int maxText = Math.max(0, getWidth() - 4 - 2 * pad);
                String text = fit(getText(), fm, maxText);
                int w = fm.stringWidth(text) + 2 * pad;
                int h = Math.min(getHeight() - 2, fm.getHeight() + 2);
                int x = 2;
                int y = (getHeight() - h) / 2;
                g2.setColor(pill);
                g2.fillRoundRect(x, y, w, h, h, h);
                g2.setColor(textColorFor(pill));
                g2.drawString(text, x + pad, y + (h - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
    }

    /** Black text on light colors, white on dark ones. */
    static Color textColorFor(Color c) {
        double luminance = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        return luminance > 150 ? Color.BLACK : Color.WHITE;
    }

    /** Shortens text with "..." to fit a width. */
    private static String fit(String text, FontMetrics fm, int width) {
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String dots = "...";
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end) + dots) > width) {
            end--;
        }
        return text.substring(0, end) + dots;
    }
}
