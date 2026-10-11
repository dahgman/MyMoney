/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JFrame.java to edit this template
 */
package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * The main window: account tree on the left, the selected account's
 * summary at top right, and its ledger below.
 *
 * @author mike@asylum.martinsmayhem.com
 */
public class MainFrame extends javax.swing.JFrame {

    private final Database db;
    private final AppConfig config;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final TransactionRepository transactionRepo;
    private final LedgerTableModel ledgerModel;

    private UndoLog undoLog;                 // null if undo couldn't be set up
    private JMenuItem undoItem;
    private JMenuItem redoItem;
    private boolean reloadingTree;           // true while loadAccounts rebuilds the tree

    private int lastLedgerRow = -1;          // for noticing when the new-transaction row is left
    private boolean resolvingDraft;          // true while asking about an unsaved new transaction

    private final JTextField filterField = new JTextField(20);
    private final JCheckBox hideReconciledBox = new JCheckBox("Hide reconciled");
    private final JLabel filterCountLabel = new JLabel(" ");
    private final JLabel selectionTotalLabel = new JLabel(" ");

    /**
     * Creates new form MainFrame
     */
    public MainFrame(Database db, AppConfig config) {
        this.db = db;
        this.config = config;
        this.accounts = new AccountRepository(db);
        this.categories = new CategoryRepository(db);
        this.tags = new TagRepository(db);
        this.transactionRepo = new TransactionRepository(db);
        this.ledgerModel = new LedgerTableModel(transactionRepo,
                this::showError, this::confirm, this::afterLedgerChange);
        initComponents();
        setTitle("MyMoney - " + db.getFile().getFileName());
        setSize(1000, 650);
        setupLedger();
        setupMenus();
        try {
            undoLog = new UndoLog(db);
        } catch (SQLException e) {
            showError("Undo is not available for this file:\n" + e.getMessage());
        }
        updateUndoMenu();
        accountTree.addTreeSelectionListener(e -> {
            if (!reloadingTree) {
                showAccount(selectedAccount());
            }
        });
        loadAccounts(null);
        if (accountTree.getRowCount() > 0) {
            accountTree.setSelectionRow(0);    // open on the first account
        } else {
            showAccount(null);                 // no accounts yet
        }
        SwingUtilities.invokeLater(this::selectTransactionNearToday);   // once the window is showing
    }

    /**
     * Selects (and scrolls to) today's transaction, or the last one before
     * today, and puts the keyboard focus in the ledger.
     */
    private void selectTransactionNearToday() {
        int row = ledgerModel.rowNearestToday();
        if (row >= 0) {
            ledgerTable.changeSelection(row, LedgerTableModel.DATE, false, false);   // also scrolls to it
        }
        ledgerTable.requestFocusInWindow();
    }

    /** Connects the ledger table to its model and sets up columns and keys. */
    private void setupLedger() {
        ledgerTable.setModel(ledgerModel);
        ledgerTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);  // clicking away saves the cell
        ledgerTable.setSurrendersFocusOnKeystroke(true);                         // typing goes straight into the cell

        TableColumnModel cols = ledgerTable.getColumnModel();
        int[] widths = {110, 60, 160, 160, 110, 150, 130, 100, 90, 90, 100};
        for (int i = 0; i < widths.length; i++) {
            cols.getColumn(i).setPreferredWidth(widths[i]);
        }
        // With 11 columns the table squeezes them to fit; never squeeze dates or amounts.
        cols.getColumn(LedgerTableModel.DATE).setMinWidth(100);
        cols.getColumn(LedgerTableModel.TAG).setMinWidth(80);
        cols.getColumn(LedgerTableModel.PAYMENT).setMinWidth(80);
        cols.getColumn(LedgerTableModel.DEPOSIT).setMinWidth(80);
        cols.getColumn(LedgerTableModel.BALANCE).setMinWidth(90);

        // Future-dated transactions are drawn with grey text, in every column.
        ledgerTable.setDefaultRenderer(Object.class, muted(ledgerTable.getDefaultRenderer(Object.class)));

        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        TableCellRenderer money = muted(right);
        cols.getColumn(LedgerTableModel.PAYMENT).setCellRenderer(money);
        cols.getColumn(LedgerTableModel.DEPOSIT).setCellRenderer(money);
        cols.getColumn(LedgerTableModel.BALANCE).setCellRenderer(money);

        // Text cells: Enter saves and moves down. Category also suggests as you type,
        // and Date also has a pop-out calendar.
        ledgerTable.setDefaultEditor(String.class, new AutoCompleteEditor(null, this::commitAndMoveDown));
        cols.getColumn(LedgerTableModel.CATEGORY).setCellEditor(
                new AutoCompleteEditor(this::categoryPaths, this::commitAndMoveDown));
        cols.getColumn(LedgerTableModel.DATE).setCellEditor(new DateCellEditor(this::commitAndMoveDown));

        // Tag: a colored pill; typing suggests existing tags, and a new name creates a tag.
        cols.getColumn(LedgerTableModel.TAG).setCellRenderer(muted(TagCell.renderer(ledgerModel::tagColor)));
        cols.getColumn(LedgerTableModel.TAG).setCellEditor(
                new AutoCompleteEditor(this::tagNames, this::commitAndMoveDown));

