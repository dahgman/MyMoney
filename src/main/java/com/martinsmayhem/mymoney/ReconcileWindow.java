package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * Reconcile Account: every unreconciled transaction, payments on the left
 * and deposits on the right. Clicking a transaction's C column (or pressing
 * Space) selects it for this statement, marking it Reconciling; again
 * deselects it. The totals follow along, and Done is only enabled once the
 * Difference is $0.00. Finish Later (or closing the window) keeps the
 * selections for next time. Cancel asks the caller (see wasCancelled) to
 * put back everything changed since Reconcile Account was started.
 */
final class ReconcileWindow extends JDialog {

    private static final Color DIFF_BAD = new Color(241, 196, 15);     // yellow
    private static final Color DIFF_GOOD = new Color(39, 174, 96);     // green

    private final Account account;
    private final ReconcileRepository reconciles;
    private final TransactionRepository transactions;
    private final AttachmentRepository attachments;
    private final Supplier<List<String>> payees;
    private final Supplier<List<String>> categories;
    private final Supplier<List<String>> tags;
    private ReconcileRepository.Reconciliation reconciliation;

    private final Pane paymentsPane = new Pane("Payments");
    private final Pane depositsPane = new Pane("Deposits");
    private Pane activePane = paymentsPane;             // the one last clicked, for Edit and Delete

    private final JLabel statementLabel = new JLabel();
    private final JLabel beginningLabel = rightLabel();
    private final JLabel currentLabel = rightLabel();
    private final JLabel targetLabel = rightLabel();
    private final JLabel differenceLabel = rightLabel();
    private final JButton doneButton = new JButton("Done");
    private AttachmentsPanel statementFiles;            // set by statementPanel()
    private long beginningCents;
    private boolean finished;
    private boolean cancelled;
    private final boolean canCancel;

    ReconcileWindow(Window owner, Account account, ReconcileRepository.Reconciliation reconciliation,
                    ReconcileRepository reconciles, TransactionRepository transactions,
                    AttachmentRepository attachments, Supplier<List<String>> payees,
                    Supplier<List<String>> categories, Supplier<List<String>> tags, boolean canCancel) {
        super(owner, "Reconcile Account: " + account.name(), ModalityType.APPLICATION_MODAL);
        this.canCancel = canCancel;
        this.account = account;
        this.reconciliation = reconciliation;
        this.reconciles = reconciles;
        this.transactions = transactions;
        this.attachments = attachments;
        this.payees = payees;
        this.categories = categories;
        this.tags = tags;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);     // closing = Finish Later

