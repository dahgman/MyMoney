package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * A transaction's status. The order listed here is the sort order,
 * and the order shown in the ledger's Status drop-down.
 * The one-letter code is what gets stored in txn.status.
 */
public enum TxnStatus {
    RECONCILED("R", "Reconciled", new Color(142, 68, 173)),   // purple
    CLEARED("C", "Cleared", new Color(39, 174, 96)),          // green
    PENDING("P", "Pending", new Color(241, 196, 15)),         // yellow
    PAID("D", "Paid", new Color(230, 126, 34)),               // orange
    NONE("U", "None", null);                                  // no dot

    private final String code;
    private final String label;
    private final Icon icon;

    TxnStatus(String code, String label, Color color) {
        this.code = code;
        this.label = label;
        this.icon = new Dot(color);
    }

    /** The value stored in the database. */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** A colored dot (NONE's dot is invisible but takes the same space). */
    public Icon icon() {
        return icon;
    }

    /** Converts a stored code back to a status; unknown or null means NONE. */
    public static TxnStatus fromCode(String code) {
        for (TxnStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return NONE;
    }

    @Override
    public String toString() {
        return label;
    }

    /** A small filled circle with a slightly darker outline. */
    private static final class Dot implements Icon {

        private static final int SIZE = 12;
        private final Color color;

        Dot(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (color == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x + 1, y + 1, SIZE - 2, SIZE - 2);
            g2.setColor(color.darker());
            g2.drawOval(x + 1, y + 1, SIZE - 3, SIZE - 3);
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
}