        // Status: a colored dot, edited with a drop-down.
        cols.getColumn(LedgerTableModel.STATUS).setCellRenderer(muted(StatusCell.renderer()));
        cols.getColumn(LedgerTableModel.STATUS).setCellEditor(StatusCell.editor());

        setupSorting();
        setupFilter();

        // Leaving the new-transaction row without an amount: ask what to do.
        ledgerTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || resolvingDraft) {
                return;
            }
            int row = ledgerTable.getSelectedRow();
            int previous = lastLedgerRow;
            lastLedgerRow = row;
            if (row != previous && ledgerModel.isDraftRow(previous) && ledgerModel.draftHasData()) {
                SwingUtilities.invokeLater(() -> {      // let the click finish first
                    if (!resolveDraft(true)) {
                        selectDraftAmount();
                    }
                });
            }
        });

        // The Delete key removes the selected transaction (after asking).
        ledgerTable.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteTransaction");
        ledgerTable.getActionMap().put("deleteTransaction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteSelectedTransaction();
            }
        });
    }

    /**
     * The filter bar above the ledger: text typed there hides every
     * transaction that doesn't contain it in some column, and "Hide
     * reconciled" hides reconciled ones. Balances are not affected.
     * Ctrl+F jumps to the filter; Escape there clears it.
     */
    private void setupFilter() {
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            filterField.setText("");
            ledgerTable.requestFocusInWindow();
        });
        filterField.setToolTipText("Show only transactions with this text in any column");
        hideReconciledBox.setToolTipText("Hide reconciled transactions (balances still include them)");

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.LINE_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        bar.add(new JLabel("Filter: "));
        bar.add(filterField);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(clear);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(hideReconciledBox);
        bar.add(Box.createHorizontalGlue());
        bar.add(filterCountLabel);
        filterField.setMaximumSize(new java.awt.Dimension(300, filterField.getPreferredSize().height));

        // Put the bar on top of the ledger, in the ledger's place in the split pane.
        JPanel ledgerPanel = new JPanel(new java.awt.BorderLayout());
        ledgerPanel.add(bar, java.awt.BorderLayout.NORTH);
        ledgerPanel.add(ledgerScroll, java.awt.BorderLayout.CENTER);
        ledgerPanel.add(selectionTotalBar(), java.awt.BorderLayout.SOUTH);
        rightSplit.setRightComponent(ledgerPanel);

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        hideReconciledBox.addActionListener(e -> applyFilter());

        // Escape in the filter clears it and goes back to the ledger.
        filterField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearFilter");
        filterField.getActionMap().put("clearFilter", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                filterField.setText("");
                ledgerTable.requestFocusInWindow();
            }
        });
        // Enter in the filter goes to the ledger, keeping the filter.
        filterField.addActionListener(e -> ledgerTable.requestFocusInWindow());

        // Ctrl+F from anywhere in the window jumps to the filter.
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuShortcut()), "focusFilter");
        getRootPane().getActionMap().put("focusFilter", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                filterField.requestFocusInWindow();
                filterField.selectAll();
            }
        });

        // "Showing 12 of 340" whenever rows are hidden.
        ledgerModel.addTableModelListener(e -> updateFilterCount());
    }

    /** The bar under the ledger that totals the selected transactions. */
    private JPanel selectionTotalBar() {
        JPanel bar = new JPanel(new java.awt.BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        bar.add(selectionTotalLabel, java.awt.BorderLayout.EAST);
        ledgerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionTotal();
            }
        });
        ledgerModel.addTableModelListener(e -> updateSelectionTotal());   // amounts edited or rows changed
        return bar;
    }

    /** "3 selected   Payments: $120.00   Deposits: $50.00   Net: -$70.00" */
    private void updateSelectionTotal() {
        int count = 0;
        long payments = 0;
        long deposits = 0;
        for (int row : ledgerTable.getSelectedRows()) {
            if (!ledgerModel.isSavedRow(row)) {
                continue;                                // the blank row isn't a transaction yet
            }
            count++;
            long amount = ledgerModel.amountCents(row);
            if (amount < 0) {
                payments -= amount;
            } else {
                deposits += amount;
            }
        }
        selectionTotalLabel.setText(count == 0 ? "No transactions selected"
                : "<html>" + count + (count == 1 ? " transaction" : " transactions") + " selected"
                        + "&nbsp;&nbsp;&nbsp;Payments: " + Money.format(payments)
                        + "&nbsp;&nbsp;&nbsp;Deposits: " + Money.format(deposits)
                        + "&nbsp;&nbsp;&nbsp;<b>Net: " + Money.format(deposits - payments) + "</b></html>");
    }

    /** Passes the filter bar's settings to the ledger. */
    private void applyFilter() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();   // save a half-finished cell first
        }
        ledgerModel.setFilter(filterField.getText(), hideReconciledBox.isSelected());
    }

    private void updateFilterCount() {
        filterCountLabel.setText(ledgerModel.isFiltered()
                ? "Showing " + ledgerModel.shownCount() + " of " + ledgerModel.transactionCount()
                        + " transactions"
                : " ");
    }

    /** Text color for transactions dated after today. */
    private static final Color FUTURE_TEXT = new Color(128, 128, 128);         // grey

    /**
     * Wraps a cell renderer so future-dated rows get grey text (on the normal
     * background). Selected rows keep the normal selection colors.
     */
    private TableCellRenderer muted(TableCellRenderer base) {
        return (table, value, isSelected, hasFocus, row, column) -> {
            java.awt.Component c = base.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                c.setBackground(table.getBackground());
                c.setForeground(ledgerModel.isFuture(row) ? FUTURE_TEXT : table.getForeground());
            }
            return c;
        };
    }

    /**
     * Header clicks sort the ledger: click to sort by one column (click again
     * to reverse), Ctrl+click to add another sort level (again to reverse it).
     */
    private void setupSorting() {
        JTableHeader header = ledgerTable.getTableHeader();

        // Show the sort arrows (and level numbers) after the column names.
        TableCellRenderer plain = header.getDefaultRenderer();
        header.setDefaultRenderer((table, value, isSelected, hasFocus, row, column) -> {
            int modelColumn = table.convertColumnIndexToModel(column);
            String text = value + ledgerModel.sortIndicator(modelColumn);
            return plain.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
        });

        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int column = header.columnAtPoint(e.getPoint());
                if (column < 0 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (ledgerTable.isEditing()) {
                    ledgerTable.getCellEditor().stopCellEditing();
                }
                ledgerModel.sortBy(ledgerTable.convertColumnIndexToModel(column), e.isControlDown());
                header.repaint();
            }
        });
    }

    /**
     * Saves the cell being edited, then moves down one row. After a new
     * transaction is recorded, moves to the Date of the new blank row instead.
     */
    private void commitAndMoveDown() {
        if (!ledgerTable.isEditing()) {
            return;
        }
        int row = ledgerTable.getEditingRow();
        int column = ledgerTable.getEditingColumn();
        int rowsBefore = ledgerTable.getRowCount();
        if (!ledgerTable.getCellEditor().stopCellEditing()) {
            return;
        }
        if (ledgerTable.getRowCount() == rowsBefore && ledgerModel.isDraftRow(row)
                && ledgerModel.draftHasData() && !resolveDraft(true)) {
            selectDraftAmount();                        // Enter on a new transaction with no amount
            return;
        }
        int rows = ledgerTable.getRowCount();
        if (rows > rowsBefore) {
            ledgerTable.changeSelection(rows - 1, LedgerTableModel.DATE, false, false);
        } else if (row + 1 < rows) {
            ledgerTable.changeSelection(row + 1, column, false, false);
        }
    }

    /**
     * The new-transaction row has typing in it but no amount, so it isn't
     * saved. Asks whether to save it at $0.00, discard it, or (if allowed)
     * go back to it. Returns false only for "Go Back".
     */
    private boolean resolveDraft(boolean allowGoBack) {
        if (!ledgerModel.draftHasData() || resolvingDraft) {
            return true;
        }
        resolvingDraft = true;
        try {
            Object[] options = allowGoBack
                    ? new Object[] {"Save with $0.00", "Discard", "Go Back"}
                    : new Object[] {"Save with $0.00", "Discard"};
            int choice = JOptionPane.showOptionDialog(this,
                    "The new transaction has no Payment or Deposit amount,\n"
                    + "so it hasn't been saved.",
                    "Unsaved Transaction", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[options.length - 1]);
            if (choice == 0 || (choice < 0 && !allowGoBack)) {
                ledgerModel.recordDraft();              // closing the box without Go Back keeps it
                return true;
            }
            if (choice == 1) {
                ledgerModel.discardDraft();
                return true;
            }
            return false;
        } finally {
            resolvingDraft = false;
        }
    }

    /** Puts the cursor on the new-transaction row's Payment cell. */
    private void selectDraftAmount() {
        int draftRow = ledgerModel.getRowCount() - 1;
        if (draftRow >= 0) {
            resolvingDraft = true;
            try {
                ledgerTable.changeSelection(draftRow, LedgerTableModel.PAYMENT, false, false);
                lastLedgerRow = draftRow;
            } finally {
                resolvingDraft = false;
            }
            ledgerTable.requestFocusInWindow();
        }
    }

    /** Category paths, then "[Account]" transfers, for the drop-down. */
    private List<String> categoryPaths() {
        List<String> list = new ArrayList<>();
        try {
            list.addAll(categories.findAllPaths());
            for (Account a : accounts.findAll()) {
                list.add("[" + a.name() + "]");
            }
        } catch (SQLException e) {
            // show whatever was read
        }
        return list;
    }

    /** Tag names for the Tag column's drop-down. */
    private List<String> tagNames() {
        try {
            return tags.findAllNames();
        } catch (SQLException e) {
            return List.of();
        }
    }

    // ----- File menu additions --------------------------------------------

    /** Adds Edit Account and Import QIF to the File menu, and a right-click menu to the tree. */
    private void setupMenus() {
        JMenuItem editAccountItem = new JMenuItem("Edit Account...");
        editAccountItem.addActionListener(e -> editAccount());
        JMenuItem importQifItem = new JMenuItem("Import QIF...");
        importQifItem.addActionListener(e -> importQif());
        JMenuItem manageTagsItem = new JMenuItem("Manage Tags...");
        manageTagsItem.addActionListener(e -> manageTags());
        fileMenu.insert(editAccountItem, 1);           // after New Account...
        fileMenu.insert(menuItem("Delete Account...", this::deleteAccount), 2);
        fileMenu.insert(manageTagsItem, 3);
        fileMenu.insertSeparator(4);
        fileMenu.insert(importQifItem, 5);

        // Edit menu: changes to the selected transactions.
        javax.swing.JMenu editMenu = new javax.swing.JMenu("Edit");
        JPopupMenu ledgerMenu = new JPopupMenu();
        for (javax.swing.JComponent menu : new javax.swing.JComponent[] {editMenu, ledgerMenu}) {
            menu.add(menuItem("Change Date...", () -> bulkChange(LedgerTableModel.DATE)));
            menu.add(menuItem("Change Payee...", () -> bulkChange(LedgerTableModel.PAYEE)));
            menu.add(menuItem("Change Category...", () -> bulkChange(LedgerTableModel.CATEGORY)));
            menu.add(menuItem("Change Tag...", () -> bulkChange(LedgerTableModel.TAG)));
            menu.add(menuItem("Change Status...", () -> bulkChange(LedgerTableModel.STATUS)));
            menu.add(menuItem("Move Memo to Description", this::moveMemoToDescription));
            menu.add(new javax.swing.JSeparator());
            menu.add(menuItem("Delete Transactions...", this::deleteSelectedTransaction));
        }
        undoItem = menuItem("Undo", this::undo);
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuShortcut()));
        redoItem = menuItem("Redo", this::redo);
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuShortcut()));
        // Also on the ledger itself, so Ctrl+Z isn't taken as typing into a cell.
        for (Object[] b : new Object[][] {{undoItem, "undo", (Runnable) this::undo},
                                          {redoItem, "redo", (Runnable) this::redo}}) {
            KeyStroke ks = ((JMenuItem) b[0]).getAccelerator();
            ledgerTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ks, b[1]);
            ledgerTable.getActionMap().put(b[1], new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ((Runnable) b[2]).run();
                }
            });
        }
        editMenu.insert(undoItem, 0);
        editMenu.insert(redoItem, 1);
        editMenu.insertSeparator(2);
        menuBar.add(editMenu, 1);                       // after File

        // Right-click in the ledger: the same menu. A row outside the selection gets selected first.
        ledgerTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showMenu(e);
            }

            private void showMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = ledgerTable.rowAtPoint(e.getPoint());
                if (row >= 0 && !ledgerTable.isRowSelected(row)) {
                    ledgerTable.changeSelection(row, ledgerTable.columnAtPoint(e.getPoint()), false, false);
                }
                ledgerMenu.show(ledgerTable, e.getX(), e.getY());
            }
        });

        JPopupMenu treeMenu = new JPopupMenu();
        JMenuItem editFromTree = new JMenuItem("Edit Account...");
        editFromTree.addActionListener(e -> editAccount());
        treeMenu.add(editFromTree);
        treeMenu.add(menuItem("Delete Account...", this::deleteAccount));
        accountTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showMenu(e);                            // Windows opens menus on release
            }

            private void showMenu(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                TreePath path = accountTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    accountTree.setSelectionPath(path);
                    treeMenu.show(accountTree, e.getX(), e.getY());
                }
            }
        });
    }

    /** Ctrl on Windows and Linux (Cmd on a Mac). */
    private static int menuShortcut() {
        return java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    // ----- Undo / Redo -----------------------------------------------------

    /** After each saved ledger change: new balances, and one undo step. */
    private void afterLedgerChange() {
        updateSummary(selectedAccount());
        checkpoint(ledgerModel.lastAction());
    }

    /** Groups everything saved since the last checkpoint into one undo step. */
    private void checkpoint(String label) {
        if (undoLog == null) {
            return;
        }
        try {
            undoLog.checkpoint(label);
        } catch (SQLException e) {
            // undo history is a convenience; never block the change itself
        }
        updateUndoMenu();
    }

    /** Edit > Undo (Ctrl+Z). While typing in a cell, it just cancels the typing. */
    private void undo() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().cancelCellEditing();
            return;
        }
        if (undoLog == null || undoLog.undoLabel() == null) {
            return;
        }
        checkpoint("Change");
        try {
            undoLog.undo();
        } catch (SQLException e) {
            showError("Could not undo:\n" + e.getMessage());
        }
        refreshAfterUndo();
    }

    /** Edit > Redo (Ctrl+Y). */
    private void redo() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        if (undoLog == null || undoLog.redoLabel() == null) {
            return;
        }
        try {
            undoLog.redo();
        } catch (SQLException e) {
            showError("Could not redo:\n" + e.getMessage());
        }
        refreshAfterUndo();
    }

    /** Redraws accounts, ledger and balances after Undo or Redo, keeping the selection and sort. */
    private void refreshAfterUndo() {
        Account selected = selectedAccount();
        loadAccounts(selected == null ? null : selected.id());
        if (selectedAccount() == null && accountTree.getRowCount() > 0) {
            accountTree.setSelectionRow(0);            // the selected account was undone away
        }
        updateUndoMenu();
    }

    /** Shows what Undo and Redo would do, like "Undo Edit Payee". */
    private void updateUndoMenu() {
        if (undoItem == null) {
            return;
        }
        String u = undoLog == null ? null : undoLog.undoLabel();
        String r = undoLog == null ? null : undoLog.redoLabel();
        undoItem.setText(u == null ? "Undo" : "Undo " + u);
        undoItem.setEnabled(u != null);
        redoItem.setText(r == null ? "Redo" : "Redo " + r);
        redoItem.setEnabled(r != null);
    }

    private static JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * Deletes the selected account and its transactions, after a confirmation
     * that says what will happen. An account with envelopes can't be deleted.
     */
    private void deleteAccount() {
        Account a = selectedAccount();
        if (a == null) {
            showError("Select an account to delete first.");
            return;
        }
        AccountRepository.DeleteInfo info;
        try {
            info = accounts.deleteInfo(a.id());
        } catch (SQLException e) {
            showError("Could not check the account:\n" + e.getMessage());
            return;
        }
        if (!info.envelopes().isEmpty()) {
            showError("\"" + a.name() + "\" has envelopes (" + String.join(", ", info.envelopes())
                    + ").\nDelete them, or move them under another account with Edit Account, first.");
            return;
        }

        StringBuilder message = new StringBuilder("Delete the account \"" + a.name() + "\"?");
        if (info.transactions() > 0) {
            message.append("\n\nIts ").append(info.transactions())
                   .append(info.transactions() == 1 ? " transaction" : " transactions");
            if (info.reconciled() > 0) {
                message.append(" (").append(info.reconciled()).append(" reconciled)");
            }
            message.append(info.transactions() == 1 ? " will be deleted." : " will be deleted.");
        }
        if (info.transfers() > 0) {
            message.append("\n").append(info.transfers())
                   .append(info.transfers() == 1 ? " transfer" : " transfers")
                   .append(" with other accounts will stay in those accounts, with no category.");
        }
        message.append("\n\nYou can undo this with Edit > Undo.");
        Object[] options = {"Delete Account", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, message.toString(), "Delete Account",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) {
            return;
        }

        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().cancelCellEditing();
        }
        Account shown = ledgerModel.currentAccount();
        if (shown != null && shown.id() == a.id()) {
            ledgerModel.discardDraft();                // its blank-row typing goes with it
        }
        try {
            accounts.delete(a.id());
        } catch (SQLException e) {
            showError("Could not delete the account, so nothing was changed:\n" + e.getMessage());
            return;
        }
        checkpoint("Delete Account");
        loadAccounts(null);
        if (accountTree.getRowCount() > 0) {
            accountTree.setSelectionRow(0);
        }
    }

    /** Opens Manage Tags; redraws the ledger if anything changed. */
    private void manageTags() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        TagDialog dlg = new TagDialog(this, tags);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        if (dlg.wasChanged()) {
            checkpoint("Tag Changes");
            ledgerModel.reload();
        }
    }

    /** Edits the selected account (name, type, opening balance, parent). */
    private void editAccount() {
        Account a = selectedAccount();
        if (a == null) {
            showError("Select an account to edit first.");
            return;
        }
        AccountDialog dlg = new AccountDialog(this, true, accounts, a);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        Long id = dlg.getSavedAccountId();
        if (id != null) {
            checkpoint("Edit Account");
            loadAccounts(id);
        }
    }

    /** Imports a QIF file, creating any accounts it names that don't exist yet. */
    private void importQif() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Import QIF File");
        fc.setFileFilter(new FileNameExtensionFilter("QIF files (*.qif)", "qif"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = fc.getSelectedFile().toPath();

        QifParser.QifFile qif;
        try {
            qif = QifParser.parse(file);
        } catch (IOException e) {
            showError("Could not read the file:\n" + e.getMessage());
            return;
        }

        String unnamedAccount = null;
        if (qif.hasUnnamedTransactions()) {
            unnamedAccount = askImportAccount(file);
            if (unnamedAccount == null) {
                return;                                 // cancelled
            }
        }

        QifImporter.Summary summary;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            summary = new QifImporter(db).run(qif, unnamedAccount);
        } catch (SQLException | RuntimeException e) {
            showError("The import failed, so nothing was imported:\n" + e.getMessage());
            return;
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }

        checkpoint("Import QIF");
        Account selected = selectedAccount();
        loadAccounts(selected == null ? null : selected.id());
        if (selectedAccount() == null && accountTree.getRowCount() > 0) {
            accountTree.setSelectionRow(0);
        }
        JOptionPane.showMessageDialog(this, summary.describe(), "Import QIF",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * The QIF file doesn't say which account its transactions belong to:
     * asks the user to pick one or type a new name. Null if cancelled.
     */
    private String askImportAccount(Path file) {
        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        try {
            for (Account a : accounts.findAll()) {
                combo.addItem(a.name());
            }
        } catch (SQLException ignored) {
        }
        String suggested = file.getFileName().toString().replaceFirst("(?i)\\.qif$", "");
        combo.setSelectedItem(suggested);
        while (true) {
            int r = JOptionPane.showConfirmDialog(this, new Object[] {
                    "This file doesn't say which account its transactions are for.\n"
                    + "Choose an account, or type a name to create a new one:", combo},
                    "Import QIF", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            Object value = combo.getEditor().getItem();
            String name = value == null ? "" : value.toString().trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
    }

    /** Deletes the selected transactions (after asking). Reconciled ones are left alone. */
    private void deleteSelectedTransaction() {
        int[] rows = selectedTransactionRows();
        if (rows.length == 0) {
            return;
        }
        int reconciled = 0;
        int transfers = 0;
        String transferAccount = null;
        for (int row : rows) {
            if (ledgerModel.isLocked(row)) {
                reconciled++;
            } else if (ledgerModel.transferAccount(row) != null) {
                transfers++;
                transferAccount = ledgerModel.transferAccount(row);
            }
        }
        int deletable = rows.length - reconciled;
        if (deletable == 0) {
            showError(rows.length == 1 ? "Reconciled transactions can't be deleted.\nChange the status first."
                    : "All of the selected transactions are reconciled, so none can be deleted.");
            return;
        }

        StringBuilder message = new StringBuilder();
        if (deletable == 1) {
            message.append(transfers == 1 ? "Delete this transfer?" : "Delete this transaction?");
        } else {
            message.append("Delete these ").append(deletable).append(" transactions?");
        }
        if (transfers == 1) {
            message.append("\nThe matching transaction in \"").append(transferAccount).append("\" will be deleted too.");
        } else if (transfers > 1) {
            message.append("\n").append(transfers).append(" of them are transfers; their matching")
                   .append(" transactions in the other accounts will be deleted too.");
        }
        if (reconciled > 0) {
            message.append("\n\n").append(reconciled).append(" reconciled ")
                   .append(reconciled == 1 ? "transaction is" : "transactions are")
                   .append(" selected too and will be left alone.");
        }
        int r = JOptionPane.showConfirmDialog(this, message.toString(),
                deletable == 1 ? "Delete Transaction" : "Delete Transactions",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            ledgerModel.deleteRows(rows);
        }
    }

    // ----- Bulk changes ----------------------------------------------------

    /** The selected rows that are saved transactions (not the blank row). */
    private int[] selectedTransactionRows() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        return java.util.Arrays.stream(ledgerTable.getSelectedRows())
                .filter(ledgerModel::isSavedRow).toArray();
    }

    /** Edit menu / right-click: set Date, Payee, Category, Tag or Status on every selected transaction. */
    private void bulkChange(int column) {
        int[] rows = selectedTransactionRows();
        if (rows.length == 0) {
            showError("Select one or more transactions first.\n"
                    + "(Ctrl+click or Shift+click to select several.)");
            return;
        }
        String what = rows.length == 1 ? "1 transaction" : rows.length + " transactions";
        Object value = switch (column) {
            case LedgerTableModel.DATE -> askDate(what);
            case LedgerTableModel.PAYEE -> askChoice("Change Payee", "New payee for " + what + ":",
                    payeeNames(), "Leave it blank to remove the payee.");
            case LedgerTableModel.CATEGORY -> askChoice("Change Category", "New category for " + what + ":",
                    categoryPaths(), "Use Parent:Child for a child category, [Account] for a transfer.\n"
                    + "Leave it blank to remove the category.");
            case LedgerTableModel.TAG -> askChoice("Change Tag", "New tag for " + what + ":",
                    tagNames(), "A new name creates a new tag. Leave it blank to remove the tag.");
            case LedgerTableModel.STATUS -> askStatus(what);
            default -> null;
        };
        if (value == null) {
            return;                                     // cancelled
        }
        String note = ledgerModel.bulkSet(rows, column, value);
        if (note != null) {
            JOptionPane.showMessageDialog(this, note, "MyMoney", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** Edit menu / right-click: moves the selected transactions' memos into their descriptions. */
    private void moveMemoToDescription() {
        int[] rows = selectedTransactionRows();
        if (rows.length == 0) {
            showError("Select one or more transactions first.\n"
                    + "(Ctrl+click or Shift+click to select several.)");
            return;
        }
        if (ledgerModel.countWithMemo(rows) == 0) {
            JOptionPane.showMessageDialog(this, rows.length == 1 ? "This transaction has no memo."
                    : "None of the selected transactions has a memo.", "MyMoney", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean append = false;
        int both = ledgerModel.countWithMemoAndDescription(rows);
        if (both > 0) {
            Object[] options = {"Replace the Description", "Add the Memo After It", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this,
                    (both == 1 ? "1 of these transactions already has a description."
                            : both + " of these transactions already have a description.")
                    + "\nWhat should happen to it?",
                    "Move Memo to Description", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[1]);
            if (choice != 0 && choice != 1) {
                return;
            }
            append = choice == 1;
        }
        ledgerModel.moveMemoToDescription(rows, append);
    }

    /** Asks for a status from the same drop-down as the Status column. Null if cancelled. */
    private TxnStatus askStatus(String what) {
        JComboBox<TxnStatus> combo = StatusCell.comboBox();
        combo.setSelectedItem(TxnStatus.CLEARED);
        int r = JOptionPane.showConfirmDialog(this, new Object[] {"New status for " + what + ":", combo},
                "Change Status", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return r == JOptionPane.OK_OPTION ? (TxnStatus) combo.getSelectedItem() : null;
    }

    /** Asks for a date by typing or with the calendar. Null if cancelled. */
    private String askDate(String what) {
        javax.swing.JTextField field = new javax.swing.JTextField(
                java.time.LocalDate.now().format(LedgerTableModel.DISPLAY_DATE), 12);
        CalendarPanel calendar = new CalendarPanel(d -> field.setText(d.format(LedgerTableModel.DISPLAY_DATE)));
        calendar.setSelected(java.time.LocalDate.now());
        while (true) {
            int r = JOptionPane.showConfirmDialog(this,
                    new Object[] {"New date for " + what + ":", field, calendar},
                    "Change Date", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            try {
                LedgerTableModel.parseDate(field.getText().trim());
                return field.getText().trim();
            } catch (IllegalArgumentException e) {
                showError(e.getMessage());
            }
        }
    }

    /** Asks for a value, offering the existing ones in an editable list. Null if cancelled. */
    private String askChoice(String title, String prompt, List<String> choices, String hint) {
        JComboBox<String> combo = new JComboBox<>(choices.toArray(new String[0]));
        combo.setEditable(true);
        combo.setSelectedItem("");
        int r = JOptionPane.showConfirmDialog(this, new Object[] {prompt, combo, hint},
                title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return null;
        }
        Object value = combo.getEditor().getItem();
        return value == null ? "" : value.toString().trim();
    }

    private List<String> payeeNames() {
        try {
            return transactionRepo.findPayeeNames();
        } catch (SQLException e) {
            return List.of();
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "MyMoney", JOptionPane.ERROR_MESSAGE);
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "MyMoney",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** Rebuilds the account tree, optionally selecting one account afterwards. */
    private void loadAccounts(Long selectId) {
        List<Account> all;
        try {
            all = accounts.findAll();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not load accounts:\n" + e.getMessage(),
                    "MyMoney", JOptionPane.ERROR_MESSAGE);
            return;
        }

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Accounts");
        Map<Long, DefaultMutableTreeNode> nodes = new HashMap<>();

        // Top-level accounts first, so their envelopes have somewhere to go.
        for (Account a : all) {
            if (a.parentId() == null) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(a);
                nodes.put(a.id(), node);
                root.add(node);
            }
        }
        for (Account a : all) {
            if (a.parentId() != null) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(a);
                nodes.put(a.id(), node);
                DefaultMutableTreeNode parent = nodes.get(a.parentId());
                (parent != null ? parent : root).add(node);
            }
        }

        reloadingTree = true;                      // don't react to the selection coming and going
        try {
            accountTree.setModel(new DefaultTreeModel(root));
            for (int i = 0; i < accountTree.getRowCount(); i++) {
                accountTree.expandRow(i);          // show every envelope
            }
            fitTreeWidth();

            if (selectId != null && nodes.containsKey(selectId)) {
                TreePath path = new TreePath(nodes.get(selectId).getPath());
                accountTree.setSelectionPath(path);
                accountTree.scrollPathToVisible(path);
            }
        } finally {
            reloadingTree = false;
        }
        showAccount(selectedAccount());
    }

    /** Narrowest and widest the account tree pane is sized to automatically. */
    private static final int TREE_MIN_WIDTH = 150;
    private static final int TREE_MAX_WIDTH = 400;

    /**
     * Moves the divider so the account tree pane is just wide enough for the
     * longest account name (within the limits above). The divider can still
     * be dragged; this runs again whenever the account list is reloaded.
     */
    private void fitTreeWidth() {
        java.awt.Insets in = accountScroll.getInsets();
        int width = accountTree.getPreferredSize().width
                + in.left + in.right
                + accountScroll.getVerticalScrollBar().getPreferredSize().width   // room if it scrolls
                + 10;                                                              // a little breathing room
        mainSplit.setDividerLocation(Math.max(TREE_MIN_WIDTH, Math.min(TREE_MAX_WIDTH, width)));
    }

    /** The account selected in the tree, or null if none. */
    private Account selectedAccount() {
        Object node = accountTree.getLastSelectedPathComponent();
        if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Account a) {
            return a;
        }
        return null;
    }

    /** Shows an account's summary and ledger (or clears both for null). */
    private void showAccount(Account a) {
        Account current = ledgerModel.currentAccount();
        if (a != null && current != null && a.id() == current.id()) {
            ledgerModel.updateAccount(a);                // same account: refresh, keep sort and blank row
            updateSummary(a);
            return;
        }
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();   // save a half-finished cell first
        }
        resolveDraft(false);                             // a new transaction left without an amount
        ledgerModel.setAccount(a);                       // also resets the sort to Status, Date
        ledgerTable.getTableHeader().repaint();          // update the sort arrows
        updateSummary(a);
    }

    /**
     * Fills the summary pane: account name and type, the cleared balance
     * (reconciled + cleared), and the total balance (every transaction).
     */
    private void updateSummary(Account a) {
        if (a == null) {
            accountNameLabel.setText("No account selected");
            balanceLabel.setText(" ");
            return;
        }
        accountNameLabel.setText(a.name() + "  (" + a.type() + ")");
        try {
            AccountRepository.Balances b = accounts.balances(a);
            balanceLabel.setText("<html><div align='right'><b><font size='+1'>Cleared Balance: "
                    + Money.format(b.clearedCents()) + "</font></b><br>"
                    + "Total Balance (all transactions): " + Money.format(b.totalCents())
                    + "</div></html>");
        } catch (SQLException e) {
            balanceLabel.setText("Balance: unavailable");
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        mainSplit = new javax.swing.JSplitPane();
        accountScroll = new javax.swing.JScrollPane();
        accountTree = new javax.swing.JTree();
        rightSplit = new javax.swing.JSplitPane();
        summaryPanel = new javax.swing.JPanel();
        accountNameLabel = new javax.swing.JLabel();
        balanceLabel = new javax.swing.JLabel();
        ledgerScroll = new javax.swing.JScrollPane();
        ledgerTable = new javax.swing.JTable();
        menuBar = new javax.swing.JMenuBar();
        fileMenu = new javax.swing.JMenu();
        newAccountItem = new javax.swing.JMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        exitItem = new javax.swing.JMenuItem();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                formWindowClosing(evt);
            }
        });

        mainSplit.setDividerLocation(250);

        accountTree.setModel(new javax.swing.tree.DefaultTreeModel(new javax.swing.tree.DefaultMutableTreeNode("Accounts")));
        accountTree.setRootVisible(false);
        accountTree.setShowsRootHandles(true);
        accountScroll.setViewportView(accountTree);

        mainSplit.setLeftComponent(accountScroll);

        rightSplit.setDividerLocation(80);
        rightSplit.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);

        summaryPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
        summaryPanel.setLayout(new java.awt.BorderLayout());

        accountNameLabel.setFont(new java.awt.Font("Liberation Sans", 1, 16)); // NOI18N
        accountNameLabel.setText("Account");
        summaryPanel.add(accountNameLabel, java.awt.BorderLayout.WEST);

        balanceLabel.setText("Balance");
        summaryPanel.add(balanceLabel, java.awt.BorderLayout.EAST);

        rightSplit.setTopComponent(summaryPanel);

        ledgerTable.setModel(new javax.swing.table.DefaultTableModel(new Object[][] {}, new String[] {"Date", "Num", "Payee", "Description", "Memo", "Status", "Payment", "Deposit", "Balance"}));
        ledgerScroll.setViewportView(ledgerTable);

        rightSplit.setRightComponent(ledgerScroll);

        mainSplit.setRightComponent(rightSplit);

        getContentPane().add(mainSplit, java.awt.BorderLayout.CENTER);

        fileMenu.setText("File");

        newAccountItem.setText("New Account...");
        newAccountItem.addActionListener(this::newAccountItemActionPerformed);
        fileMenu.add(newAccountItem);
        fileMenu.add(jSeparator1);

        exitItem.setText("Exit");
        exitItem.addActionListener(this::exitItemActionPerformed);
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        setJMenuBar(menuBar);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void formWindowClosing(java.awt.event.WindowEvent evt) {//GEN-FIRST:event_formWindowClosing
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        db.close();
        config.close();
    }//GEN-LAST:event_formWindowClosing

    private void newAccountItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_newAccountItemActionPerformed
        AccountDialog dlg = new AccountDialog(this, true, accounts);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);                      // modal: waits here until closed
        Long id = dlg.getSavedAccountId();
        if (id != null) {
            checkpoint("New Account");
            loadAccounts(id);                      // rebuild the tree and select the new account
        }
    }//GEN-LAST:event_newAccountItemActionPerformed

    private void exitItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exitItemActionPerformed
        // Same path as clicking the window's X, so formWindowClosing still closes everything.
        dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
    }//GEN-LAST:event_exitItemActionPerformed


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel accountNameLabel;
    private javax.swing.JScrollPane accountScroll;
    private javax.swing.JTree accountTree;
    private javax.swing.JLabel balanceLabel;
    private javax.swing.JMenuItem exitItem;
    private javax.swing.JMenu fileMenu;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JScrollPane ledgerScroll;
    private javax.swing.JTable ledgerTable;
    private javax.swing.JSplitPane mainSplit;
    private javax.swing.JMenuBar menuBar;
    private javax.swing.JMenuItem newAccountItem;
    private javax.swing.JSplitPane rightSplit;
    private javax.swing.JPanel summaryPanel;
    // End of variables declaration//GEN-END:variables
}
