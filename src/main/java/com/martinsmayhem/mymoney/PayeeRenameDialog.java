package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/**
 * Account > Manage Payee Renames: the payee names remembered for bank names
 * during imports. The payee can be changed in place (with the same
 * suggestions as the ledger), and renames can be deleted, so that bank name
 * is offered as it comes again.
 */
final class PayeeRenameDialog extends JDialog {

    private final PayeeRenameRepository renames;
    private final List<PayeeRenameRepository.Rename> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JButton deleteButton = new JButton("Delete");
    private boolean changed;

    PayeeRenameDialog(Window owner, PayeeRenameRepository renames, Supplier<List<String>> payeeNames) {
        super(owner, "Manage Payee Renames", ModalityType.APPLICATION_MODAL);
        this.renames = renames;

        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setCellEditor(new AutoCompleteEditor(payeeNames, () -> {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }));
        table.getSelectionModel().addListSelectionListener(e -> deleteButton.setEnabled(table.getSelectedRowCount() > 0));
        ColumnFit.install(table);

        JLabel help = new JLabel("<html><div style='width:380px'>When a downloaded transaction is added as new"
                + " under a payee you chose, its bank name is remembered here, and that payee is offered the"
                + " next time. Bank names are shown simplified (lower case, without numbers or punctuation),"
                + " as they are compared. Double-click a payee to change it.</div></html>");
        help.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));

        deleteButton.setEnabled(false);
        deleteButton.addActionListener(e -> deleteSelected());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(deleteButton);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(close);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8),
                scroll.getBorder()));
        getContentPane().add(help, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        load();
        setSize(560, 420);
        setLocationRelativeTo(owner);
    }

    /** True if anything was changed or deleted. */
    boolean wasChanged() {
        return changed;
    }

    private void load() {
        rows.clear();
        try {
            rows.addAll(renames.findAll());
        } catch (SQLException e) {
            error("Could not read the payee renames:\n" + e.getMessage());
        }
        model.fireTableDataChanged();
    }

    private void deleteSelected() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        String what = selected.length == 1 ? "the rename for \"" + rows.get(selected[0]).bankKey() + "\""
                : selected.length + " renames";
        if (JOptionPane.showConfirmDialog(this, "Delete " + what + "?\n\nTransactions already imported keep"
                + " their payees.", getTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (int r : selected) {
            ids.add(rows.get(r).id());
        }
        try {
            renames.delete(ids);
            changed = true;
        } catch (SQLException e) {
            error("Could not delete:\n" + e.getMessage());
        }
        load();
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Bank name", "Payee"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 1;
        }

        @Override
        public Object getValueAt(int row, int column) {
            PayeeRenameRepository.Rename r = rows.get(row);
            return column == 0 ? r.bankKey() : r.payee();
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            String payee = value == null ? "" : value.toString().trim();
            PayeeRenameRepository.Rename r = rows.get(row);
            if (payee.isEmpty() || payee.equals(r.payee())) {
                return;                                  // blank: keep it (Delete removes a rename)
            }
            try {
                renames.setPayee(r.id(), payee);
                rows.set(row, new PayeeRenameRepository.Rename(r.id(), r.bankKey(), payee));
                changed = true;
            } catch (SQLException e) {
                error("Could not change the payee:\n" + e.getMessage());
            }
            fireTableRowsUpdated(row, row);
        }
    }
}