        JLabel title = new JLabel("Reconcile Account: " + account.name(), SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 6));
        statementLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(title, BorderLayout.NORTH);
        top.add(statementLabel, BorderLayout.SOUTH);

        JPanel panes = new JPanel(new GridLayout(1, 2, 10, 0));
        panes.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        panes.add(paymentsPane);
        panes.add(depositsPane);

        JPanel bottom = new JPanel(new GridLayout(1, 2, 10, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        bottom.add(statementPanel());
        bottom.add(controlsPanel());

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(panes, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);

        reload();
        setSize(1150, 760);
        setLocationRelativeTo(owner);
    }

    /** True if Done was pressed (the reconciliation is finished). */
    boolean wasFinished() {
        return finished;
    }

    /** True if Cancel was pressed: everything changed while reconciling is to be put back. */
    boolean wasCancelled() {
        return cancelled;
    }

    // ----- Layout ----------------------------------------------------------

    /** Left of the buttons: the statement's details and its attached files. */
    private JPanel statementPanel() {
        AttachmentsPanel files = new AttachmentsPanel("Bank Statement", new AttachmentsPanel.Target() {
            @Override
            public List<AttachmentRepository.Attachment> list() throws SQLException {
                return attachments.forReconciliation(reconciliation.id());
            }

            @Override
            public void add(Path file) throws SQLException, IOException {
                attachments.addToStatement(account, reconciliation.id(), reconciliation.statementDate(), file);
            }

            @Override
            public void remove(AttachmentRepository.Attachment a) throws SQLException, IOException {
                attachments.remove(a, account);
            }

            @Override
            public Path file(AttachmentRepository.Attachment a) {
                return attachments.file(a, account);
            }
        }, () -> { });
        statementFiles = files;
        JButton change = new JButton("Change Statement...");
        change.addActionListener(e -> changeStatement());
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(files, BorderLayout.CENTER);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(change);
        p.add(row, BorderLayout.SOUTH);
        return p;
    }

    /** Right: Cancel / Finish Later / Deselect All / Done, the transaction buttons, and the balances. */
    private JPanel controlsPanel() {
        JButton cancel = new JButton("Cancel");
        cancel.setToolTipText(canCancel ? "Put everything back as it was before this reconcile was started"
                : "Undo isn't available for this file, so changes can't be put back");
        cancel.setEnabled(canCancel);
        cancel.addActionListener(e -> cancel());
        JButton later = new JButton("Finish Later");
        later.addActionListener(e -> dispose());
        JButton deselectAll = new JButton("Deselect All");
        deselectAll.addActionListener(e -> deselectAll());
        doneButton.setFont(doneButton.getFont().deriveFont(Font.BOLD));
        doneButton.addActionListener(e -> done());
        JPanel main = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        main.add(cancel);
        main.add(later);
        main.add(deselectAll);
        main.add(doneButton);

        JButton edit = new JButton("Edit");
        edit.addActionListener(e -> editSelected());
        JButton add = new JButton("New");
        add.addActionListener(e -> newTransaction());
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> deleteSelected());
        JButton files = new JButton("Attachments");
        files.addActionListener(e -> attachmentsOfSelected());
        JPanel txnButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        txnButtons.add(edit);
        txnButtons.add(add);
        txnButtons.add(delete);
        txnButtons.add(files);

        JPanel balances = new JPanel(new GridBagLayout());
        String[] names = {"Beginning Balance:", "Current Balance:", "Target Balance:", "Difference:"};
        JLabel[] values = {beginningLabel, currentLabel, targetLabel, differenceLabel};
        for (int i = 0; i < names.length; i++) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = i;
            c.anchor = GridBagConstraints.LINE_END;
            c.insets = new Insets(1, 0, 1, 8);
            balances.add(new JLabel(names[i]), c);
            c = new GridBagConstraints();
            c.gridx = 1;
            c.gridy = i;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(1, 0, 1, 0);
            balances.add(values[i], c);
        }
        differenceLabel.setOpaque(true);
        differenceLabel.setFont(differenceLabel.getFont().deriveFont(Font.BOLD));
        differenceLabel.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));

        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.PAGE_AXIS));
        p.add(main);
        p.add(javax.swing.Box.createVerticalStrut(10));
        JLabel fnLabel = new JLabel("Transaction Functions:");
        fnLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        p.add(fnLabel);
        p.add(txnButtons);
        p.add(javax.swing.Box.createVerticalStrut(10));
        p.add(balances);
        return p;
    }

    private static JLabel rightLabel() {
        JLabel l = new JLabel(" ", SwingConstants.RIGHT);
        l.setPreferredSize(new java.awt.Dimension(130, l.getPreferredSize().height + 4));
        return l;
    }

    // ----- Data ------------------------------------------------------------

    /** Re-reads the transactions and recomputes every total, keeping the table selections. */
    private void reload() {
        List<LedgerEntry> pays = new ArrayList<>();
        List<LedgerEntry> deps = new ArrayList<>();
        try {
            for (LedgerEntry e : transactions.findByAccount(account.id())) {
                if (e.status == TxnStatus.RECONCILED) {
                    continue;
                }
                (e.amountCents < 0 ? pays : deps).add(e);
            }
            beginningCents = reconciles.beginningBalance(account);
        } catch (SQLException ex) {
            error("Could not read the transactions:\n" + ex.getMessage());
        }
        paymentsPane.setRows(pays);
        depositsPane.setRows(deps);

        statementLabel.setText("Statement date: " + reconciliation.statementDate().format(LedgerTableModel.DISPLAY_DATE)
                + "      Ending balance: " + Money.format(reconciliation.endingBalanceCents()));
        long current = beginningCents + paymentsPane.clearedCents() + depositsPane.clearedCents();
        long difference = reconciliation.endingBalanceCents() - current;
        beginningLabel.setText(Money.format(beginningCents));
        currentLabel.setText(Money.format(current));
        targetLabel.setText(Money.format(reconciliation.endingBalanceCents()));
        differenceLabel.setText(Money.format(difference));
        differenceLabel.setBackground(difference == 0 ? DIFF_GOOD : DIFF_BAD);
        differenceLabel.setForeground(difference == 0 ? Color.WHITE : Color.BLACK);
        doneButton.setEnabled(difference == 0);
        doneButton.setToolTipText(difference == 0 ? null : "The difference must be $0.00 to finish");
    }

    /** Selects or deselects transactions for this statement. */
    private void setSelected(List<LedgerEntry> rows, boolean select) {
        List<Long> ids = new ArrayList<>();
        for (LedgerEntry e : rows) {
            ids.add(e.id);
        }
        try {
            if (select) {
                reconciles.select(ids);
            } else {
                reconciles.deselect(ids);
            }
        } catch (SQLException ex) {
            error("Could not change the transactions:\n" + ex.getMessage());
        }
        reload();
    }

    private void deselectAll() {
        try {
            reconciles.deselectAll(account.id());
        } catch (SQLException ex) {
            error("Could not deselect the transactions:\n" + ex.getMessage());
        }
        reload();
    }

    private void done() {
        try {
            if (attachments.forReconciliation(reconciliation.id()).isEmpty()) {
                Object[] options = {"Finish Without Statement", "Go Back"};
                int choice = JOptionPane.showOptionDialog(this,
                        "No bank statement is attached. Finish anyway?\n\n(Use Add... under Bank Statement to attach it.)",
                        getTitle(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, options, options[1]);
                if (choice != 0) {
                    return;
                }
            }
            reconciles.finish(reconciliation, beginningCents);
        } catch (SQLException ex) {
            error("Could not finish the reconciliation:\n" + ex.getMessage());
            return;
        }
        finished = true;
        dispose();
    }

    /** Cancel: after a yes, closes the window for the caller to put everything back. */
    private void cancel() {
        int r = JOptionPane.showConfirmDialog(this, "Cancel this reconcile and put back everything changed since"
                + " you started it?\n\nEvery transaction's status, any transactions added, edited or deleted here,"
                + "\nand the statement's balance and date go back as they were. Files attached since then are"
                + "\nremoved. (Any you removed or renamed can't be put back.)",
                getTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) {
            return;
        }
        cancelled = true;
        dispose();
    }

    private void changeStatement() {
        LocalDate periodStart;
        try {
            periodStart = reconciles.statementStart(account.id());
        } catch (SQLException ex) {
            error("Could not read the last reconciliation:\n" + ex.getMessage());
            return;
        }
        ReconcileStartDialog dlg = new ReconcileStartDialog(this, account, beginningCents,
                reconciliation.endingBalanceCents(), reconciliation.statementDate(), periodStart, true);
        dlg.setVisible(true);
        if (!dlg.wasAccepted()) {
            return;
        }
        try {
            reconciliation = reconciles.start(account, dlg.startDate(), dlg.statementDate(), dlg.endingCents(),
                    dlg.asOf());
        } catch (SQLException ex) {
            error("Could not change the statement:\n" + ex.getMessage());
        }
        statementFiles.reload();
        reload();
    }

    // ----- Transaction functions ------------------------------------------

    private LedgerEntry selectedEntry() {
        LedgerEntry e = activePane.selectedEntry();
        if (e == null) {
            e = (activePane == paymentsPane ? depositsPane : paymentsPane).selectedEntry();
        }
        if (e == null) {
            JOptionPane.showMessageDialog(this, "Select a transaction first.", getTitle(),
                    JOptionPane.INFORMATION_MESSAGE);
        }
        return e;
    }

    private void editSelected() {
        LedgerEntry e = selectedEntry();
        if (e != null) {
            edit(e);
        }
    }

    private void edit(LedgerEntry e) {
        TransactionDialog dlg = new TransactionDialog(this, account, e, transactions, attachments,
                payees, categories, tags);
        dlg.setVisible(true);
        reload();
    }

    private void newTransaction() {
        TransactionDialog dlg = new TransactionDialog(this, account, null, transactions, attachments,
                payees, categories, tags);
        dlg.setVisible(true);
        reload();
    }

    private void deleteSelected() {
        LedgerEntry e = selectedEntry();
        if (e == null) {
            return;
        }
        String what = (e.payee == null || e.payee.isBlank() ? "this transaction" : "\"" + e.payee + "\"")
                + " (" + Money.format(e.amountCents) + " on " + e.date.format(LedgerTableModel.DISPLAY_DATE) + ")";
        if (JOptionPane.showConfirmDialog(this, "Delete " + what + "?", getTitle(),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            transactions.delete(e.id);
        } catch (SQLException ex) {
            error("Could not delete the transaction:\n" + ex.getMessage());
        }
        reload();
    }

    private void attachmentsOfSelected() {
        LedgerEntry e = selectedEntry();
        if (e != null) {
            AttachmentsDialog.show(this, account, e.id, e.date, attachments);
            reload();
        }
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
    }

    // ----- One side: payments or deposits ---------------------------------

    private static final int DATE = 0;
    private static final int NUM = 1;
    private static final int PAYEE = 2;
    private static final int DESCRIPTION = 3;
    private static final int C = 4;
    private static final int AMOUNT = 5;
    private static final String[] COLUMNS = {"Date", "Check#", "Payee", "Description", "C", "Amount"};

    /** A titled table with Cleared / Uncleared totals under it. */
    private final class Pane extends JPanel {

        private final List<LedgerEntry> rows = new ArrayList<>();
        private final Model model = new Model();
        private final JTable table = new JTable(model);
        private final JLabel clearedLabel = new JLabel();
        private final JLabel unclearedLabel = new JLabel();

        Pane(String title) {
            super(new BorderLayout(0, 4));
            JLabel heading = new JLabel(title, SwingConstants.CENTER);
            heading.setFont(heading.getFont().deriveFont(Font.BOLD));
            add(heading, BorderLayout.NORTH);

            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            table.setFillsViewportHeight(true);
            TableRowSorter<Model> sorter = new TableRowSorter<>(model) {
                @Override
                public void toggleSortOrder(int column) {
                    if (!ColumnFit.pressedOnBorder(table.getTableHeader())) {
                        super.toggleSortOrder(column);  // a click on a border resizes, not sorts
                    }
                }
            };
            sorter.setSortKeys(List.of(new RowSorter.SortKey(DATE, SortOrder.ASCENDING)));
            table.setRowSorter(sorter);
            int[] widths = {90, 60, 150, 150, 26, 90};
            for (int i = 0; i < widths.length; i++) {
                table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            }
            table.getColumnModel().getColumn(C).setMaxWidth(30);
            ColumnFit.install(table);                   // double-click a header border to fit
            javax.swing.table.TableCellRenderer plainHeader = table.getTableHeader().getDefaultRenderer();
            table.getTableHeader().setDefaultRenderer((t, value, sel, focus, row, col) -> {
                Component h = plainHeader.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (h instanceof JLabel label) {        // C centered, the rest as they were
                    label.setHorizontalAlignment(t.convertColumnIndexToModel(col) == C
                            ? SwingConstants.CENTER : SwingConstants.LEADING);
                }
                return h;
            });
            table.getColumnModel().getColumn(DATE).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                protected void setValue(Object value) {
                    setText(value instanceof LocalDate d ? d.format(LedgerTableModel.DISPLAY_DATE) : "");
                }
            });
            table.getColumnModel().getColumn(C).setCellRenderer(StatusCell.renderer());
            DefaultTableCellRenderer money = new DefaultTableCellRenderer() {
                @Override
                protected void setValue(Object value) {
                    setText(value instanceof Long cents ? Money.format(cents).replace("$", "") : "");
                }
            };
            money.setHorizontalAlignment(SwingConstants.RIGHT);
            table.getColumnModel().getColumn(AMOUNT).setCellRenderer(money);

            // Click C to select / deselect; double-click elsewhere to edit.
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    activePane = Pane.this;
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    int col = table.columnAtPoint(e.getPoint());
                    if (viewRow < 0 || e.getButton() != MouseEvent.BUTTON1) {
                        return;
                    }
                    LedgerEntry entry = rows.get(table.convertRowIndexToModel(viewRow));
                    if (table.convertColumnIndexToModel(col) == C && e.getClickCount() == 1) {
                        setSelected(List.of(entry), entry.status != TxnStatus.RECONCILING);
                    } else if (e.getClickCount() == 2 && table.convertColumnIndexToModel(col) != C) {
                        edit(entry);
                    }
                }
            });
            table.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    activePane = Pane.this;
                }
            });
            // Space: select the highlighted rows (or deselect them, if all are selected already).
            table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle");
            table.getActionMap().put("toggle", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    List<LedgerEntry> chosen = highlighted();
                    if (!chosen.isEmpty()) {
                        boolean allIn = chosen.stream().allMatch(x -> x.status == TxnStatus.RECONCILING);
                        setSelected(chosen, !allIn);
                    }
                }
            });
            add(new JScrollPane(table), BorderLayout.CENTER);

            JPanel totals = new JPanel(new GridLayout(1, 2));
            clearedLabel.setHorizontalAlignment(SwingConstants.CENTER);
            unclearedLabel.setHorizontalAlignment(SwingConstants.CENTER);
            totals.add(clearedLabel);
            totals.add(unclearedLabel);
            add(totals, BorderLayout.SOUTH);
        }

        /** New rows, keeping the highlighted ones highlighted. */
        void setRows(List<LedgerEntry> newRows) {
            Set<Long> keep = new HashSet<>();
            for (LedgerEntry e : highlighted()) {
                keep.add(e.id);
            }
            rows.clear();
            rows.addAll(newRows);
            model.fireTableDataChanged();
            for (int i = 0; i < rows.size(); i++) {
                if (keep.contains(rows.get(i).id)) {
                    int v = table.convertRowIndexToView(i);
                    table.addRowSelectionInterval(v, v);
                }
            }
            clearedLabel.setText("Cleared: " + Money.format(clearedCents()));
            unclearedLabel.setText("Uncleared: " + Money.format(unclearedCents()));
        }

        long clearedCents() {
            return rows.stream().filter(e -> e.status == TxnStatus.RECONCILING).mapToLong(LedgerEntry::netCents).sum();
        }

        long unclearedCents() {
            return rows.stream().filter(e -> e.status != TxnStatus.RECONCILING).mapToLong(LedgerEntry::netCents).sum();
        }

        List<LedgerEntry> highlighted() {
            List<LedgerEntry> list = new ArrayList<>();
            for (int v : table.getSelectedRows()) {
                list.add(rows.get(table.convertRowIndexToModel(v)));
            }
            return list;
        }

        LedgerEntry selectedEntry() {
            List<LedgerEntry> h = highlighted();
            return h.isEmpty() ? null : h.get(0);
        }

        private final class Model extends AbstractTableModel {
            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return COLUMNS.length;
            }

            @Override
            public String getColumnName(int column) {
                return COLUMNS[column];
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return switch (column) {
                    case DATE -> LocalDate.class;
                    case C -> TxnStatus.class;
                    case AMOUNT -> Long.class;
                    default -> String.class;
                };
            }

            @Override
            public Object getValueAt(int row, int column) {
                LedgerEntry e = rows.get(row);
                return switch (column) {
                    case DATE -> e.date;
                    case NUM -> e.checkNumber == null ? "" : e.checkNumber;
                    case PAYEE -> e.payee == null ? "" : e.payee;
                    case DESCRIPTION -> e.description == null ? "" : e.description;
                    case C -> e.status;
                    case AMOUNT -> Math.abs(e.netCents());
                    default -> "";
                };
            }
        }
    }
}
