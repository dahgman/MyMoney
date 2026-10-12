package com.martinsmayhem.mymoney;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * A small paperclip, drawn in the text color (the ledger's attachments column
 * header). It centers itself across the whole width of the component it is
 * painted in, since some look-and-feels (GTK) pad header cells unevenly and a
 * narrow column's icon would otherwise sit off to one side.
 */
final class PaperclipIcon implements Icon {

    static final PaperclipIcon INSTANCE = new PaperclipIcon();

    private static final int W = 10;
    private static final int H = 16;

    private PaperclipIcon() {
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(c == null ? java.awt.Color.DARK_GRAY : c.getForeground());
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int left = c == null ? x : (c.getWidth() - W) / 2;
        g2.translate(left, y);
        g2.draw(clip());
        g2.dispose();
    }

    /** The paperclip outline: an inner and outer loop joined at the top. */
    private static Path2D clip() {
        Path2D p = new Path2D.Float();
        p.moveTo(5, 5);                    // inner wire, bottom of the middle
        p.lineTo(5, 11);
        p.quadTo(5, 12.5, 6.25, 12.5);
        p.quadTo(7.5, 12.5, 7.5, 11);
        p.lineTo(7.5, 3.5);                // up the right
        p.quadTo(7.5, 1, 5, 1);
        p.quadTo(2.5, 1, 2.5, 3.5);        // round the top
        p.lineTo(2.5, 12.5);               // down the left
        p.quadTo(2.5, 15, 5, 15);
        p.quadTo(7.5, 15, 7.5, 13.5);
        return p;
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
