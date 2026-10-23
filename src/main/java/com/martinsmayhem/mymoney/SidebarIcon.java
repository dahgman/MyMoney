package com.martinsmayhem.mymoney;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;

/**
 * A window with a side panel, drawn in the text color: the button that hides
 * and shows the account list. The panel is filled in while the list is shown.
 */
final class SidebarIcon implements Icon {

    private static final int W = 18;
    private static final int H = 14;

    private final java.util.function.BooleanSupplier shown;

    SidebarIcon(java.util.function.BooleanSupplier shown) {
        this.shown = shown;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(c == null ? java.awt.Color.DARK_GRAY : c.getForeground());
        g2.translate(x, y);
        g2.setStroke(new BasicStroke(1.4f));
        g2.draw(new RoundRectangle2D.Float(0.7f, 0.7f, W - 1.4f, H - 1.4f, 4, 4));
        g2.drawLine(6, 1, 6, H - 1);
        if (shown.getAsBoolean()) {
            g2.fill(new RoundRectangle2D.Float(1.5f, 1.5f, 4.5f, H - 3f, 2, 2));
        }
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return W;
    }

    @Override
    public int getIconHeight() {
        return H;
    }
}
