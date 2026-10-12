package com.martinsmayhem.mymoney;

import java.awt.Component;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

/** How the ledger's Status column is drawn and edited. */
public final class StatusCell {

    private StatusCell() {
        // static helper only
    }

    /** Draws just the colored dot, centered, with the status name as a tooltip. */
    public static TableCellRenderer renderer() {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
                TxnStatus s = value instanceof TxnStatus ts ? ts : TxnStatus.NONE;
                setIcon(s.icon());
                setHorizontalAlignment(SwingConstants.CENTER);
                setToolTipText(s == TxnStatus.NONE ? null : s.label());
                return this;
            }
        };
    }

    /** A drop-down showing each status as a dot and its name. */
    public static TableCellEditor editor() {
        return new DefaultCellEditor(comboBox());
    }

    /** The status drop-down on its own (also used by Edit > Change Status). Reconciling isn't offered. */
    public static JComboBox<TxnStatus> comboBox() {
        JComboBox<TxnStatus> combo = new JComboBox<>(TxnStatus.userChoices());
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof TxnStatus s) {
                    setIcon(s.icon());
                    setText(s.label());
                }
                return this;
            }
        });
        return combo;
    }
}
