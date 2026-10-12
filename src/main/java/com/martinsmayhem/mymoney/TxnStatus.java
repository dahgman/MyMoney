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
 *
 * RECONCILING is set only by Reconcile Account, for the transactions
 * chosen in an unfinished reconciliation; it can't be picked by hand.
 */
public enum TxnStatus {
    RECONCILED("R", "Reconciled", new Color(142, 68, 173)),   // purple
    CLEARED("C", "Cleared", new Color(39, 174, 96)),          // green
    RECONCILING("I", "Reconciling", new Color(243, 156, 18)), // orange diamond
    PENDING("P", "Pending", new Color(241, 196, 15)),         // yellow
    PAID("D", "Paid", new Color(230, 126, 34)),               // orange
    SCHEDULED("S", "Scheduled", new Color(52, 152, 219)),     // blue
    NONE("U", "None", null);                                  // no dot

    private final String code;
    private final String label;
    private final Icon icon;

    TxnStatus(String code, String label, Color color) {
        this.code = code;
        this.label = label;
        this.icon = new Dot(color, "I".equals(code));
    }

    /** The statuses a user can choose (every one but RECONCILING). */
    public static TxnStatus[] userChoices() {
        return java.util.Arrays.stream(values())
                .filter(s -> s != RECONCILING).toArray(TxnStatus[]::new);
    }

    /** True for Reconciled, Cleared and Reconciling: counted in the cleared balance. */
    public boolean isCleared() {
        return this == RECONCILED || this == CLEARED || this == RECONCILING;
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

    /** A small filled circle (or diamond) with a slightly darker outline. */
    private static final class Dot implements Icon {

        private static final int SIZE = 12;
        private final Color color;
        private final boolean diamond;

        Dot(Color color, boolean diamond) {
            this.color = color;
            this.diamond = diamond;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (color == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (diamond) {
                int m = SIZE / 2;
                java.awt.Polygon p = new java.awt.Polygon(
                        new int[] {x + m, x + SIZE - 1, x + m, x}, new int[] {y, y + m, y + SIZE - 1, y + m}, 4);
                g2.setColor(color);
                g2.fillPolygon(p);
                g2.setColor(color.darker());
                g2.drawPolygon(p);
            } else {
                g2.setColor(color);
                g2.fillOval(x + 1, y + 1, SIZE - 2, SIZE - 2);
                g2.setColor(color.darker());
                g2.drawOval(x + 1, y + 1, SIZE - 3, SIZE - 3);
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
}
