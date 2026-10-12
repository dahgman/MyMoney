package com.martinsmayhem.mymoney;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import javax.swing.Icon;

/**
 * The ledger's download column: an empty blue circle for a downloaded
 * transaction that was added new, and a blue circle with a line through it
 * for one that matched a transaction already entered.
 */
final class DownloadIcon implements Icon {

    static final DownloadIcon NEW = new DownloadIcon(false);
    static final DownloadIcon MATCHED = new DownloadIcon(true);

    private static final int SIZE = 12;
    private static final Color BLUE = new Color(0x2E86C1);

    private final boolean matched;

    private DownloadIcon(boolean matched) {
        this.matched = matched;
    }

    /** The icon for a LedgerEntry.download value, or null for a transaction entered by hand. */
    static DownloadIcon of(String download) {
        return DownloadImporter.MATCHED.equals(download) ? MATCHED
                : DownloadImporter.NEW.equals(download) ? NEW : null;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(BLUE);
        g2.setStroke(new BasicStroke(1.5f));
        g2.translate(x, y);
        g2.draw(new Ellipse2D.Float(1, 1, SIZE - 2, SIZE - 2));
        if (matched) {
            g2.draw(new Line2D.Float(SIZE - 3, 3, 3, SIZE - 3));     // corner to corner, like ⊘
        }
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}
