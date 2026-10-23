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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
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
    private final AttachmentRepository attachments;
    private final Paychecks paychecks;
    /** The ledger's Show: all transactions, or what's due before a paycheck (see Paychecks). */
    private final JComboBox<Object> payView = new JComboBox<>(new Object[] {"All transactions",
        Paychecks.Mode.UNTIL_NEXT, Paychecks.Mode.NEXT_PERIOD});
    private final JLabel payViewLabel = new JLabel("Show: ");
    private PaydayWidget paydayWidget;
    private final ReconcileRepository reconciles;
    private final ScheduleRepository schedules;

    private UndoLog undoLog;                 // null if undo couldn't be set up
    private JMenuItem undoItem;
    private JMenuItem redoItem;
    private boolean reloadingTree;           // true while loadAccounts rebuilds the tree
    private boolean showTreeBalances;        // preference: cleared balances in the account tree
    private boolean showGroupTotals;         // preference: totals on the tree's type headings (with balances)
    private boolean enterNextField;          // preference: Enter moves to the next field (else down a row)

    /**
     * The transaction being edited, kept white until it's committed: from the
     * first field edited until another transaction is selected (or a new one
     * is saved). Its id, 0 for the blank new-transaction row, NO_EDIT if none.
     */
    private long editingTxn = NO_EDIT;
    private static final long NO_EDIT = Long.MIN_VALUE;
    private Map<Long, Long> treeBalances = new HashMap<>();   // account id -> cleared (envelopes: current) balance

    private int lastLedgerRow = -1;          // for noticing when the new-transaction row is left
    private boolean resolvingDraft;          // true while asking about an unsaved new transaction

    private final JTextField filterField = new JTextField(20);
    private final JCheckBox hideReconciledBox = new JCheckBox("Hide reconciled");
    private final JLabel filterCountLabel = new JLabel(" ");
    private final JLabel selectionTotalLabel = new JLabel(" ");
    private final JLabel websiteLabel = new JLabel();     // the account's website, under its name
    private final JLabel loanNumberLabel = new JLabel();  // a loan's number, under its name (smaller)
    private final BalanceChart balanceChart = new BalanceChart();   // the past 12 months, in the top panel
    private String websiteUrl;

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
        this.attachments = new AttachmentRepository(db);
        this.paychecks = new Paychecks(db);
        this.reconciles = new ReconcileRepository(db);
        this.schedules = new ScheduleRepository(db);
        this.ledgerModel = new LedgerTableModel(transactionRepo,
                this::showError, this::confirm, this::afterLedgerChange);
        initComponents();
        setIconImages(AppIcon.images());
        Splash.step("Setting up the ledger…", 45);
        ledgerModel.setPayeeDebtCategory(p -> {         // a payee that pays a debt: transfer to it
            try {
                return transactionRepo.debtCategoryForPayee(p);
            } catch (SQLException e) {
                return null;
            }
        });
        setupWebsiteLink();
        setTitle("MyMoney - " + db.getFile());           // the full path of the open file
        setSize(1000, 650);
        LedgerTableModel.setDateFormat(config.getString(AppConfig.DATE_FORMAT, LedgerTableModel.DATE_FORMATS[0]));
        setupLedger();
        setupSummary();
        setupMenus();
        Splash.step("Working out loan interest…", 60);
        try {
            transactionRepo.recalculateAllLoans();      // loan interest, before there's anything to undo
        } catch (SQLException e) {
            showError("Could not work out the interest in your loans' payments:\n" + e.getMessage());
        }
        try {
            attachments.fileStatements();                // statements filed by an older version: into their place
            attachments.fileTransactionAttachments();    // transaction files named "<id>-<payee>.<ext>"
        } catch (SQLException e) {
            showError("Could not file the attachments in their folders:\n" + e.getMessage());
        }
        Splash.step("Preparing undo…", 70);
        try {
            undoLog = new UndoLog(db);
            attachments.setUndoLog(undoLog);             // attaching files isn't undone (they're on disk)
        } catch (SQLException e) {
            showError("Undo is not available for this file:\n" + e.getMessage());
        }
        updateUndoMenu();
        Splash.step("Creating scheduled transactions…", 78);
        createScheduledTransactions(false);              // bring scheduled transactions up to date
        showTreeBalances = config.getBoolean(AppConfig.SHOW_TREE_BALANCES, false);
        chosenTreeWidth = config.getInt(AppConfig.TREE_WIDTH, 0);
        showGroupTotals = config.getBoolean(AppConfig.SHOW_TREE_GROUP_TOTALS, true);
        enterNextField = config.getBoolean(AppConfig.ENTER_NEXT_FIELD, false);
        Money.setAutoDecimal(config.getBoolean(AppConfig.AUTO_DECIMAL, false));
        setupTreeRenderer();
        accountTree.addTreeSelectionListener(e -> {
            if (!reloadingTree) {
                if (selectedAccount() != null) {
                    showLedgerView();                    // an account picked: its ledger, not the summary
                }
                showAccount(selectedAccount());
            }
        });
        Splash.step("Loading accounts…", 88);
        loadAccounts(null);
        if (config.getBoolean(AppConfig.TREE_HIDDEN, false)) {
            setTreeShown(false);                         // hidden when last closed
        }
        if (config.getBoolean(AppConfig.OPEN_ON_SUMMARY, false)) {
            showSummary();                     // the preference: open on Summary
        } else if (accountTree.getRowCount() > 0) {
            selectFirstAccount();              // open on the first account
        } else {
            showAccount(null);                 // no accounts yet
        }
        SwingUtilities.invokeLater(this::selectTransactionNearToday);   // once the window is showing
        countUse();                                      // for the backup reminder
        refreshFonts();
        refreshPaycheckControls();
        addComponentListener(new java.awt.event.ComponentAdapter() {      // its size and place, unmaximized
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                rememberNormalBounds();
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                rememberNormalBounds();
            }
        });
        setupSync();
        Splash.step("Ready", 100);
    }

    /**
     * Selects (and scrolls to) today's transaction, or the last one before
     * today, and puts the keyboard focus in the ledger.
     */
    private void selectTransactionNearToday() {
        int row = ledgerModel.rowNearestToday();
        if (row >= 0) {
            ledgerTable.changeSelection(row, ledgerTable.convertColumnIndexToView(LedgerTableModel.DATE),
                    false, false);                                                    // also scrolls to it
        }
        ledgerTable.requestFocusInWindow();
    }

    /** Connects the ledger table to its model and sets up columns and keys. */
    private void setupLedger() {
        ledgerTable.setModel(ledgerModel);
        ledgerTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);  // clicking away saves the cell
        ledgerTable.setSurrendersFocusOnKeystroke(true);                         // typing goes straight into the cell

        TableColumnModel cols = ledgerTable.getColumnModel();
        for (int i = 0; i < LEDGER_WIDTHS.length; i++) {
            cols.getColumn(i).setPreferredWidth(scaled(LEDGER_WIDTHS[i]));
        }
        // With 11 columns the table squeezes them to fit; never squeeze dates or amounts.
        LEDGER_MIN_WIDTHS.forEach((column, w) -> cols.getColumn(column).setMinWidth(scaled(w)));

        // The whole row being edited is drawn white (see muted): redraw it as editing starts and stops.
        ledgerTable.addPropertyChangeListener("tableCellEditor", e -> {
            if (ledgerTable.getEditorComponent() != null) {
                keepTabInLedger(ledgerTable.getEditorComponent());
            }
            // (The table names the editor before the row, so the row is read just after.)
            SwingUtilities.invokeLater(() -> {
                if (ledgerTable.isEditing() && ledgerTable.getEditingRow() >= 0) {
                    editingTxn = ledgerModel.transactionId(ledgerTable.getEditingRow());   // being edited
                }
                ledgerTable.repaint();
            });
        });

        // Tab and Shift+Tab move between the fields of the transaction being edited, without
        // saving it; Enter saves it all (commitAndMoveDown). Escape puts it back as it was.
        ledgerTable.getActionMap().put("selectNextColumnCell", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                moveField(1);
            }
        });
        ledgerTable.getActionMap().put("selectPreviousColumnCell", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                moveField(-1);
            }
        });
        javax.swing.Action cancelCell = ledgerTable.getActionMap().get("cancel");
        ledgerTable.getActionMap().put("cancel", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (ledgerTable.isEditing()) {
                    cancelCell.actionPerformed(e);       // first the field
                } else {
                    revertEdit();                       // then the whole transaction
                }
            }
        });

        // Future-dated transactions are drawn with grey text, in every column.
        ledgerTable.setDefaultRenderer(Object.class, muted(ledgerTable.getDefaultRenderer(Object.class)));

        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        TableCellRenderer money = muted(right);
        cols.getColumn(LedgerTableModel.PAYMENT).setCellRenderer(money);
        cols.getColumn(LedgerTableModel.DEPOSIT).setCellRenderer(money);
        // A loan payment's interest and principal: in italics when typed in rather than calculated.
        TableCellRenderer split = (table, value, isSelected, hasFocus, row, column) -> {
            java.awt.Component c = money.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean manual = ledgerModel.isManualInterest(row);
            c.setFont(c.getFont().deriveFont(manual ? java.awt.Font.ITALIC : java.awt.Font.PLAIN));
            if (c instanceof JComponent j) {
                j.setToolTipText(manual ? "Entered by hand. Clear it to have it calculated again." : null);
            }
            return c;
        };
        cols.getColumn(LedgerTableModel.INTEREST).setCellRenderer(split);
        cols.getColumn(LedgerTableModel.PRINCIPAL).setCellRenderer(split);
        // Balance: red when negative, green when positive (selected rows keep the selection colors).
        cols.getColumn(LedgerTableModel.BALANCE).setCellRenderer((table, value, isSelected, hasFocus, row, column) -> {
            java.awt.Component c = money.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if ((!isSelected || row == table.getEditingRow()) && ledgerModel.isSavedRow(row)) {
                long cents = column == ledgerTable.convertColumnIndexToView(LedgerTableModel.PRINCIPAL_BALANCE)
                        ? ledgerModel.principalBalanceCents(row) : ledgerModel.balanceCents(row);
                if (cents != 0) {
                    c.setForeground(cents < 0 ? NEGATIVE : POSITIVE);
                }
            }
            return c;
        });

        cols.getColumn(LedgerTableModel.PRINCIPAL_BALANCE).setCellRenderer(
                cols.getColumn(LedgerTableModel.BALANCE).getCellRenderer());     // colored like Balance

        // Files: how many attachments; double-click to see them.
        DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
        centered.setHorizontalAlignment(SwingConstants.CENTER);
        cols.getColumn(LedgerTableModel.FILES).setCellRenderer(muted(centered));
        ledgerTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = ledgerTable.rowAtPoint(e.getPoint());
                int col = ledgerTable.columnAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && row >= 0 && col >= 0
                        && ledgerTable.convertColumnIndexToModel(col) == LedgerTableModel.FILES) {
                    showAttachments(row);
                }
            }
        });

        // Text cells: Enter saves and moves down. Category also suggests as you type,
        // and Date also has a pop-out calendar.
        ledgerTable.setDefaultEditor(String.class, new AutoCompleteEditor(null, this::commitAndMoveDown));
        cols.getColumn(LedgerTableModel.CATEGORY).setCellEditor(
                new AutoCompleteEditor(this::categoryPaths, this::commitAndMoveDown));
        cols.getColumn(LedgerTableModel.PAYEE).setCellEditor(
                new AutoCompleteEditor(this::payeeNames, this::commitAndMoveDown));
        cols.getColumn(LedgerTableModel.DATE).setCellEditor(new DateCellEditor(this::commitAndMoveDown));
        cols.getColumn(LedgerTableModel.BANK_DATE).setCellEditor(new DateCellEditor(this::commitAndMoveDown));
        // Bank Date: hovering says how long the bank took, counted from the date entered.
        TableCellRenderer plain = ledgerTable.getDefaultRenderer(Object.class);
        cols.getColumn(LedgerTableModel.BANK_DATE).setCellRenderer((table, value, isSelected, hasFocus, row, column) -> {
            java.awt.Component c = plain.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (c instanceof JComponent j) {
                j.setToolTipText(ledgerModel.bankDelay(row));
            }
            return c;
        });

        // Tag: a colored pill; typing suggests existing tags, and a new name creates a tag.
        cols.getColumn(LedgerTableModel.TAG).setCellRenderer(muted(TagCell.renderer(ledgerModel::tagColor)));
        cols.getColumn(LedgerTableModel.TAG).setCellEditor(
                new AutoCompleteEditor(this::tagNames, this::commitAndMoveDown));

        // Type (POS, DIRECTDEBIT, ...): suggests the bank types and any already used, like Category.
        cols.getColumn(LedgerTableModel.TYPE).setCellEditor(
                new AutoCompleteEditor(this::typeNames, this::commitAndMoveDown));

        // Status: a colored dot, edited with a drop-down.
        cols.getColumn(LedgerTableModel.STATUS).setCellRenderer(muted(StatusCell.renderer()));
        cols.getColumn(LedgerTableModel.STATUS).setCellEditor(StatusCell.editor());

        // Downloaded: an empty blue circle if added new, crossed through if it matched one already there.
        cols.getColumn(LedgerTableModel.DOWNLOAD).setCellRenderer(muted(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
                DownloadIcon icon = DownloadIcon.of((String) value);
                setIcon(icon);
                setHorizontalAlignment(SwingConstants.CENTER);
                setToolTipText(icon == DownloadIcon.MATCHED ? "Downloaded: matched a transaction already entered"
                        : icon == DownloadIcon.NEW ? "Downloaded: added as a new transaction" : null);
                return this;
            }
        }));

        setupSorting();
        ColumnFit.install(ledgerTable);                  // double-click a header border to fit
        setupFilter();

        // Leaving the new-transaction row without an amount: ask what to do.
        ledgerTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || resolvingDraft) {
                return;
            }
            int row = ledgerTable.getSelectedRow();
            if (editingTxn != NO_EDIT && !isEditingTxnRow(row)) {
                editingTxn = NO_EDIT;                   // moved to another transaction: save this one
                ledgerTable.repaint();
                if (ledgerModel.hasPendingEdit()) {
                    long to = ledgerModel.transactionId(row);
                    boolean toDraft = ledgerModel.isDraftRow(row);
                    SwingUtilities.invokeLater(() -> {  // let the click finish first
                        int column = Math.max(0, ledgerTable.getSelectedColumn());
                        if (ledgerModel.commitEdit()) {  // rows may have moved: back to the one chosen
                            int now = toDraft ? ledgerModel.draftRow() : ledgerModel.rowOfTransaction(to);
                            if (now >= 0) {
                                ledgerTable.changeSelection(now, column, false, false);
                            }
                        }
                    });
                }
            }
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

        // Show the download column right after C. (Done last: everything above
        // finds columns by their model number, which is also their place until now.)
        ledgerTable.getColumnModel().moveColumn(LedgerTableModel.DOWNLOAD, LedgerTableModel.STATUS + 1);
        // And a loan's Interest and Principal right after Deposit; Type right after Num.
        TableColumnModel order = ledgerTable.getColumnModel();
        order.moveColumn(order.getColumnIndex("Type"), order.getColumnIndex("Num") + 1);
        order.moveColumn(order.getColumnIndex("Interest"), order.getColumnIndex("Deposit") + 1);
        order.moveColumn(order.getColumnIndex("Principal"), order.getColumnIndex("Interest") + 1);
        order.moveColumn(order.getColumnIndex("Principal Bal."), order.getColumnIndex("Balance") + 1);
        order.moveColumn(order.getColumnIndex("Bank Date"), order.getColumnIndex("Date") + 1);
        setupColumnChooser();
    }

    // ----- Choosing the columns ------------------------------------------------

    /** Every ledger column by its number (hidden ones too), and their usual left-to-right order. */
    private final Map<Integer, javax.swing.table.TableColumn> ledgerColumns = new HashMap<>();
    private final List<Integer> defaultColumnOrder = new ArrayList<>();

    /**
     * Right-clicking the ledger's column headers lists every column with a
     * checkmark: untick one to hide it, tick it to show it again (in its
     * usual place). Date always stays. The choice is remembered.
     */
    private void setupColumnChooser() {
        TableColumnModel cols = ledgerTable.getColumnModel();
        for (int v = 0; v < cols.getColumnCount(); v++) {
            javax.swing.table.TableColumn c = cols.getColumn(v);
            ledgerColumns.put(c.getModelIndex(), c);
            defaultColumnOrder.add(c.getModelIndex());
        }
        readHiddenColumns(AppConfig.HIDDEN_LEDGER_COLUMNS, hiddenOther);
        readHiddenColumns(AppConfig.HIDDEN_DEBT_COLUMNS, hiddenDebt);
        applyColumns();
        ledgerTable.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showMenu(e);                            // Windows opens menus on release
            }

            private void showMenu(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    columnMenu().show(ledgerTable.getTableHeader(), e.getX(), e.getY());
                }
            }
        });
    }

    /** The columns the user has hidden in other ledgers (Interest and Principal also only show for debts). */
    private final java.util.Set<Integer> hiddenOther = new java.util.HashSet<>();
    /** The same for loans' and credit cards' ledgers, chosen separately (they have their own columns). */
    private final java.util.Set<Integer> hiddenDebt = new java.util.HashSet<>();

    /** The hidden columns for the ledger being shown. */
    private java.util.Set<Integer> hiddenColumns() {
        return isDebtShown() ? hiddenDebt : hiddenOther;
    }

    private void readHiddenColumns(String key, java.util.Set<Integer> into) {
        for (String part : config.getString(key, "").split(",")) {
            try {
                int column = Integer.parseInt(part.trim());
                if (column != LedgerTableModel.DATE && ledgerColumns.containsKey(column)) {
                    into.add(column);
                }
            } catch (NumberFormatException ignored) {
                // blank or damaged: nothing hidden
            }
        }
    }

    private JPopupMenu columnMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem title = new JMenuItem("Show columns:");
        title.setEnabled(false);
        menu.add(title);
        boolean debt = isDebtShown();
        Account shown = ledgerModel.currentAccount();
        for (int column : defaultColumnOrder) {
            if (LedgerTableModel.isLoanColumn(column) ? !debt : debt && LedgerTableModel.isHiddenForDebts(column)) {
                continue;                               // only a debt's ledger has these, or a debt's doesn't
            }
            if (LedgerTableModel.isLoanOnlyColumn(column) && (shown == null || shown.type() != AccountType.LOAN)) {
                continue;                               // only a loan's ledger has this (not a card's)
            }
            String header = LedgerTableModel.header(column, shown == null ? null : shown.type());
            javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(
                    debt && !header.isEmpty() ? header : LedgerTableModel.columnName(column), isColumnShown(column));
            item.setEnabled(column != LedgerTableModel.DATE);   // Date always stays
            item.addActionListener(e -> setColumnShown(column, item.isSelected()));
            menu.add(item);
        }
        menu.addSeparator();
        menu.add(menuItem("Show All Columns", () -> {
            hiddenColumns().clear();
            saveHiddenColumns();
            applyColumns();
        }));
        return menu;
    }

    private boolean isColumnShown(int column) {
        return ledgerTable.convertColumnIndexToView(column) >= 0;
    }

    /** True if the ledger is showing a loan or credit card (with its own, shorter set of columns). */
    private boolean isDebtShown() {
        Account a = ledgerModel.currentAccount();
        return a != null && LedgerTableModel.isDebt(a.type());
    }

    /** Hides or shows a ledger column, and saves the choice. */
    private void setColumnShown(int column, boolean show) {
        if (column == LedgerTableModel.DATE) {
            return;
        }
        if (show) {
            hiddenColumns().remove(column);
        } else {
            hiddenColumns().add(column);
        }
        saveHiddenColumns();
        applyColumns();
    }

    private void saveHiddenColumns() {
        StringBuilder hidden = new StringBuilder();
        for (int c : defaultColumnOrder) {
            if (hiddenColumns().contains(c)) {
                hidden.append(hidden.length() == 0 ? "" : ",").append(c);
            }
        }
        try {
            config.setString(isDebtShown() ? AppConfig.HIDDEN_DEBT_COLUMNS : AppConfig.HIDDEN_LEDGER_COLUMNS,
                    hidden.toString());
        } catch (SQLException e) {
            // it applies now, just not next time
        }
    }

    /**
     * Shows the columns that should be: those not hidden, with Interest and
     * Principal only for a loan or credit card, whose ledgers have only their
     * own columns (LedgerTableModel.isHiddenForDebts) and their own choice of
     * hidden ones. A column shown again goes back just after
     * the nearest shown column that usually comes before it.
     */
    private void applyColumns() {
        boolean debt = isDebtShown();
        TableColumnModel cols = ledgerTable.getColumnModel();
        // A loan's or card's ledger calls its amounts Charge and Payment (see LedgerTableModel.header).
        Account shown = ledgerModel.currentAccount();
        AccountType type = shown == null ? null : shown.type();
        for (int column : new int[] {LedgerTableModel.PAYMENT, LedgerTableModel.DEPOSIT}) {
            ledgerColumns.get(column).setHeaderValue(LedgerTableModel.header(column, type));
        }
        ledgerTable.getTableHeader().repaint();
        for (int column : defaultColumnOrder) {
            boolean show = !hiddenColumns().contains(column) && (debt || !LedgerTableModel.isLoanColumn(column))
                    && (!LedgerTableModel.isLoanOnlyColumn(column) || type == AccountType.LOAN)
                    && !(debt && LedgerTableModel.isHiddenForDebts(column));
            if (show == isColumnShown(column)) {
                continue;
            }
            if (ledgerTable.isEditing()) {
                ledgerTable.getCellEditor().stopCellEditing();
            }
            if (show) {
                int place = 0;                          // after the shown columns that usually come before it
                for (int c : defaultColumnOrder) {
                    if (c == column) {
                        break;
                    }
                    int v = ledgerTable.convertColumnIndexToView(c);
                    if (v >= 0) {
                        place = Math.max(place, v + 1);
                    }
                }
                cols.addColumn(ledgerColumns.get(column));
                cols.moveColumn(cols.getColumnCount() - 1, place);
            } else {
                cols.removeColumn(ledgerColumns.get(column));
            }
        }
    }

    /** A column's place on screen, or (if it's hidden) the fallback column's. */
    private int visibleColumnOr(int column, int fallback) {
        int v = ledgerTable.convertColumnIndexToView(column);
        return v >= 0 ? v : ledgerTable.convertColumnIndexToView(fallback);
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
        filterField.setToolTipText("<html>Show only transactions with this text in any column.<br>"
                + "Start with a column's name and a colon to look in just that column:"
                + " <b>payee: walmart</b>, <b>type:pos</b>, <b>memo: card 2549</b></html>");
        hideReconciledBox.setToolTipText("Hide reconciled transactions (balances still include them)");
        hideReconciledBox.setSelected(config.getBoolean(AppConfig.HIDE_RECONCILED, false));   // as last left

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.LINE_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        bar.add(new JLabel("Filter: "));
        bar.add(filterField);
        filterField.setMinimumSize(new java.awt.Dimension(scaled(140), filterField.getPreferredSize().height));
        bar.add(Box.createHorizontalStrut(4));
        bar.add(clear);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(hideReconciledBox);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(payViewLabel);
        bar.add(payView);
        payView.setMaximumSize(payView.getPreferredSize());
        payView.addActionListener(e -> applyPayView());
        bar.add(Box.createHorizontalStrut(12));
        JButton goToNew = new JButton("New Transaction");
        goToNew.setToolTipText("Jump to the blank row for entering a new transaction (Ctrl+N)");
        goToNew.addActionListener(e -> goToNewTransaction());
        bar.add(goToNew);
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
        hideReconciledBox.addActionListener(e -> {
            applyFilter();
            try {
                config.setBoolean(AppConfig.HIDE_RECONCILED, hideReconciledBox.isSelected());   // kept for next time
            } catch (SQLException ex) {
                // it starts unticked next time
            }
        });

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

        // Ctrl+N from anywhere in the window jumps to the blank new-transaction row.
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcut()), "newTransaction");
        getRootPane().getActionMap().put("newTransaction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                goToNewTransaction();
            }
        });

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
        bar.add(syncStatusLabel, java.awt.BorderLayout.WEST);
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
                        + "&nbsp;&nbsp;&nbsp;" + (isDebtShown() ? "Charges: " : "Payments: ") + Money.format(payments)
                        + "&nbsp;&nbsp;&nbsp;" + (isDebtShown() ? "Payments: " : "Deposits: ") + Money.format(deposits)
                        + "&nbsp;&nbsp;&nbsp;<b>Net: " + Money.format(deposits - payments) + "</b></html>");
    }

    /** Selects (and scrolls to) the blank new-transaction row, ready to type its date. */
    private void goToNewTransaction() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();   // save a half-finished cell first
        }
        int row = ledgerModel.draftRow();
        if (row < 0) {
            showError("Select an account first.");
            return;
        }
        ledgerTable.changeSelection(row, ledgerTable.convertColumnIndexToView(LedgerTableModel.DATE), false, false);
        ledgerTable.requestFocusInWindow();
    }

    /** Passes the filter bar's settings to the ledger. */
    private void applyFilter() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();   // save a half-finished cell first
        }
        ledgerModel.setFilter(filterField.getText(), hideReconciledBox.isSelected() && !isDebtShown());
    }

    private void updateFilterCount() {
        filterCountLabel.setText(ledgerModel.isFiltered()
                ? (payPeriod == null ? "Showing " + ledgerModel.shownCount() + " of " + ledgerModel.transactionCount()
                        + " transactions"
                        : payPeriod.from().format(LedgerTableModel.DISPLAY_DATE) + " to "
                        + payPeriod.last().format(LedgerTableModel.DISPLAY_DATE) + ": " + ledgerModel.shownCount()
                        + " shown, " + ledgerModel.notEnteredCount() + " not entered yet")
                : payView.getSelectedItem() instanceof Paychecks.Mode && payPeriod == null ? "No paycheck to go by" : " ");
    }

    // ----- Paychecks: what's due before payday (see Paychecks) ----------------------------

    /**
     * Edit > Enter Scheduled (and the ledger's right-click): the selected
     * scheduled transactions are entered, taken off Scheduled (status None),
     * as the phone's "Enter scheduled" does. Others selected are left alone.
     */
    private void enterScheduled() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        List<Integer> scheduled = new ArrayList<>();
        for (int row : selectedTransactionRows()) {
            if (ledgerModel.isNotEntered(row)) {
                scheduled.add(row);
            }
        }
        if (scheduled.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select one or more scheduled transactions (still marked Scheduled)"
                    + " to enter.", "MyMoney", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ledgerModel.bulkSet(scheduled.stream().mapToInt(Integer::intValue).toArray(),
                LedgerTableModel.STATUS, TxnStatus.NONE);
    }

    /** The period the ledger is showing (Show: a paycheck period), or null for everything. */
    private Paychecks.Period payPeriod;

    /** Show: all transactions, or the uncleared ones in a paycheck period. */
    private void applyPayView() {
        payPeriod = null;
        if (payView.getSelectedItem() instanceof Paychecks.Mode m) {
            try {
                payPeriod = paychecks.current(m, LocalDate.now());
            } catch (SQLException e) {
                showError("Could not read the paychecks:\n" + e.getMessage());
            }
        }
        ledgerModel.setPeriod(payPeriod);
        updateFilterCount();
        payView.setToolTipText(payPeriod == null ? "What's due before payday (Preferences > Paychecks)"
                : "Not yet cleared, " + payPeriod.from().format(LedgerTableModel.DISPLAY_DATE) + " to "
                        + payPeriod.last().format(LedgerTableModel.DISPLAY_DATE) + " ("
                        + Paychecks.rankName(payPeriod.paycheck().paycheck()).toLowerCase() + " paycheck on "
                        + payPeriod.payday().format(LedgerTableModel.DISPLAY_DATE) + ")");
    }

    /** Shows the paycheck controls if paychecks are turned on (and brings them up to date); hides them if not. */
    private void refreshPaycheckControls() {
        boolean on;
        try {
            on = paychecks.settings().enabled();
        } catch (SQLException e) {
            on = false;
        }
        payViewLabel.setVisible(on);
        payView.setVisible(on);
        if (!on && payView.getSelectedIndex() != 0) {
            payView.setSelectedIndex(0);                 // off: everything again
        } else {
            applyPayView();                              // a paycheck or the date may have changed
        }
        refreshSummaryIfShown();
    }

    /**
     * Grey for the blank new-transaction row: a shade lighter than the
     * ledger's background on a dark theme, a shade darker on a light one.
     */
    private static Color newRowBackground(javax.swing.JTable table) {
        Color bg = table.getBackground();
        boolean dark = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000 < 128;
        return dark ? new Color(0x4A, 0x4A, 0x4A) : new Color(0xD6, 0xD6, 0xD6);
    }

    /** Balances: red below zero, green above (readable on dark, light and white rows). */
    static final Color NEGATIVE = new Color(0xE0, 0x3E, 0x2D);
    static final Color POSITIVE = new Color(0x22, 0xA0, 0x55);

    /** The row being edited: white, with dark text, in light and dark themes alike. */
    static final Color EDITING_BACKGROUND = Color.WHITE;
    static final Color EDITING_TEXT = Color.BLACK;

    /** True if a row shows the transaction being edited (see editingTxn). */
    private boolean isEditingTxnRow(int row) {
        if (editingTxn == NO_EDIT || row < 0) {
            return false;
        }
        return editingTxn == 0 ? ledgerModel.isDraftRow(row)
                : ledgerModel.isSavedRow(row) && ledgerModel.transactionId(row) == editingTxn;
    }

    /** Text color for transactions dated after today. */
    private static final Color FUTURE_TEXT = new Color(128, 128, 128);         // grey

    /**
     * Showing a paycheck period: a scheduled transaction not yet entered, in
     * a muted blue-grey on a faint tint (light and dark themes), in italics.
     */
    private static Color notEnteredBackground(javax.swing.JTable table) {
        Color bg = table.getBackground();
        boolean dark = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000 < 128;
        return dark ? new Color(0x26, 0x2D, 0x38) : new Color(0xEE, 0xF2, 0xF8);
    }

    private static Color notEnteredText(javax.swing.JTable table) {
        Color bg = table.getBackground();
        boolean dark = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000 < 128;
        return dark ? new Color(0x8A, 0x97, 0xAB) : new Color(0x6E, 0x7C, 0x91);
    }

    /**
     * Wraps a cell renderer so future-dated rows get grey text (on the normal
     * background). Selected rows keep the normal selection colors, the
     * blank new-transaction row is always grey, and the row being edited is
     * white (the blank row too, while it's being typed in).
     */
    private TableCellRenderer muted(TableCellRenderer base) {
        return (table, value, isSelected, hasFocus, row, column) -> {
            java.awt.Component c = base.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (row == table.getEditingRow() || isEditingTxnRow(row)) {
                c.setBackground(EDITING_BACKGROUND);   // the transaction being edited stands out
                c.setForeground(EDITING_TEXT);
            } else if (ledgerModel.isDraftRow(row)) {
                c.setBackground(newRowBackground(table)); // the blank new-transaction row: always grey
                c.setForeground(table.getForeground());
            } else if (payPeriod != null) {
                // Showing a paycheck period: not yet entered (still Scheduled) muted and in
                // italics; entered ones in the usual colors, even when dated ahead.
                boolean notEntered = ledgerModel.isNotEntered(row);
                if (!isSelected) {
                    c.setBackground(notEntered ? notEnteredBackground(table) : table.getBackground());
                    c.setForeground(notEntered ? notEnteredText(table) : table.getForeground());
                }
            } else if (!isSelected) {
                c.setBackground(table.getBackground());
                c.setForeground(ledgerModel.isFuture(row) ? FUTURE_TEXT : table.getForeground());
            }
            // Italics for one not yet entered, and only for it (a renderer is reused from cell to cell).
            boolean italic = payPeriod != null && row != table.getEditingRow() && !isEditingTxnRow(row)
                    && ledgerModel.isNotEntered(row);
            java.awt.Font f = c.getFont();
            if (f != null && f.isItalic() != italic) {
                c.setFont(f.deriveFont(italic ? f.getStyle() | java.awt.Font.ITALIC : f.getStyle() & ~java.awt.Font.ITALIC));
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
            java.awt.Component c = plain.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
            if (c instanceof JLabel label) {       // Files: a paperclip instead of a name
                boolean narrow = modelColumn == LedgerTableModel.STATUS || modelColumn == LedgerTableModel.FILES
                        || modelColumn == LedgerTableModel.DOWNLOAD;
                label.setHorizontalAlignment(narrow ? SwingConstants.CENTER : SwingConstants.LEADING);
                label.setIcon(modelColumn == LedgerTableModel.FILES ? PaperclipIcon.INSTANCE : null);
                label.setToolTipText(modelColumn == LedgerTableModel.FILES ? "Attachments"
                        : modelColumn == LedgerTableModel.STATUS ? "Status"
                        : modelColumn == LedgerTableModel.DOWNLOAD ? "Downloaded" : null);
            }
            return c;
        });

        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int column = header.columnAtPoint(e.getPoint());
                if (column < 0 || !SwingUtilities.isLeftMouseButton(e) || ColumnFit.onBorder(header, e)) {
                    return;                             // a click on a border resizes, not sorts
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
     * Enter in a ledger cell: saves the cell, then moves down one row, or (with
     * the "Enter moves to the next field" preference) to the next field of the
     * same transaction, and past the last field to the next row's Date. After
     * a new transaction is recorded, moves to the Date of the new blank row.
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
        if (enterNextField) {
            // Enter works like Tab: on to the next field of this transaction that can be edited.
            for (int v = column + 1; v < ledgerTable.getColumnCount(); v++) {
                if (ledgerModel.isCellEditable(row, ledgerTable.convertColumnIndexToModel(v))) {
                    editField(row, v);
                    return;
                }
            }
            column = ledgerTable.convertColumnIndexToView(LedgerTableModel.DATE);   // past the last: next row's Date
        }
        // Save the whole transaction (see LedgerTableModel.commitEdit).
        if (ledgerModel.isDraftRow(row)) {
            if (ledgerModel.draftHasData() && !resolveDraft(true)) {
                selectDraftAmount();                    // Enter on a new transaction with no amount
                return;
            }
        } else if (ledgerModel.hasPendingEdit()) {
            long id = ledgerModel.transactionId(row);
            ledgerModel.commitEdit();
            editingTxn = NO_EDIT;
            int now = ledgerModel.rowOfTransaction(id);   // it may have moved (a new date)
            if (now >= 0) {
                row = now;
            }
        }
        editingTxn = NO_EDIT;
        ledgerTable.repaint();
        int rows = ledgerTable.getRowCount();
        if (rows > rowsBefore) {                        // a new transaction was saved: on to the next blank row
            ledgerTable.changeSelection(ledgerModel.draftRow(),
                    ledgerTable.convertColumnIndexToView(LedgerTableModel.DATE), false, false);
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
        if (resolvingDraft) {
            return true;
        }
        if (ledgerModel.commitEdit()) {                 // a transaction being edited: save it
            editingTxn = NO_EDIT;
        }
        if (!ledgerModel.draftHasData()) {
            return true;
        }
        if (ledgerModel.draftHasAmount()) {
            ledgerModel.recordDraft();                  // a new transaction with an amount: save it
            editingTxn = NO_EDIT;
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

    /**
     * Tab (step 1) or Shift+Tab (-1): on to the next field of the same
     * transaction that can be edited, editing it, round to the first after
     * the last. The transaction isn't saved until Enter or leaving its row.
     */
    private void moveField(int step) {
        boolean editing = ledgerTable.isEditing();
        int row = editing ? ledgerTable.getEditingRow() : ledgerTable.getSelectedRow();
        int column = editing ? ledgerTable.getEditingColumn() : ledgerTable.getSelectedColumn();
        if (row < 0) {
            return;
        }
        if (editing && !ledgerTable.getCellEditor().stopCellEditing()) {
            return;                                     // bad input: stay in the field
        }
        int n = ledgerTable.getColumnCount();
        for (int i = 1; i <= n; i++) {
            int v = Math.floorMod(column + step * i, n);
            if (ledgerModel.isCellEditable(row, ledgerTable.convertColumnIndexToModel(v))) {
                editField(row, v);
                return;
            }
        }
    }

    /** Selects a cell and starts editing it, with its text selected so typing replaces it. */
    private void editField(int row, int viewColumn) {
        ledgerTable.changeSelection(row, viewColumn, false, false);
        if (ledgerTable.editCellAt(row, viewColumn)) {
            java.awt.Component c = ledgerTable.getEditorComponent();
            c.requestFocusInWindow();
            if (c instanceof javax.swing.text.JTextComponent t) {
                t.selectAll();
            }
        }
    }

    /** Lets Tab reach the ledger (moveField) from inside a cell editor, instead of leaving the table. */
    private static void keepTabInLedger(java.awt.Component c) {
        c.setFocusTraversalKeysEnabled(false);
        if (c instanceof java.awt.Container box) {
            for (java.awt.Component child : box.getComponents()) {
                keepTabInLedger(child);
            }
        }
    }

    /**
     * Escape (or Undo) with changes to a saved transaction not yet saved:
     * puts it back as it was. Returns false if there were none.
     */
    private boolean revertEdit() {
        if (!ledgerModel.hasPendingEdit()) {
            return false;
        }
        long id = editingTxn;
        int column = Math.max(0, ledgerTable.getSelectedColumn());
        ledgerModel.cancelEdit();
        editingTxn = NO_EDIT;
        int row = ledgerModel.rowOfTransaction(id);
        if (row >= 0) {
            ledgerTable.changeSelection(row, column, false, false);
        }
        ledgerTable.repaint();
        return true;
    }

    /** Puts the cursor on the new-transaction row's Payment cell. */
    private void selectDraftAmount() {
        int draftRow = ledgerModel.draftRow();
        if (draftRow >= 0) {
            resolvingDraft = true;
            try {
                ledgerTable.changeSelection(draftRow,
                        visibleColumnOr(LedgerTableModel.PAYMENT, LedgerTableModel.DATE), false, false);
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
    /** The types to suggest in the Type column: the banks' (TRNTYPE) and any others used. */
    private List<String> typeNames() {
        try {
            return transactionRepo.findTypes();
        } catch (SQLException e) {
            return TransactionRepository.STANDARD_TYPES;
        }
    }

    private List<String> tagNames() {
        try {
            return tags.findAllNames();
        } catch (SQLException e) {
            return List.of();
        }
    }

    // ----- Menus ------------------------------------------------------------

    /** Builds the File, Edit and Account menus, and the right-click menus on the ledger and tree. */
    private void setupMenus() {
        // File menu: the data file itself. New Account (from the form) moves to Account.
        fileMenu.remove(newAccountItem);
        JMenuItem openItem = menuItem("Open...", this::openFile);
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, menuShortcut()));
        fileMenu.insert(openItem, 0);
        fileMenu.insertSeparator(1);
        fileMenu.insert(menuItem("Import...", this::importFile), 2);
        fileMenu.insert(menuItem("Back Up Database...", this::backupDatabase), 3);
        fileMenu.insert(menuItem("Rename File...", this::renameFile), 4);

        // Account menu: the selected account, and tags.
        javax.swing.JMenu accountMenu = new javax.swing.JMenu("Account");
        accountMenu.add(newAccountItem);
        accountMenu.add(menuItem("Edit Account...", this::editAccount));
        accountMenu.add(menuItem("Delete Account...", this::deleteAccount));
        accountMenu.add(menuItem("Manage Accounts...", this::manageAccounts));
        accountMenu.addSeparator();
        accountMenu.add(menuItem("Amortization Schedule...", this::showLoanSchedule));
        accountMenu.add(menuItem("Payoff Schedule...", this::showCardPayoff));
        JMenuItem reconcileItem = menuItem("Reconcile Account...", this::reconcileAccount);
        reconcileItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, menuShortcut()));
        accountMenu.add(reconcileItem);
        accountMenu.add(menuItem("Statements...", this::showStatements));
        accountMenu.addSeparator();
        accountMenu.add(menuItem("Manage Tags...", this::manageTags));
        accountMenu.add(menuItem("Manage Payee Renames...", this::managePayeeRenames));
        accountMenu.addSeparator();
        accountMenu.add(menuItem("Scheduled Transactions...", this::manageSchedules));
        accountMenu.add(menuItem("Debt Snowball...", this::debtSnowball));

        // Edit menu: changes to the selected transactions.
        javax.swing.JMenu editMenu = new javax.swing.JMenu("Edit");
        JPopupMenu ledgerMenu = new JPopupMenu();
        for (javax.swing.JComponent menu : new javax.swing.JComponent[] {editMenu, ledgerMenu}) {
            menu.add(menuItem("Edit Transactions...", this::editTransactions));
            menu.add(menuItem("Enter Scheduled", this::enterScheduled));
            menu.add(menuItem("Move Memo to Description", this::moveMemoToDescription));
            menu.add(menuItem("Clear Downloaded Flag", this::clearDownloadedFlag));
            menu.add(menuItem("Add to Schedule...", this::addToSchedule));
            menu.add(menuItem("Move to Account...", this::moveToAccount));
            menu.add(new javax.swing.JSeparator());
            menu.add(menuItem("Attachments...", () -> showAttachments(ledgerTable.getSelectedRow())));
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
        editMenu.addSeparator();
        editMenu.add(menuItem("Preferences...", this::editPreferences));
        int file = menuBar.getComponentIndex(fileMenu);  // (the account list button may come first)
        menuBar.add(editMenu, file + 1);                // after File
        menuBar.add(accountMenu, file + 2);             // after Edit

        // Sync: with the phones (see docs/SYNC-DESIGN.md).
        javax.swing.JMenu syncMenu = new javax.swing.JMenu("Sync");
        JMenuItem syncItem = menuItem("Sync Now", () -> syncNow(true));
        syncItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        syncMenu.add(syncItem);
        syncMenu.addSeparator();
        syncMenu.add(menuItem("Settings...", this::syncSettings));
        menuBar.add(syncMenu, file + 3);                // after Account, before Help

        // Help: the help pages (F1), and About.
        javax.swing.JMenu helpMenu = new javax.swing.JMenu("Help");
        JMenuItem helpItem = menuItem("MyMoney Help", () -> HelpDialog.show(this));
        helpItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
        helpMenu.add(helpItem);
        helpMenu.addSeparator();
        helpMenu.add(menuItem("About MyMoney", () -> AboutDialog.show(this, db.getFile())));
        menuBar.add(helpMenu);                          // last

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
        treeMenu.add(menuItem("Reconcile Account...", this::reconcileAccount));
        treeMenu.add(menuItem("Statements...", this::showStatements));
        treeMenu.add(menuItem("Amortization Schedule...", this::showLoanSchedule));
        treeMenu.add(menuItem("Payoff Schedule...", this::showCardPayoff));
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
        refreshSummaryIfShown();
        if (editingTxn == 0 && !ledgerModel.draftHasData()) {
            editingTxn = NO_EDIT;                       // the new transaction was saved: committed
        }
        updateSummary(selectedAccount());
        refreshTreeBalances();                          // a transfer changes another account too
        checkpoint(ledgerModel.lastAction());
    }

    /** Groups everything saved since the last checkpoint into one undo step. */
    private void checkpoint(String label) {
        // Whatever changed, loans' interest is brought up to date as part of the same step.
        boolean recalculated = false;
        try {
            recalculated = transactionRepo.recalculateAllLoans();
        } catch (SQLException e) {
            showError("Could not work out the interest in your loans' payments:\n" + e.getMessage());
        }
        if (undoLog != null) {
            try {
                undoLog.checkpoint(label);
            } catch (SQLException e) {
                // undo history is a convenience; never block the change itself
            }
            updateUndoMenu();
        }
        fileAttachments();                               // a new date or payee: its files follow
        if (recalculated) {
            ledgerModel.reload();
            updateSummary(selectedAccount());
            refreshTreeBalances();
        }
    }

    /** Edit > Undo (Ctrl+Z). While typing in a cell, it just cancels the typing. */
    private void undo() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().cancelCellEditing();
            return;
        }
        if (revertEdit()) {
            return;                                     // undoes the unsaved changes to the transaction being edited
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
        fileAttachments();                               // the date or payee back: the files go back too
        refreshAfterUndo();
    }

    /**
     * Keeps transactions' attached files named and filed for them ("<id>-<payee>.<ext>"
     * in the date's folder). Moving files isn't undone, so the paths it saves aren't kept
     * as an undo step: Undo changes the transaction, and this files its attachments again.
     */
    private void fileAttachments() {
        try {
            attachments.fileTransactionAttachments();
        } catch (SQLException e) {
            // they stay where they are, and are still found there
        }
    }

    /** Edit > Redo (Ctrl+Y). */
    private void redo() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        ledgerModel.commitEdit();
        if (undoLog == null || undoLog.redoLabel() == null) {
            return;
        }
        try {
            undoLog.redo();
        } catch (SQLException e) {
            showError("Could not redo:\n" + e.getMessage());
        }
        fileAttachments();
        refreshAfterUndo();
    }

    /** Redraws accounts, ledger and balances after Undo or Redo, keeping the selection and sort. */
    private void refreshAfterUndo() {
        Account selected = selectedAccount();
        loadAccounts(selected == null ? null : selected.id());
        if (selectedAccount() == null && accountTree.getRowCount() > 0) {
            selectFirstAccount();                      // the selected account was undone away
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
        boolean debt = a.type() == AccountType.LOAN || a.type() == AccountType.CREDIT_CARD;
        int kept = info.transfers() - info.upcomingPayments();
        if (kept > 0) {
            message.append("\n").append(kept).append(kept == 1 ? " transfer" : " transfers")
                   .append(" with other accounts will stay in those accounts")
                   .append(debt ? ", in the category " + AccountRepository.debtPaymentsCategory(a.name()) + "."
                           : ", with no category.")
                   .append(" Their balances don't change.");
        }
        if (info.linkedSchedules() > 0) {
            message.append("\n").append(info.linkedSchedules())
                   .append(info.linkedSchedules() == 1 ? " scheduled payment pays" : " scheduled payments pay")
                   .append(" this account; ").append(info.linkedSchedules() == 1 ? "it" : "they").append(" will end");
            if (info.upcomingPayments() > 0) {
                message.append(", and ").append(info.upcomingPayments())
                       .append(info.upcomingPayments() == 1 ? " upcoming payment" : " upcoming payments")
                       .append(" not yet made will be removed");
            }
            message.append(".");
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
            selectFirstAccount();
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

    // ----- Scheduled transactions ------------------------------------------------

    /**
     * Puts every schedule's occurrences in the ledger, up to the number of
     * days ahead set in Preferences (and, with paychecks on, at least to the
     * end of the next pay period), and takes back untouched ones past that,
     * as one undo step. Problems are shown; if
     * refresh, the ledger and balances are redrawn.
     */
    private void createScheduledTransactions(boolean refresh) {
        List<String> problems = new ArrayList<>();
        int created;
        try {
            int days = scheduleDaysAhead();
            created = schedules.trim(days);              // fewer days ahead: take back the untouched extras
            created += schedules.generate(days, problems);
        } catch (SQLException e) {
            showError("Could not create the scheduled transactions:\n" + e.getMessage());
            return;
        }
        if (!problems.isEmpty()) {
            showError("Some scheduled transactions could not be created:\n\n" + String.join("\n", problems));
        }
        if (created > 0) {
            checkpoint("Create Scheduled Transactions");
        }
        if (refresh) {
            ledgerModel.reload();
            updateSummary(selectedAccount());
            refreshTreeBalances();
        }
    }

    /**
     * How many days ahead scheduled transactions are put in the ledger: the
     * Preferences setting, or, with paychecks on, to the end of the next pay
     * period if that's further, so Show: and the Summary see every bill in it.
     */
    private int scheduleDaysAhead() {
        int days = config.getInt(AppConfig.SCHEDULE_DAYS, AppConfig.DEFAULT_SCHEDULE_DAYS);
        try {
            return paychecks.daysAhead(days, LocalDate.now());
        } catch (SQLException e) {
            return days;                                 // the setting alone
        }
    }

    /** Account > Debt Snowball...: the plan for paying off the loans and cards; it can update their schedules. */
    private void debtSnowball() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        try {
            DebtSnowballDialog dlg = new DebtSnowballDialog(this, db.getConnection(), accounts, schedules);
            dlg.setVisible(true);
            if (dlg.schedulesChanged()) {
                checkpoint("Debt Snowball");
                createScheduledTransactions(true);
            }
        } catch (SQLException e) {
            showError("Could not open the debt snowball:\n" + e.getMessage());
        }
    }

    /** Account > Scheduled Transactions...: the list of schedules, to add, edit and delete. */
    private void manageSchedules() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        ScheduleListDialog dlg = new ScheduleListDialog(this, schedules, accounts,
                s -> openScheduleForm(s != null ? s : blankSchedule()), db.getFile().getParent());
        dlg.setVisible(true);
        if (dlg.wasChanged()) {
            checkpoint("Scheduled Transactions");
            createScheduledTransactions(true);
            refreshPaycheckControls();
        }
    }

    /**
     * Edit > Add to Schedule... (and the ledger's right-click): the schedule
     * form, filled in from the selected transaction, repeating monthly from a
     * month after it.
     */
    private void addToSchedule() {
        Account a = ledgerModel.currentAccount();
        LedgerEntry e = ledgerModel.entryCopy(ledgerTable.getSelectedRow());
        if (a == null || e == null) {
            showError("Select a saved transaction to schedule first.");
            return;
        }
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        if (TransactionRepository.SPLIT_LABEL.equals(e.category)) {
            showError("Split transactions can't be scheduled yet.");
            return;
        }
        ScheduleRepository.Schedule template = new ScheduleRepository.Schedule(0, a.id(), e.payee, e.category,
                e.tag, e.description, e.memo, e.amountCents,
                new Recurrence(Recurrence.Unit.MONTH, 1, 15, e.date.plusMonths(1), null, null));
        if (openScheduleForm(template) != null) {
            checkpoint("Add to Schedule");
            createScheduledTransactions(true);
        }
    }

    /** A new, empty schedule: the selected account, monthly from today. */
    private ScheduleRepository.Schedule blankSchedule() {
        Account a = selectedAccount();
        return new ScheduleRepository.Schedule(0, a == null ? 0 : a.id(), null, null, null, null, null, 0,
                new Recurrence(Recurrence.Unit.MONTH, 1, 15, LocalDate.now(), null, null));
    }

    /** Opens the schedule form; returns the saved schedule's id, or null if it wasn't saved. */
    private Long openScheduleForm(ScheduleRepository.Schedule s) {
        List<Account> all;
        try {
            all = accounts.findAll();
        } catch (SQLException e) {
            showError("Could not read the accounts:\n" + e.getMessage());
            return null;
        }
        if (all.isEmpty()) {
            showError("Add an account first.");
            return null;
        }
        ScheduleDialog dlg = new ScheduleDialog(this, schedules, s, all, this::payeeNames,
                this::categoryPaths, this::tagNames, this::lastCategoryForPayee);
        dlg.setVisible(true);
        if (dlg.savedId() != null) {
            SwingUtilities.invokeLater(this::refreshPaycheckControls);   // it may have become (or stopped being) a paycheck
        }
        return dlg.savedId();
    }

    /** Account > Amortization Schedule...: the selected loan's payments, interest and principal. */
    private void showLoanSchedule() {
        Account a = selectedAccount();
        Loan loan;
        try {
            loan = a == null ? null : accounts.findLoan(a.id());
            if (loan == null) {
                showError("Select a loan account first.");
                return;
            }
            if (loan.regularPayment() == 0) {
                showError("Enter the loan's original amount and term first (Account > Edit Account).");
                return;
            }
            new LoanScheduleDialog(this, a.name(), loan, accounts.principalOwed(a.id(), LocalDate.now()))
                    .setVisible(true);
        } catch (SQLException e) {
            showError("Could not read the loan:\n" + e.getMessage());
        }
    }

    /** Account > Payoff Schedule...: paying off the selected credit card's balance. */
    private void showCardPayoff() {
        Account a = selectedAccount();
        try {
            CreditCard card = a == null ? null : accounts.findCard(a.id());
            if (card == null) {
                showError("Select a credit card account first.");
                return;
            }
            long owed = -accounts.balanceThrough(a.id(), LocalDate.now());
            if (owed <= 0) {
                JOptionPane.showMessageDialog(this, "Nothing is owed on " + a.name() + ".", "Payoff Schedule",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            new CardPayoffDialog(this, a.name(), card, owed).setVisible(true);
        } catch (SQLException e) {
            showError("Could not read the card:\n" + e.getMessage());
        }
    }

    /** Account > Manage Payee Renames...: the payee names remembered for bank names during imports. */
    private void managePayeeRenames() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        PayeeRenameDialog dlg = new PayeeRenameDialog(this, new PayeeRenameRepository(db), this::payeeNames);
        dlg.setVisible(true);
        if (dlg.wasChanged()) {
            checkpoint("Payee Renames");
        }
    }

    /** Account > Manage Accounts...: every account's full details, to view and edit. */
    private void manageAccounts() {
        openAccounts(false);
    }

    /**
     * Manage Accounts, starting on the selected account, or (newAccount) with
     * the form empty for a new one. Afterwards the tree is rebuilt; after a
     * new account is created, it is selected.
     */
    private void openAccounts(boolean newAccount) {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        ManageAccountsDialog dlg = new ManageAccountsDialog(this, accounts, attachments,
                ManageAccountsDialog.categoriesOnly(categoryPaths()), db.getFile(), selectedAccount(),
                newAccount ? ManageAccountsDialog.BLANK : null);
        dlg.setVisible(true);
        if (dlg.wasChanged()) {
            checkpoint(newAccount ? "New Account" : "Manage Accounts");
            Account selected = selectedAccount();
            Long select = newAccount && dlg.lastSavedId() != null ? dlg.lastSavedId()
                    : selected == null ? null : selected.id();
            loadAccounts(select);                       // names, parents, balances may have changed
        }
    }

    /**
     * Puts a second line under the account name in the top panel: the
     * account's website as a link (hidden when it has none).
     */
    private void setupWebsiteLink() {
        summaryPanel.remove(accountNameLabel);
        JPanel names = new JPanel();
        names.setOpaque(false);
        names.setLayout(new BoxLayout(names, BoxLayout.PAGE_AXIS));
        names.add(accountNameLabel);
        names.add(loanNumberLabel);
        names.add(websiteLabel);
        loanNumberLabel.setVisible(false);              // (its font: see refreshFonts)
        summaryPanel.add(names, java.awt.BorderLayout.WEST);
        // The account's balance over the past 12 months, between its name and its balances.
        JPanel chartHolder = new JPanel(new java.awt.BorderLayout());
        chartHolder.setOpaque(false);
        chartHolder.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
        chartHolder.add(balanceChart, java.awt.BorderLayout.CENTER);
        summaryPanel.add(chartHolder, java.awt.BorderLayout.CENTER);
        rightSplit.setDividerLocation(160);             // room for the chart
        websiteLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        websiteLabel.setVisible(false);
        websiteLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (websiteUrl != null && SwingUtilities.isLeftMouseButton(e)) {
                    openWebsite(websiteUrl);
                }
            }
        });
    }

    /** Under the account name: a loan's number, and the account's website as a link (each only if it has one). */
    private void showWebsite(Account a) {
        String site = null;
        String loanNumber = null;
        if (a != null) {
            try {
                AccountRepository.Details d = accounts.findDetails(a.id());
                site = d == null ? null : d.website();
                loanNumber = d == null || d.type() != AccountType.LOAN ? null : d.accountNumber();
            } catch (SQLException e) {
                // no link, then
            }
        }
        // A loan's number (from Manage Accounts) goes just under its name.
        loanNumberLabel.setVisible(loanNumber != null && !loanNumber.isBlank());
        loanNumberLabel.setText(loanNumber == null ? "" : "Loan " + loanNumber.trim());
        websiteUrl = site == null || site.isBlank() ? null
                : site.contains("://") ? site.trim() : "https://" + site.trim();
        websiteLabel.setVisible(websiteUrl != null);
        if (websiteUrl != null) {
            websiteLabel.setText("<html><a href=''>" + escape(site.trim()) + "</a></html>");
            websiteLabel.setToolTipText("Open " + websiteUrl + " in your browser");
        }
    }

    /** Opens a web page in the system's browser (in the background). */
    private void openWebsite(String url) {
        new Thread(() -> {
            try {
                java.net.URI uri = new java.net.URI(url);
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(uri);
                } else {
                    String os = System.getProperty("os.name").toLowerCase();
                    String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
                    new ProcessBuilder(cmd, uri.toString()).start();
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> showError("Could not open the website:\n" + e.getMessage()));
            }
        }, "open-website").start();
    }

    /**
     * Account > Edit Account... (and the tree's right-click Edit Account):
     * the same account details as Manage Accounts, opened on the selected account.
     */
    private void editAccount() {
        if (selectedAccount() == null) {
            showError("Select an account to edit first.");
            return;
        }
        manageAccounts();                               // it starts on the selected account
    }

    /** Shows (and lets you change) a ledger row's attached files. */
    private void showAttachments(int row) {
        Account a = ledgerModel.currentAccount();
        long txnId = ledgerModel.transactionId(row);
        if (a == null || txnId == 0) {
            showError("Select a saved transaction first.");
            return;
        }
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        if (AttachmentsDialog.show(this, a, txnId, ledgerModel.transactionDate(row), attachments)) {
            ledgerModel.reload();                        // the file count (attaching isn't undone)
        }
    }

    /** Account > Statements...: the selected account's statements, to add, open, re-date or remove. */
    private void showStatements() {
        Account a = selectedAccount();
        if (a == null) {
            showError("Select an account first.");
            return;
        }
        StatementsDialog.show(this, a, attachments);    // (not undoable: see AttachmentRepository.setUndoLog)
    }

    /**
     * File > Reconcile Account...: asks for the statement (or picks up an
     * unfinished reconciliation), then shows the reconcile window.
     */
    private void reconcileAccount() {
        Account a = selectedAccount();
        if (a == null) {
            showError("Select an account to reconcile first.");
            return;
        }
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        checkpoint("Change");             // what came before stays its own undo step: Cancel puts back only this
        java.util.Set<Long> attachmentsBefore;
        try {
            attachmentsBefore = attachments.idsFor(a.id());
        } catch (SQLException e) {
            attachmentsBefore = null;      // can't tell which files are new: no Cancel
        }
        try {
            // The start window every time, even to pick up an unfinished one (with its statement filled
            // in), so the statement's period is always shown and, with As of, selected.
            ReconcileRepository.Reconciliation r = reconciles.unfinished(a.id());
            LocalDate last = reconciles.lastReconciledDate(a.id());
            LocalDate date = r != null ? r.statementDate() : last == null ? LocalDate.now() : last.plusMonths(1);
            ReconcileStartDialog start = new ReconcileStartDialog(this, a, reconciles.beginningBalance(a),
                    r == null ? null : r.endingBalanceCents(), date, reconciles.statementStart(a.id()), true);
            start.setVisible(true);
            if (!start.wasAccepted()) {
                return;
            }
            r = reconciles.start(a, start.startDate(), start.statementDate(), start.endingCents(), start.asOf());
            ReconcileWindow w = new ReconcileWindow(this, a, r, reconciles, transactionRepo, attachments,
                    this::payeeNames, this::categoryPaths, this::tagNames,
                    undoLog != null && attachmentsBefore != null);
            w.setVisible(true);
            if (w.wasCancelled()) {
                cancelReconcile(a, attachmentsBefore);
            } else {
                checkpoint(w.wasFinished() ? "Reconcile" : "Reconcile (unfinished)");
            }
        } catch (SQLException e) {
            showError("Could not reconcile the account:\n" + e.getMessage());
        }
        ledgerModel.reload();
        updateSummary(a);
        refreshTreeBalances();
    }

    // ----- Sync with phones (docs/SYNC-DESIGN.md) ---------------------------------

    private SyncEngine syncEngine;
    private javax.swing.Timer syncTimer;
    private boolean syncRunning;
    private boolean closing;                     // the window is closing: no more syncs touch the file
    /** The file's change count (Database.totalChanges) when the phones last got a snapshot; -1: never. */
    private long syncedChanges = -1;
    /** The change count of the sync in progress, kept as syncedChanges once its snapshot is written. */
    private long syncingChanges = -1;
    private final JLabel syncStatusLabel = new JLabel(" ");

    /** Syncs now (if it's set up), then every few minutes while the file is open. */
    private void setupSync() {
        syncEngine = new SyncEngine(db, attachments);
        syncTimer = new javax.swing.Timer(5 * 60_000, e -> syncNow(false));
        restartSyncTimer();
        SwingUtilities.invokeLater(() -> syncNow(false));
    }

    private void restartSyncTimer() {
        syncTimer.stop();
        try {
            SyncRepository.Settings s = new SyncRepository(db).settings();
            if (s.isSetUp()) {
                syncTimer.setDelay(s.minutes() * 60_000);
                syncTimer.setInitialDelay(s.minutes() * 60_000);
                syncTimer.start();
            } else {
                syncStatusLabel.setText(" ");
            }
        } catch (SQLException e) {
            // no automatic sync this time
        }
    }

    /** Sync > Settings... */
    private void syncSettings() {
        if (SyncDialog.show(this, db)) {
            restartSyncTimer();
            syncNow(false);
        }
    }

    /** True while a modal dialog of this window is open (an automatic sync waits for it). */
    private boolean dialogOpen() {
        for (java.awt.Window w : getOwnedWindows()) {
            if (w.isShowing() && w instanceof java.awt.Dialog d && d.isModal()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Syncs with the phones: the mailbox is read and written in the
     * background, and the phones' changes are applied here, on the window's
     * thread, as one undo step. manual: Sync > Sync Now (says how it went,
     * and offers the settings if sync isn't set up); otherwise it quietly
     * waits while a cell is being typed in or a dialog is open.
     */
    private void syncNow(boolean manual) {
        if (closing || syncEngine == null) {
            return;
        }
        if (syncRunning) {
            if (manual) {
                syncStatusLabel.setText("Syncing… (already)");
            }
            return;
        }
        if (!manual && (ledgerTable.isEditing() || dialogOpen())) {
            return;                                      // next time
        }
        SyncEngine.Plan plan;
        try {
            plan = syncEngine.plan();
        } catch (SQLException e) {
            syncFailed("Could not read the sync settings: " + e.getMessage(), manual);
            return;
        }
        if (plan == null) {
            if (manual && JOptionPane.showConfirmDialog(this, "Syncing with phones isn't set up for this file."
                    + " Set it up now?", "Sync Now", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                syncSettings();
            }
            return;
        }
        syncRunning = true;
        syncStatusLabel.setText("Syncing…");
        new javax.swing.SwingWorker<SyncEngine.Summary, Void>() {
            @Override
            protected SyncEngine.Summary doInBackground() throws Exception {
                SyncEngine.Fetched fetched = syncEngine.fetch(plan);
                SyncEngine.Outgoing[] out = new SyncEngine.Outgoing[1];
                Exception[] failed = new Exception[1];
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        out[0] = applySync(fetched, manual);
                    } catch (Exception e) {
                        failed[0] = e;
                    }
                });
                if (failed[0] != null) {
                    throw failed[0];
                }
                return out[0] == null ? null : syncEngine.publish(out[0]);
            }

            @Override
            protected void done() {
                syncRunning = false;
                if (closing) {
                    return;
                }
                try {
                    SyncEngine.Summary s = get();
                    if (s != null) {
                        syncedChanges = syncingChanges;  // the phones have all of it
                        syncEngine.remember(plan);
                        syncDone(s, manual);
                    } else {
                        syncStatusLabel.setText(" ");
                    }
                } catch (java.util.concurrent.ExecutionException | SQLException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    syncFailed(cause.getMessage(), manual);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /**
     * The window-thread part of a sync: applies the phones' changes (one undo
     * step) and makes their new snapshots. Null if it has to wait (closing, or
     * being typed in).
     */
    private SyncEngine.Outgoing applySync(SyncEngine.Fetched fetched, boolean manual) throws SQLException {
        if (closing || !isDisplayable()) {
            return null;
        }
        if (ledgerTable.isEditing()) {
            if (!manual || !ledgerTable.getCellEditor().stopCellEditing()) {
                return null;
            }
        }
        checkpoint("Change");                            // whatever came before stays its own undo step
        SyncEngine.Outgoing out = syncEngine.apply(fetched);
        syncingChanges = db.totalChanges();              // everything up to here goes in this snapshot
        SyncEngine.Summary s = out.summary();
        if (s.applied() + s.conflicts() > 0) {
            checkpoint("Sync from Phone");
            ledgerModel.reload();
            updateSummary(selectedAccount());
            refreshTreeBalances();
            refreshSummaryIfShown();
        }
        return out;
    }

    private void syncDone(SyncEngine.Summary s, boolean manual) {
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"));
        int fromPhones = s.applied() + s.conflicts();
        String text = "Synced " + time + (fromPhones > 0 ? " · " + fromPhones + " from phones" : "")
                + (s.rejected() > 0 ? " · " + s.rejected() + " refused" : "");
        syncStatusLabel.setText(s.ok() ? text : "⚠ " + text);
        syncStatusLabel.setToolTipText(s.ok() ? null : "<html>" + String.join("<br>", s.problems()) + "</html>");
        if (manual) {
            StringBuilder m = new StringBuilder("Synced with the phones.");
            if (fromPhones > 0) {
                m.append("\n\n").append(fromPhones).append(fromPhones == 1 ? " change" : " changes")
                        .append(" from phones applied").append(s.conflicts() > 0 ? " (" + s.conflicts()
                        + " changed here too; this computer's values were kept)" : "").append('.');
            }
            if (s.rejected() > 0) {
                m.append("\n").append(s.rejected()).append(" refused; the phone shows why.");
            }
            for (String p : s.problems()) {
                m.append("\n\n").append(p);
            }
            JOptionPane.showMessageDialog(this, m.toString(), "Sync Now",
                    s.ok() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        }
    }

    private void syncFailed(String why, boolean manual) {
        syncStatusLabel.setText("⚠ Sync failed");
        syncStatusLabel.setToolTipText(why);
        if (manual) {
            showError("Could not sync with the phones:\n" + why);
        }
    }

    /**
     * On closing: sends the phones a last snapshot of what changed here
     * (their own changes wait for next time), waiting up to 8 seconds. If
     * nothing changed since the last snapshot they got, there's nothing to
     * send, and it's skipped.
     */
    private void finalSync() {
        closing = true;
        if (syncTimer != null) {
            syncTimer.stop();
        }
        if (syncEngine == null) {
            return;
        }
        try {
            SyncEngine.Plan plan = syncEngine.plan();
            if (plan == null || plan.devices().isEmpty() || db.totalChanges() == syncedChanges) {
                return;                                  // not set up, no phones, or nothing new for them
            }
            SyncEngine.Outgoing out = syncEngine.apply(SyncEngine.Fetched.nothing(plan));
            Thread t = new Thread(() -> {
                try {
                    syncEngine.publish(out);
                } catch (java.io.IOException ignored) {
                    // the phones get it next time
                }
            }, "final sync");
            t.setDaemon(true);
            t.start();
            t.join(8_000);
        } catch (SQLException | InterruptedException ignored) {
            // the phones get it next time
        }
    }

    // ----- The window's size and place, kept between runs ------------------------

    /** The ledger's column widths for the look's own font, by column number (see scaled). */
    private static final int[] LEDGER_WIDTHS = {110, 60, 160, 160, 110, 150, 130, 62, 90, 90, 100, 30, 30, 90, 90, 120,
                                                110, 110};
    /** Never narrower than these (dates and amounts aren't squeezed), for the look's own font. */
    private static final Map<Integer, Integer> LEDGER_MIN_WIDTHS = Map.of(LedgerTableModel.DATE, 100,
            LedgerTableModel.TAG, 80, LedgerTableModel.PAYMENT, 80, LedgerTableModel.DEPOSIT, 80,
            LedgerTableModel.BALANCE, 90);

    /** A width for the look's own font, made to suit the font in use (larger text: wider). */
    private static int scaled(int width) {
        java.awt.Font now = javax.swing.UIManager.getFont("Table.font");
        float ratio = now == null ? 1f : now.getSize2D() / Theme.lookFont().getSize2D();
        return Math.round(width * Math.max(1f, ratio));
    }

    /** Its size and place when last not maximized (what restoring a maximized window goes back to). */
    private java.awt.Rectangle normalBounds;

    private void rememberNormalBounds() {
        if (isShowing() && (getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) == 0) {
            normalBounds = getBounds();
        }
    }

    /** Keeps the window's size, place and maximized state for next time. */
    private void saveWindow() {
        java.awt.Rectangle b = normalBounds != null ? normalBounds : getBounds();
        try {
            config.setString(AppConfig.WINDOW_BOUNDS, b.x + "," + b.y + "," + b.width + "," + b.height);
            config.setBoolean(AppConfig.WINDOW_MAXIMIZED, (getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) != 0);
            config.setInt(AppConfig.TREE_WIDTH, chosenTreeWidth);
            config.setBoolean(AppConfig.TREE_HIDDEN, !treeShown);
        } catch (SQLException e) {
            // it opens at the usual size next time
        }
    }

    /**
     * Puts the window where it was last time, at its size then (maximized if
     * it was); centered at the usual size if that's not known, or no longer
     * on any screen (say, a monitor was unplugged).
     */
    void restoreWindow() {
        java.awt.Rectangle b = parseBounds(config.getString(AppConfig.WINDOW_BOUNDS, ""));
        if (b != null && onScreen(b)) {
            setBounds(b);
            normalBounds = b;
        } else {
            setLocationRelativeTo(null);
        }
        if (config.getBoolean(AppConfig.WINDOW_MAXIMIZED, false)) {
            setExtendedState(getExtendedState() | java.awt.Frame.MAXIMIZED_BOTH);
        }
    }

    /** "x,y,width,height" as a rectangle, or null if it isn't one (or is too small to use). */
    static java.awt.Rectangle parseBounds(String text) {
        String[] p = text.split(",");
        if (p.length != 4) {
            return null;
        }
        try {
            java.awt.Rectangle r = new java.awt.Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            return r.width >= 400 && r.height >= 300 ? r : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True if enough of it is on some screen to grab its title bar (at least 200 by 50 pixels). */
    private static boolean onScreen(java.awt.Rectangle r) {
        for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            java.awt.Rectangle seen = d.getDefaultConfiguration().getBounds().intersection(r);
            if (seen.width >= 200 && seen.height >= 50) {
                return true;
            }
        }
        return false;
    }

    /**
     * The few labels drawn in a font of their own, made from the current
     * one (after a font or theme change, too): the account's name larger and
     * bold, the loan number smaller, Summary bold.
     */
    private void refreshFonts() {
        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) {
            return;
        }
        // The ledger's columns, as wide as the font needs (hidden ones too, for when they're shown).
        for (java.util.Map.Entry<Integer, javax.swing.table.TableColumn> c : ledgerColumns.entrySet()) {
            int column = c.getKey();
            if (column < LEDGER_WIDTHS.length) {
                c.getValue().setMinWidth(scaled(LEDGER_MIN_WIDTHS.getOrDefault(column, 15)));
                c.getValue().setPreferredWidth(scaled(LEDGER_WIDTHS[column]));
            }
        }
        accountNameLabel.setFont(base.deriveFont(java.awt.Font.BOLD, base.getSize2D() + 3));
        loanNumberLabel.setFont(base.deriveFont(base.getSize2D() - 2));
        summaryButton.setFont(javax.swing.UIManager.getFont("Button.font").deriveFont(java.awt.Font.BOLD));
    }

    @Override
    public void dispose() {
        closing = true;                                  // switching files: this one's sync stops
        if (syncTimer != null) {
            syncTimer.stop();
        }
        super.dispose();
    }

    /**
     * Cancel in Reconcile Account: removes the files attached since it was
     * started (they're on disk, so not in the undo log), then reverses every
     * other change since then, leaving no undo step for it.
     */
    private void cancelReconcile(Account a, java.util.Set<Long> attachmentsBefore) {
        try {
            for (AttachmentRepository.Attachment x : attachments.addedSince(a.id(), attachmentsBefore)) {
                attachments.remove(x, a);
            }
        } catch (SQLException | java.io.IOException e) {
            showError("Could not remove a file attached while reconciling:\n" + e.getMessage());
        }
        try {
            undoLog.revertToCheckpoint();
        } catch (SQLException e) {
            showError("Could not put everything back:\n" + e.getMessage()
                    + "\n\nNothing was changed; use Edit > Undo to reverse the reconcile instead.");
            checkpoint("Reconcile (unfinished)");
        }
        updateUndoMenu();
    }

    /**
     * File > Back Up Database...: an encrypted copy of the database in the
     * backup folder, named filename_YYYYMMDD_hhmm.MyMoney (see Backups). It
     * opens with the same password.
     */
    private void backupDatabase() {
        backUpNow();
    }

    /**
     * Makes a database backup (an encrypted copy of the data file) in the
     * backup folder (Preferences > Backups; asked for, and kept, if none is
     * set), then applies the retention settings to the older ones. Says where
     * it went, and that attachments aren't in it. Returns true if it was made.
     */
    private boolean backUpNow() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();   // save a half-finished cell first
        }
        resolveDraft(false);
        Path folder = backupFolder();
        if (folder == null) {
            return false;                                    // cancelled
        }
        Path target = Backups.target(db.getFile(), folder, java.time.LocalDateTime.now());
        try {
            Files.createDirectories(folder);
            db.backupTo(target);
        } catch (SQLException | IOException e) {
            showError("Could not back up the database:\n" + e.getMessage());
            return false;
        }
        try {
            config.setInt(AppConfig.BACKUP_USES_PREFIX + db.getFile(), 0);   // the reminder starts over
        } catch (SQLException ignored) {
            // the reminder may just come early
        }
        String removed = "";
        try {
            int n = Backups.prune(db.getFile(), folder, config.getInt(AppConfig.BACKUP_KEEP_PER_DAY, 0),
                    config.getInt(AppConfig.BACKUP_THIN_AFTER_DAYS, 0), LocalDate.now());
            if (n > 0) {
                removed = "\n\nRemoved " + n + (n == 1 ? " older backup" : " older backups")
                        + ", as set in Edit > Preferences > Backups.";
            }
        } catch (IOException e) {
            removed = "\n\nOlder backups couldn't be tidied up: " + e.getMessage();
        }
        JOptionPane.showMessageDialog(this, "Database backed up to:\n" + target
                + "\n\nIt opens with the same password as this file." + removed
                + "\n\n" + Backups.attachmentsNote(db.getFile().getParent()),
                "Database Backup", JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    /**
     * The folder backups go in: the one in Preferences, or, if none is set (or
     * it's gone), one chosen now, which is kept as the preference. Null if
     * cancelled.
     */
    private Path backupFolder() {
        String saved = config.getString(AppConfig.BACKUP_FOLDER, "");
        if (!saved.isBlank() && Files.isDirectory(Path.of(saved))) {
            return Path.of(saved);
        }
        if (!saved.isBlank()) {
            showError("The backup folder can't be found:\n" + saved + "\n\n(If it's on a drive that isn't"
                    + " plugged in, plug it in and try again.) Otherwise, choose another.");
        }
        Path folder = chooseBackupFolder(saved.isBlank() ? db.getFile().getParent().getParent() : Path.of(saved));
        if (folder != null) {
            try {
                config.setString(AppConfig.BACKUP_FOLDER, folder.toString());
            } catch (SQLException ignored) {
                // asked again next time
            }
        }
        return folder;
    }

    /** Asks for a folder for database backups, starting at start. Null if cancelled. */
    private Path chooseBackupFolder(Path start) {
        JFileChooser fc = new JFileChooser(start == null ? null : start.toFile());
        fc.setDialogTitle("Choose a Folder for Database Backups");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setApproveButtonText("Use This Folder");
        fc.setApproveButtonToolTipText("Database backups will go here (change it in Edit > Preferences > Backups)."
                + " Best on another drive.");
        return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile().toPath().toAbsolutePath()
                : null;
    }

    /** One more use of this file since its last backup (counted each time it's opened). */
    private void countUse() {
        String key = AppConfig.BACKUP_USES_PREFIX + db.getFile();
        try {
            config.setInt(key, config.getInt(key, 0) + 1);
        } catch (SQLException ignored) {
            // the reminder may just come late
        }
    }

    /**
     * Closing: if the file's been used often enough since its last backup
     * (Preferences > Backups), offers to back it up now, so the backup has
     * today's work in it.
     */
    private void remindToBackUp() {
        int every = config.getInt(AppConfig.BACKUP_REMIND_EVERY, AppConfig.DEFAULT_BACKUP_REMIND_EVERY);
        int uses = config.getInt(AppConfig.BACKUP_USES_PREFIX + db.getFile(), 0);
        if (every <= 0 || uses < every) {
            return;
        }
        Object[] options = {"Back Up Now", "Not Now"};
        int r = JOptionPane.showOptionDialog(this, "\"" + db.baseName() + "\" has been used " + uses
                + " times since its last database backup.\nBack it up now?\n\n(How often to ask: Edit > Preferences"
                + " > Backups.)", "Database Backup", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                options, options[0]);
        if (r == 0) {
            backUpNow();
        }
    }

    /**
     * File > Open...: picks another MyMoney file (or makes a new one) with the
     * same Open File dialog used at startup, then closes this file and shows
     * the other in a new window in the same place.
     */
    private void openFile() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();   // save a half-finished cell first
        }
        resolveDraft(false);

        StartupDialog sd = new StartupDialog(this, true, config);
        sd.useCancelButton();
        sd.setLocationRelativeTo(this);
        sd.setVisible(true);                            // modal: waits here until closed
        Database opened = sd.getDatabase();
        if (opened == null) {
            return;                                     // cancelled
        }
        if (opened.getFile().equals(db.getFile())) {
            opened.close();                             // already open here
            return;
        }
        db.close();
        MainFrame frame = new MainFrame(opened, config);
        frame.setBounds(getBounds());
        frame.setExtendedState(getExtendedState());
        dispose();                                      // not a window close: config stays open
        frame.setVisible(true);
    }

    /**
     * File > Rename File...: renames the data file, and its folder too when
     * the folder is named after it (as new files' are), attachments and all.
     * The file is closed and opened again under its new name, in a new
     * window (so Undo starts afresh).
     */
    private void renameFile() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        String current = db.baseName();
        String question = "New name for \"" + current + "\":"
                + (db.hasOwnFolder() ? "\n\nIts folder, \"" + current + "\", is renamed to match, with its attachments."
                        : "")
                + "\n(Undo starts afresh afterwards.)";
        String typed = current;
        Database.Renamed renamed;
        while (true) {
            Object answer = JOptionPane.showInputDialog(this, question, "Rename File", JOptionPane.PLAIN_MESSAGE,
                    null, null, typed);
            if (answer == null || answer.toString().trim().equals(current)) {
                return;                                  // cancelled, or the same name
            }
            typed = answer.toString();
            try {
                Path before = db.getFile();
                renamed = db.renameTo(typed);
                if (renamed.problem() == null) {
                    try {
                        config.renamed(before, renamed.database().getFile());
                    } catch (SQLException e) {
                        // the recent list just shows the old name until it's opened again
                    }
                }
                break;
            } catch (IllegalArgumentException e) {
                showError(e.getMessage());               // and ask again, with what was typed
            } catch (IllegalStateException e) {
                showError(e.getMessage() + "\n\nMyMoney will close; open the file again with File > Open.");
                dispose();
                System.exit(1);
                return;
            }
        }
        if (renamed.problem() != null) {
            showError("Could not rename the file, so it keeps its name:\n" + renamed.problem().getMessage()
                    + "\n\n(If it's open in another program, close it there and try again.)");
        }
        // The file was closed and opened again: a new window works with it.
        MainFrame frame = new MainFrame(renamed.database(), config);
        frame.setBounds(getBounds());
        frame.setExtendedState(getExtendedState());
        dispose();                                       // not a window close: config stays open
        frame.setVisible(true);
    }

    /**
     * File > Import...: what kind of import is decided by the file:
     *  - .qfx / .ofx: a bank download, matched against existing transactions.
     *  - .qif from Quicken (it names accounts or lists categories): the full
     *    import, every account and opening balance.
     *  - any other .qif (one account's transactions): a bank download.
     */
    private void importFile() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Import");
        FileNameExtensionFilter all = new FileNameExtensionFilter(
                "QIF, QFX and OFX files (*.qif, *.qfx, *.ofx)", "qif", "qfx", "ofx");
        fc.addChoosableFileFilter(all);
        fc.addChoosableFileFilter(new FileNameExtensionFilter("QIF files (*.qif)", "qif"));
        fc.addChoosableFileFilter(new FileNameExtensionFilter("QFX / OFX files (*.qfx, *.ofx)", "qfx", "ofx"));
        fc.setFileFilter(all);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = fc.getSelectedFile().toPath();
        String name = file.getFileName().toString().toLowerCase();
        try {
            if (name.endsWith(".qfx") || name.endsWith(".ofx")) {
                OfxParser.OfxFile f = OfxParser.parse(file);
                importBankDownload(file, f.transactions, f.warnings, BankImportDialog.FileAccount.of(f));
            } else if (name.endsWith(".qif")) {
                QifParser.QifFile f = QifParser.parse(file);
                if (f.isFullExport()) {
                    importQuicken(file, f);
                } else {
                    importBankDownload(file, DownloadImporter.fromQif(f), f.warnings, null);
                }
            } else {
                showError("MyMoney can import .qif, .qfx and .ofx files.");
            }
        } catch (IOException e) {
            showError("Could not read the file:\n" + e.getMessage());
        }
    }

    /**
     * A bank download (QIF or QFX): one account's transactions from the bank,
     * matched against what is already entered (see DownloadImporter).
     * fileAccount is the account the file names (QFX / OFX), or null.
     */
    private void importBankDownload(Path file, List<DownloadImporter.Download> downloads, List<String> warnings,
                                    BankImportDialog.FileAccount fileAccount) {
        if (downloads.isEmpty()) {
            showError("No transactions were found in this file."
                    + (warnings.isEmpty() ? "" : "\n\n" + String.join("\n", warnings)));
            return;
        }

        BankImportDialog dlg = new BankImportDialog(this, file.getFileName().toString(), downloads,
                fileAccount, accounts, selectedAccount(), config, this::createAccountFrom);
        dlg.setVisible(true);
        if (!dlg.wasAccepted()) {
            return;
        }
        Account target = dlg.account();
        DownloadImporter importer = new DownloadImporter(db);
        List<DownloadImporter.Download> fresh;
        try {
            fresh = importer.notYetImported(target.id(), downloads);
        } catch (SQLException e) {
            showError("Could not read the account's transactions:\n" + e.getMessage());
            return;
        }
        if (!warnings.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Some of the file couldn't be read:\n\n" + String.join("\n", warnings),
                    "Import", JOptionPane.WARNING_MESSAGE);
        }
        if (fresh.isEmpty()) {
            checkpoint("Import " + file.getFileName());  // an account number may have been saved
            if (dlg.madeAccount()) {
                loadAccounts(target.id());
            }
            JOptionPane.showMessageDialog(this, "Every transaction in this file was imported into \""
                    + target.name() + "\" before. There is nothing new to review.",
                    "Import", JOptionPane.INFORMATION_MESSAGE);
            afterImport(file, target, dlg.fileAction(), fileAccount != null);
            return;
        }
        DownloadReviewWindow review = new DownloadReviewWindow(this, file.getFileName().toString(), target, fresh,
                downloads.size() - fresh.size(), importer, dlg.match(), dlg.days());
        review.setVisible(true);
        checkpoint("Import " + file.getFileName());      // everything accepted or added is one undo step
        if (review.wasChanged() || dlg.madeAccount()) {
            loadAccounts(target.id());                   // show the account it went into
        }
        afterImport(file, target, dlg.fileAction(), fileAccount != null);
    }

    /**
     * What's done with a bank download file once it's imported, as chosen:
     * left where it is, deleted, or moved into MyMoney's folder as
     * <data folder>/imports/<Account>_import_<import date>.<ext> (see
     * ImportFileChoice.keptName). Moving a QFX/OFX
     * file asks first, since it holds the account number in plain text; if
     * not, it can be deleted or left instead.
     */
    private void afterImport(Path file, Account account, String action, boolean hasAccountNumber) {
        if (ImportFileChoice.MOVE.equals(action) && hasAccountNumber) {
            int r = JOptionPane.showConfirmDialog(this, "QFX and OFX files contain your account number in clear"
                    + " (unencrypted) text.\nMoving this one keeps that copy in MyMoney's folder:\n    "
                    + ImportFileChoice.importsFolder(db.getFile().getParent()) + "\n\nMove it there?", "After Importing",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                ImportFileChoice instead = new ImportFileChoice(ImportFileChoice.DELETE);
                instead.withoutMove();
                if (JOptionPane.showConfirmDialog(this, new Object[] {"Then the downloaded file is:", instead},
                        "After Importing", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                        != JOptionPane.OK_OPTION) {
                    return;                              // left where it is
                }
                action = instead.choice();
            }
        }
        try {
            if (ImportFileChoice.DELETE.equals(action)) {
                Files.deleteIfExists(file);
            } else if (ImportFileChoice.MOVE.equals(action)) {
                Path to = ImportFileChoice.keptName(db.getFile().getParent(), account.name(), LocalDate.now(),
                        file.getFileName().toString());          // imports/Checking_import_2026-10-20.qfx
                Files.createDirectories(to.getParent());
                Files.move(file, to);
            }
        } catch (IOException e) {
            showError("Could not " + (ImportFileChoice.DELETE.equals(action) ? "delete" : "move") + " \""
                    + file.getFileName() + "\":\n" + e.getMessage() + "\n\nIt's still where it was.");
        }
    }

    /**
     * Opens Manage Accounts on a new account filled in from a template (such
     * as an imported file's account number). Returns the id of the account
     * saved, or null if none was.
     */
    private Long createAccountFrom(AccountRepository.Details template) {
        ManageAccountsDialog dlg = new ManageAccountsDialog(this, accounts, attachments,
                ManageAccountsDialog.categoriesOnly(categoryPaths()), db.getFile(), selectedAccount(), template);
        dlg.setVisible(true);
        if (!dlg.wasChanged()) {
            return null;
        }
        checkpoint("New Account");
        loadAccounts(dlg.lastSavedId());
        return dlg.lastSavedId();
    }

    /** A Quicken QIF export: every account in it, creating any that don't exist yet. */
    private void importQuicken(Path file, QifParser.QifFile qif) {
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
            selectFirstAccount();
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

    /** Edit menu / right-click: moves the selected transactions' memos into their descriptions. */
    /**
     * Edit > Clear Downloaded Flag: the selected transactions no longer show
     * as downloaded (matched or new), and importing their file again offers
     * those downloads again. Status, payee and memo stay as they are.
     */
    /**
     * Edit > Edit Transactions...: one window to change any of the selected
     * transactions' fields at once (see BulkEditDialog), as one undo step.
     */
    private void editTransactions() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        int[] rows = selectedTransactionRows();
        List<LedgerEntry> selected = new ArrayList<>();
        for (int row : rows) {
            LedgerEntry e = ledgerModel.entryCopy(row);
            if (e != null) {
                selected.add(e);
            }
        }
        if (selected.isEmpty()) {
            showError("Select one or more transactions first.\n(Ctrl+click or Shift+click to select several.)");
            return;
        }
        Map<Integer, Object> values = BulkEditDialog.ask(this, selected, payeeNames(), categoryPaths(), tagNames(),
                typeNames());
        if (values == null) {
            return;                                      // cancelled
        }
        String note = ledgerModel.bulkSet(rows, values);
        if (note != null) {
            JOptionPane.showMessageDialog(this, note, "Edit Transactions", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Edit > Move to Account...: moves the selected transactions to another
     * account (see TransactionRepository.moveToAccount), as one undo step.
     */
    private void moveToAccount() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        resolveDraft(false);
        Account from = ledgerModel.currentAccount();
        List<Long> ids = new ArrayList<>();
        for (int row : selectedTransactionRows()) {
            long id = ledgerModel.transactionId(row);
            if (id != 0) {
                ids.add(id);
            }
        }
        if (from == null || ids.isEmpty()) {
            showError("Select one or more transactions first.\n(Ctrl+click or Shift+click to select several.)");
            return;
        }
        JComboBox<Account> to = new JComboBox<>();
        try {
            for (Account a : accounts.findAll()) {
                if (a.id() != from.id()) {
                    to.addItem(a);
                }
            }
        } catch (SQLException e) {
            showError("Could not read the accounts:\n" + e.getMessage());
            return;
        }
        if (to.getItemCount() == 0) {
            showError("There's no other account to move them to. Add one first (Account > New Account).");
            return;
        }
        String what = ids.size() == 1 ? "this transaction" : "these " + ids.size() + " transactions";
        if (JOptionPane.showConfirmDialog(this, new Object[] {"Move " + what + " from \"" + from.name() + "\" to:", to,
                "<html><div style='width:360px'>A transfer stays linked with its other account. Attachments move"
                + " too. Reconciled transactions aren't moved.</div></html>"},
                "Move to Account", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Account target = (Account) to.getSelectedItem();
        TransactionRepository.Moved result;
        try {
            result = transactionRepo.moveToAccount(ids, target.id());
        } catch (SQLException e) {
            showError("Could not move the transactions; nothing was changed:\n" + e.getMessage());
            return;
        }
        if (result.moved() > 0) {
            checkpoint("Move to " + target.name());
        }
        ledgerModel.reload();                            // they're gone from this ledger
        refreshSummaryIfShown();
        updateSummary(selectedAccount());
        refreshTreeBalances();
        if (!result.skipped().isEmpty()) {
            JOptionPane.showMessageDialog(this, (result.moved() == 0 ? "Nothing was moved:\n\n"
                    : result.moved() + (result.moved() == 1 ? " moved; this one wasn't" : " moved; these weren't")
                    + ":\n\n") + String.join("\n", result.skipped()), "Move to Account",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void clearDownloadedFlag() {
        int[] rows = selectedTransactionRows();
        if (rows.length == 0) {
            showError("Select one or more transactions first.\n"
                    + "(Ctrl+click or Shift+click to select several.)");
            return;
        }
        int n = ledgerModel.countDownloaded(rows);
        if (n == 0) {
            JOptionPane.showMessageDialog(this, rows.length == 1 ? "This transaction isn't marked as downloaded."
                    : "None of the selected transactions is marked as downloaded.", "MyMoney",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!confirm("Clear the downloaded flag on " + (n == 1 ? "this transaction" : n + " transactions") + "?\n\n"
                + "Importing the bank file again will offer " + (n == 1 ? "its download" : "their downloads")
                + " again. Status, payee and memo stay as they are.")) {
            return;
        }
        ledgerModel.clearDownloaded(rows);
    }

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

    /** The category a payee had last time, or null. */
    private String lastCategoryForPayee(String payee, long accountId) {
        try {
            return transactionRepo.lastCategoryForPayee(payee, accountId);
        } catch (SQLException e) {
            return null;
        }
    }

    private List<String> payeeNames() {
        try {
            return transactionRepo.findPayeeNames();
        } catch (SQLException e) {
            return List.of();
        }
    }

    private void showError(String message) {
        Splash.pause();                                  // (at startup) so the message isn't hidden behind it
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

        // A heading for each account type (in the type list's order), holding its
        // top-level accounts by name; envelopes go under their parent account.
        Map<AccountType, DefaultMutableTreeNode> groups = new java.util.EnumMap<>(AccountType.class);
        for (AccountType t : AccountType.values()) {
            groups.put(t, new DefaultMutableTreeNode(new TypeGroup(t)));
        }
        for (Account a : all) {
            if (a.parentId() == null) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(a);
                nodes.put(a.id(), node);
                groups.get(a.type()).add(node);
            }
        }
        for (Account a : all) {
            if (a.parentId() != null) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(a);
                nodes.put(a.id(), node);
                DefaultMutableTreeNode parent = nodes.get(a.parentId());
                (parent != null ? parent : groups.get(AccountType.ENVELOPE)).add(node);   // orphans: Envelopes
            }
        }
        for (AccountType t : AccountType.values()) {
            if (groups.get(t).getChildCount() > 0) {
                root.add(groups.get(t));                // only types that have accounts
            }
        }

        readTreeBalances();
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
        refreshSummaryIfShown();
    }

    // ----- Account tree balances --------------------------------------------

    /**
     * Draws each account as its name, followed (if that preference is on) by
     * its cleared balance, or for an envelope its current balance.
     */
    private void setupTreeRenderer() {
        javax.swing.ToolTipManager.sharedInstance().registerComponent(accountTree);
        // Type headings can't be selected: clicking one leaves the current account showing.
        accountTree.setSelectionModel(new javax.swing.tree.DefaultTreeSelectionModel() {
            @Override
            public void setSelectionPaths(TreePath[] paths) {
                TreePath[] accountsOnly = withoutHeadings(paths);
                if (paths == null || paths.length == 0 || accountsOnly.length > 0) {
                    super.setSelectionPaths(accountsOnly);
                }
            }

            @Override
            public void addSelectionPaths(TreePath[] paths) {
                super.addSelectionPaths(withoutHeadings(paths));
            }

            private TreePath[] withoutHeadings(TreePath[] paths) {
                if (paths == null) {
                    return null;
                }
                return java.util.Arrays.stream(paths).filter(p -> !(p.getLastPathComponent()
                        instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof TypeGroup))
                        .toArray(TreePath[]::new);
            }
        });
        accountTree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        accountTree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override
            public java.awt.Component getTreeCellRendererComponent(javax.swing.JTree tree, Object value,
                    boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
                setToolTipText(null);
                if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Account a) {
                    setIcon(AccountIcon.of(a));         // its type's icon; stacked for a sub-account
                } else if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof TypeGroup g) {
                    setIcon(null);                      // a type heading: bold, no icon
                    setText("<html><b>" + g + "</b></html>");
                }
                if (showTreeBalances && value instanceof DefaultMutableTreeNode n
                        && n.getUserObject() instanceof Account a && treeBalances.containsKey(a.id())) {
                    setToolTipText(a.type() == AccountType.ENVELOPE
                            ? "Current balance (transactions through today)" : "Cleared balance");
                }
                return this;
            }
        });

        // The balances are drawn over the tree, right-aligned at its right edge (a tree row is
        // only as wide as its text, so it can't right-align part of it). The tree is never
        // narrower than its names and balances need: when the pane is, the ledger covers its
        // right side (the balances, then the names) rather than balances running over names.
        accountScroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        accountScroll.setMinimumSize(new java.awt.Dimension(0, 0));
        accountScroll.setViewportView(new javax.swing.JLayer<>(accountTree, new javax.swing.plaf.LayerUI<>() {
            @Override
            public void paint(java.awt.Graphics g, JComponent c) {
                super.paint(g, c);
                if (showTreeBalances) {
                    paintTreeBalances((java.awt.Graphics2D) g, c.getVisibleRect(), c.getWidth());
                }
            }

            @Override
            public java.awt.Dimension getPreferredSize(JComponent c) {
                // At least as wide as the names and balances need (the pane fills any extra).
                java.awt.Dimension d = super.getPreferredSize(c);
                return new java.awt.Dimension(Math.max(d.width, treeContentWidth()), d.height);
            }
        }));
    }

    /** Space between a balance and the tree's right edge. */
    private static final int TREE_BALANCE_MARGIN = 6;

    /** Draws each visible account's balance at the tree's right edge (width: the tree's width). */
    private void paintTreeBalances(java.awt.Graphics2D g, java.awt.Rectangle visible, int width) {
        g.setFont(accountTree.getFont());
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        java.awt.FontMetrics fm = g.getFontMetrics();
        int right = width - TREE_BALANCE_MARGIN;
        for (int row = 0; row < accountTree.getRowCount(); row++) {
            java.awt.Rectangle r = accountTree.getRowBounds(row);
            if (r == null || r.y + r.height < visible.y || r.y > visible.y + visible.height) {
                continue;
            }
            Long amount = treeAmount(row);
            if (amount == null) {
                continue;
            }
            long cents = amount;
            boolean heading = isHeadingRow(row);
            g.setFont(heading ? accountTree.getFont().deriveFont(java.awt.Font.BOLD) : accountTree.getFont());
            fm = g.getFontMetrics();
            String text = Money.format(cents);
            if (accountTree.isRowSelected(row)) {          // on the selection highlight: its text color
                java.awt.Color sel = javax.swing.UIManager.getColor("Tree.selectionForeground");
                g.setColor(sel != null ? sel : java.awt.Color.WHITE);
            } else {
                g.setColor(cents < 0 ? NEGATIVE : java.awt.Color.GRAY);
            }
            int baseline = r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(text, right - fm.stringWidth(text), baseline);
        }
    }

    /** Space kept between an account's name and its balance. */
    private static final int TREE_BALANCE_GAP = 12;

    /**
     * The narrowest the tree pane can be without a balance running into its
     * account's name: for each row, where its name ends plus the gap and the
     * balance, plus the pane's border and room for a scroll bar. With balances
     * hidden, there is no limit beyond the usual minimum.
     */
    private int treeMinimumWidth() {
        if (!showTreeBalances) {
            return TREE_MIN_WIDTH;
        }
        java.awt.Insets in = accountScroll.getInsets();
        return Math.max(TREE_MIN_WIDTH, treeContentWidth() + in.left + in.right
                + accountScroll.getVerticalScrollBar().getPreferredSize().width);
    }

    /** How wide the tree must be for every name and its balance to show without touching (0 without balances). */
    private int treeContentWidth() {
        if (!showTreeBalances) {
            return 0;
        }
        int need = 0;
        for (int row = 0; row < accountTree.getRowCount(); row++) {
            java.awt.Rectangle r = accountTree.getRowBounds(row);
            Long amount = treeAmount(row);
            if (r != null && amount != null) {
                need = Math.max(need, r.x + r.width + TREE_BALANCE_GAP
                        + amountWidth(row, amount) + TREE_BALANCE_MARGIN);
            }
        }
        return need;
    }

    /** After the balances change: the tree may need to be wider (see treeContentWidth). */
    private void updateTreeMinimumWidth() {
        accountScroll.getViewport().revalidate();
        accountScroll.repaint();
    }

    /** The widest balance in the tree, in pixels (0 if balances aren't shown). */
    private int widestTreeBalance() {
        if (!showTreeBalances) {
            return 0;
        }
        int w = 0;
        for (int row = 0; row < accountTree.getRowCount(); row++) {
            Long amount = treeAmount(row);
            if (amount != null) {
                w = Math.max(w, amountWidth(row, amount));
            }
        }
        return w;
    }

    /**
     * The amount shown at the right of a tree row: an account's balance, or
     * for a type heading the total of every account under it (envelopes
     * too). Null if there is none to show.
     */
    private Long treeAmount(int row) {
        Object node = accountTree.getPathForRow(row).getLastPathComponent();
        if (!(node instanceof DefaultMutableTreeNode n)) {
            return null;
        }
        if (n.getUserObject() instanceof Account a) {
            return treeBalances.get(a.id());
        }
        if (!(n.getUserObject() instanceof TypeGroup) || !showGroupTotals) {
            return null;
        }
        long total = 0;
        boolean any = false;
        java.util.Enumeration<javax.swing.tree.TreeNode> e = n.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            if (e.nextElement() instanceof DefaultMutableTreeNode c && c.getUserObject() instanceof Account a
                    && treeBalances.containsKey(a.id())) {
                total += treeBalances.get(a.id());
                any = true;
            }
        }
        return any ? total : null;
    }

    private boolean isHeadingRow(int row) {
        return accountTree.getPathForRow(row).getLastPathComponent() instanceof DefaultMutableTreeNode n
                && n.getUserObject() instanceof TypeGroup;
    }

    /** How wide a row's amount is drawn (headings' totals are bold). */
    private int amountWidth(int row, long cents) {
        java.awt.Font f = isHeadingRow(row) ? accountTree.getFont().deriveFont(java.awt.Font.BOLD) : accountTree.getFont();
        return accountTree.getFontMetrics(f).stringWidth(Money.format(cents));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void readTreeBalances() {
        if (!showTreeBalances) {
            return;
        }
        try {
            treeBalances = accounts.treeBalances();
        } catch (SQLException e) {
            treeBalances = new HashMap<>();             // just names, then
        }
    }

    /** Re-reads the balances and redraws the tree (row widths change with the numbers). */
    private void refreshTreeBalances() {
        if (!showTreeBalances) {
            return;
        }
        readTreeBalances();
        updateTreeMinimumWidth();                        // a balance may have grown
        accountScroll.getViewport().repaint();           // the balances are drawn over the tree
    }

    /** Edit > Preferences...: application settings, saved for next time. */
    private void editPreferences() {
        javax.swing.JCheckBox balances = new javax.swing.JCheckBox(
                "Show balances in the account tree (cleared; current for envelopes; remaining for loans)", showTreeBalances);
        javax.swing.JCheckBox groupTotals = new javax.swing.JCheckBox(
                "Show totals on the account type headings", showGroupTotals);
        groupTotals.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 0));   // indented under balances
        groupTotals.setEnabled(balances.isSelected());
        balances.addActionListener(e -> groupTotals.setEnabled(balances.isSelected()));
        javax.swing.JCheckBox match = new javax.swing.JCheckBox("Match existing transactions",
                config.getBoolean(AppConfig.MATCH_DOWNLOADS, true));
        javax.swing.JSpinner days = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                config.getInt(AppConfig.MATCH_DAYS, AppConfig.DEFAULT_MATCH_DAYS), 0, 60, 1));
        days.setEnabled(match.isSelected());
        match.addActionListener(e -> days.setEnabled(match.isSelected()));
        JPanel matchRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        matchRow.add(match);
        matchRow.add(new JLabel("within"));
        matchRow.add(days);
        matchRow.add(new JLabel("days"));
        JLabel importHeading = new JLabel("Bank download imports:");
        importHeading.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        int oldScheduleDays = config.getInt(AppConfig.SCHEDULE_DAYS, AppConfig.DEFAULT_SCHEDULE_DAYS);
        javax.swing.JSpinner scheduleDays = new javax.swing.JSpinner(
                new javax.swing.SpinnerNumberModel(oldScheduleDays, 0, 365, 1));
        JPanel scheduleRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        scheduleRow.add(new JLabel("Put them in the ledger"));
        scheduleRow.add(scheduleDays);
        scheduleRow.add(new JLabel("days ahead"));
        JLabel editHeading = new JLabel("Editing the ledger: Enter");
        editHeading.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        javax.swing.JRadioButton enterDown = new javax.swing.JRadioButton(
                "moves down to the next transaction", !enterNextField);
        javax.swing.JRadioButton enterRight = new javax.swing.JRadioButton(
                "moves to the next field, like Tab", enterNextField);
        javax.swing.ButtonGroup enterGroup = new javax.swing.ButtonGroup();
        enterGroup.add(enterDown);
        enterGroup.add(enterRight);
        javax.swing.JCheckBox autoDecimal = new javax.swing.JCheckBox("Amounts: insert the decimal point"
                + " automatically (123 enters $1.23; 12 enters $0.12; 1 enters $0.01; 12. enters $12.00)",
                config.getBoolean(AppConfig.AUTO_DECIMAL, false));
        autoDecimal.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        javax.swing.JCheckBox openOnSummary = new javax.swing.JCheckBox(
                "Open on the Summary screen (instead of the first account)",
                config.getBoolean(AppConfig.OPEN_ON_SUMMARY, false));
        JComboBox<String> dateFormat = new JComboBox<>();
        for (String f : LedgerTableModel.DATE_FORMATS) {
            dateFormat.addItem(f.toUpperCase() + "   (today: " + LocalDate.now().format(
                    java.time.format.DateTimeFormatter.ofPattern(f)) + ")");
        }
        String currentFormat = config.getString(AppConfig.DATE_FORMAT, LedgerTableModel.DATE_FORMATS[0]);
        dateFormat.setSelectedIndex(Math.max(0, java.util.Arrays.asList(LedgerTableModel.DATE_FORMATS)
                .indexOf(currentFormat)));
        Theme.Choice oldTheme = Theme.Choice.of(config.getString(AppConfig.THEME, "system"));
        JComboBox<Theme.Choice> theme = new JComboBox<>(Theme.Choice.values());
        theme.setSelectedItem(oldTheme);
        JPanel themeRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        themeRow.add(new JLabel("Theme:"));
        themeRow.add(theme);
        themeRow.add(new JLabel("(Match the system: light or dark, as the desktop is set when MyMoney starts)"));

        // The font everything is drawn in, and its size; Default = the look's own.
        java.awt.Font lookFont = Theme.lookFont();
        String oldFamily = config.getString(AppConfig.FONT_FAMILY, "");
        int oldSize = config.getInt(AppConfig.FONT_SIZE, 0);
        String defaultFamily = "Default (" + lookFont.getFamily() + ")";
        JComboBox<String> fontFamily = new JComboBox<>();
        fontFamily.addItem(defaultFamily);
        for (String f : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            fontFamily.addItem(f);
        }
        fontFamily.setSelectedItem(oldFamily.isEmpty() ? defaultFamily : oldFamily);
        fontFamily.setMaximumRowCount(20);
        javax.swing.JSpinner fontSize = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                oldSize > 0 ? oldSize : lookFont.getSize(), 8, 32, 1));
        JLabel fontSample = new JLabel("Sample: Walmart  Food:Groceries  $1,234.56");
        Runnable showSample = () -> {
            String fam = fontFamily.getSelectedIndex() == 0 ? lookFont.getFamily() : (String) fontFamily.getSelectedItem();
            fontSample.setFont(new java.awt.Font(fam, java.awt.Font.PLAIN, (Integer) fontSize.getValue()));
        };
        fontFamily.addActionListener(e -> showSample.run());
        fontSize.addChangeListener(e -> showSample.run());
        showSample.run();
        JButton fontDefault = new JButton("Default");
        fontDefault.addActionListener(e -> {
            fontFamily.setSelectedIndex(0);
            fontSize.setValue(lookFont.getSize());
        });
        JPanel fontRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        fontRow.add(new JLabel("Font:"));
        fontRow.add(fontFamily);
        fontRow.add(new JLabel("Size:"));
        fontRow.add(fontSize);
        fontRow.add(fontDefault);
        JPanel fontSampleRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        fontSampleRow.add(fontSample);
        JPanel dateRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        dateRow.add(new JLabel("Dates:"));
        dateRow.add(dateFormat);
        dateRow.add(new JLabel("(8 digits typed in a date field become a date in this format)"));
        JLabel scheduleHeading = new JLabel("Scheduled transactions:");
        scheduleHeading.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        // Imports: what's done with a bank download file once it's imported.
        JLabel afterHeading = new JLabel("After a bank download is imported, its file is:");
        afterHeading.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        ImportFileChoice afterImport = new ImportFileChoice(config.getString(AppConfig.IMPORT_FILE_ACTION, "leave"));

        // Backups.
        javax.swing.JTextField backupField = new javax.swing.JTextField(config.getString(AppConfig.BACKUP_FOLDER, ""), 28);
        backupField.setToolTipText("Database backups go here. Best on another drive. Blank: asked the first time.");
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> {
            Path chosen = chooseBackupFolder(backupField.getText().isBlank() ? db.getFile().getParent().getParent()
                    : Path.of(backupField.getText().trim()));
            if (chosen != null) {
                backupField.setText(chosen.toString());
            }
        });
        JPanel folderRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        folderRow.add(new JLabel("Back up the database to:"));
        folderRow.add(backupField);
        folderRow.add(browse);
        javax.swing.JSpinner remindEvery = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                config.getInt(AppConfig.BACKUP_REMIND_EVERY, AppConfig.DEFAULT_BACKUP_REMIND_EVERY), 0, 999, 1));
        JPanel remindRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        remindRow.add(new JLabel("When closing, offer a backup after a file's been used"));
        remindRow.add(remindEvery);
        remindRow.add(new JLabel("times since its last one (0: never)"));
        javax.swing.JSpinner keepPerDay = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                config.getInt(AppConfig.BACKUP_KEEP_PER_DAY, 0), 0, 99, 1));
        JPanel keepRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        keepRow.add(new JLabel("Keep up to"));
        keepRow.add(keepPerDay);
        keepRow.add(new JLabel("backups a day, the latest (0: all of them)"));
        javax.swing.JSpinner thinAfter = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                config.getInt(AppConfig.BACKUP_THIN_AFTER_DAYS, 0), 0, 3650, 1));
        JPanel thinRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        thinRow.add(new JLabel("After"));
        thinRow.add(thinAfter);
        thinRow.add(new JLabel("days, keep only the last backup of each day (0: keep them all)"));
        JLabel retentionHeading = new JLabel("Old backups (this file's, in that folder; nothing else there is touched):");
        retentionHeading.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        JLabel backupNote = new JLabel("<html><div style='width:520px'>"
                + Backups.attachmentsNote(db.getFile().getParent()).replace("\n", "<br>") + "</div></html>");
        backupNote.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        tabs.addTab("General", preferencesTab(themeRow, fontRow, fontSampleRow, openOnSummary, dateRow, balances, groupTotals));
        tabs.addTab("Ledger", preferencesTab(editHeading, enterDown, enterRight, autoDecimal, scheduleHeading,
                scheduleRow));
        tabs.addTab("Imports", preferencesTab(importHeading, matchRow, afterHeading, afterImport));
        tabs.addTab("Backups", preferencesTab(folderRow, remindRow, retentionHeading, keepRow, thinRow, backupNote));

        // Paychecks (this file's): on or off, which one to go by, and the Summary's period.
        Paychecks.Settings paySettings;
        List<ScheduleRepository.Schedule> payList;
        try {
            paySettings = paychecks.settings();
            payList = paychecks.paychecks();
        } catch (SQLException e) {
            paySettings = Paychecks.Settings.OFF;
            payList = List.of();
        }
        javax.swing.JCheckBox payOn = new javax.swing.JCheckBox(
                "Show what's due between paychecks (on the Summary screen, and Show: on the ledger)", paySettings.enabled());
        JComboBox<String> payWhich = new JComboBox<>();
        List<Integer> payRanks = new ArrayList<>();
        for (ScheduleRepository.Schedule p : payList) {
            payWhich.addItem(Paychecks.rankName(p.paycheck()) + ": " + (p.payee() == null ? "(no payee)" : p.payee())
                    + ", " + p.describeAmount() + ", " + p.recurrence().describe().toLowerCase());
            payRanks.add(p.paycheck());
        }
        if (payList.isEmpty()) {
            payWhich.addItem("No paychecks yet");
            payWhich.setEnabled(false);
        } else {
            payWhich.setSelectedIndex(Math.max(0, payRanks.indexOf(paySettings.rank())));
        }
        JPanel payWhichRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        payWhichRow.add(new JLabel("Go by:"));
        payWhichRow.add(payWhich);
        javax.swing.JRadioButton payUntil = new javax.swing.JRadioButton(Paychecks.Mode.UNTIL_NEXT.label
                + " (from today)", paySettings.mode() == Paychecks.Mode.UNTIL_NEXT);
        javax.swing.JRadioButton payPeriodButton = new javax.swing.JRadioButton(Paychecks.Mode.NEXT_PERIOD.label
                + " (from that payday to the next)", paySettings.mode() == Paychecks.Mode.NEXT_PERIOD);
        javax.swing.ButtonGroup payModes = new javax.swing.ButtonGroup();
        payModes.add(payUntil);
        payModes.add(payPeriodButton);
        JLabel payModeHeading = new JLabel("The Summary screen starts on:");
        payModeHeading.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        JLabel payHint = new JLabel("<html><div style='width:520px'>Mark a scheduled deposit as a paycheck (main,"
                + " secondary, tertiary, ...) with its <b>Paycheck</b> choice, in Account &gt; Scheduled Transactions."
                + " Upcoming means not yet cleared: scheduled, pending, paid, or entered with no status."
                + " These settings belong to this file.</div></html>");
        payHint.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        Runnable payEnable = () -> {
            payWhich.setEnabled(payOn.isSelected() && !payRanks.isEmpty());
            payUntil.setEnabled(payOn.isSelected());
            payPeriodButton.setEnabled(payOn.isSelected());
        };
        payOn.addActionListener(e -> payEnable.run());
        payEnable.run();
        tabs.addTab("Paychecks", preferencesTab(payOn, payWhichRow, payModeHeading, payUntil, payPeriodButton, payHint));
        int r = JOptionPane.showConfirmDialog(this, tabs, "Preferences", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            paychecks.save(new Paychecks.Settings(payOn.isSelected(),
                    payRanks.isEmpty() ? paySettings.rank() : payRanks.get(Math.max(0, payWhich.getSelectedIndex())),
                    payPeriodButton.isSelected() ? Paychecks.Mode.NEXT_PERIOD : Paychecks.Mode.UNTIL_NEXT));
        } catch (SQLException e) {
            showError("Could not save the paycheck settings:\n" + e.getMessage());
        }
        createScheduledTransactions(true);               // paychecks on: bills through the next pay period
        refreshPaycheckControls();
        String folderText = backupField.getText().trim();
        if (!folderText.isEmpty() && !Files.isDirectory(Path.of(folderText))) {
            showError("The backup folder doesn't exist:\n" + folderText + "\n\nIt's kept anyway; backups will ask for"
                    + " another if it still can't be found then.");
        }
        try {
            config.setBoolean(AppConfig.MATCH_DOWNLOADS, match.isSelected());
            config.setInt(AppConfig.MATCH_DAYS, (Integer) days.getValue());
            config.setBoolean(AppConfig.SHOW_TREE_BALANCES, balances.isSelected());
            config.setBoolean(AppConfig.SHOW_TREE_GROUP_TOTALS, groupTotals.isSelected());
            config.setBoolean(AppConfig.ENTER_NEXT_FIELD, enterRight.isSelected());
            config.setBoolean(AppConfig.AUTO_DECIMAL, autoDecimal.isSelected());
            config.setBoolean(AppConfig.OPEN_ON_SUMMARY, openOnSummary.isSelected());
            config.setString(AppConfig.DATE_FORMAT, LedgerTableModel.DATE_FORMATS[dateFormat.getSelectedIndex()]);
            config.setString(AppConfig.THEME, ((Theme.Choice) theme.getSelectedItem()).key);
            config.setString(AppConfig.FONT_FAMILY, fontFamily.getSelectedIndex() == 0 ? "" : (String) fontFamily.getSelectedItem());
            int size = (Integer) fontSize.getValue();
            config.setInt(AppConfig.FONT_SIZE, size == lookFont.getSize() ? 0 : size);
            config.setInt(AppConfig.SCHEDULE_DAYS, (Integer) scheduleDays.getValue());
            config.setString(AppConfig.IMPORT_FILE_ACTION, afterImport.choice());
            config.setString(AppConfig.BACKUP_FOLDER, folderText);
            config.setInt(AppConfig.BACKUP_REMIND_EVERY, (Integer) remindEvery.getValue());
            config.setInt(AppConfig.BACKUP_KEEP_PER_DAY, (Integer) keepPerDay.getValue());
            config.setInt(AppConfig.BACKUP_THIN_AFTER_DAYS, (Integer) thinAfter.getValue());
        } catch (SQLException e) {
            showError("The preferences apply now, but could not be saved for next time:\n" + e.getMessage());
        }
        enterNextField = enterRight.isSelected();
        String newFamily = fontFamily.getSelectedIndex() == 0 ? "" : (String) fontFamily.getSelectedItem();
        int newSize = (Integer) fontSize.getValue() == lookFont.getSize() ? 0 : (Integer) fontSize.getValue();
        if (theme.getSelectedItem() != oldTheme || !newFamily.equals(oldFamily) || newSize != oldSize) {
            Theme.setFont(newFamily, newSize);
            Theme.switchTo((Theme.Choice) theme.getSelectedItem());   // every open window, now
            refreshFonts();
            fitTreeWidth();
        }
        Money.setAutoDecimal(autoDecimal.isSelected());
        String newFormat = LedgerTableModel.DATE_FORMATS[dateFormat.getSelectedIndex()];
        if (!newFormat.equals(currentFormat)) {
            LedgerTableModel.setDateFormat(newFormat);
            ledgerModel.reload();                        // show the dates the new way
            updateSummary(selectedAccount());
            refreshSummaryIfShown();
        }
        if ((Integer) scheduleDays.getValue() != oldScheduleDays) {
            createScheduledTransactions(true);           // further ahead: the extra ones now; fewer: the untouched extras go
        }
        if (balances.isSelected() == showTreeBalances && groupTotals.isSelected() == showGroupTotals) {
            return;
        }
        showTreeBalances = balances.isSelected();
        showGroupTotals = groupTotals.isSelected();
        Account selected = selectedAccount();
        loadAccounts(selected == null ? null : selected.id());   // redraws the tree and fits its width
    }

    /** One tab of Preferences: these, one under another, at the top left. */
    private static JPanel preferencesTab(java.awt.Component... rows) {
        JPanel tab = new JPanel();
        tab.setLayout(new BoxLayout(tab, BoxLayout.PAGE_AXIS));
        tab.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        for (java.awt.Component c : rows) {
            if (c instanceof JComponent j) {
                j.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
                j.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, j.getPreferredSize().height));   // no stretching
            }
            tab.add(c);
        }
        tab.add(javax.swing.Box.createVerticalGlue());
        return tab;
    }

    // ----- The Summary screen -------------------------------------------------

    private final JPanel rightCards = new JPanel(new java.awt.CardLayout());
    private final JButton summaryButton = new JButton("Summary");
    private SummaryPanel summaryView;
    private boolean showingSummary;

    /**
     * Puts Summary above the account tree. Clicking it shows the Summary
     * screen (net worth and widgets) where the ledger is; picking an account
     * in the tree shows its ledger again.
     */
    private void setupSummary() {
        summaryView = new SummaryPanel(accounts, this::selectAccountInTree);
        paydayWidget = new PaydayWidget(paychecks, accounts, this::selectAccountInTree);
        summaryView.addWidget(paydayWidget, paydayWidget::refresh);
        javax.swing.JScrollPane summaryScroll = new javax.swing.JScrollPane(summaryView);
        summaryScroll.setBorder(null);
        summaryScroll.getVerticalScrollBar().setUnitIncrement(16);
        rightCards.add(rightSplit, "ledger");
        rightCards.add(summaryScroll, "summary");
        int divider = mainSplit.getDividerLocation();
        mainSplit.setRightComponent(rightCards);

        summaryButton.setHorizontalAlignment(SwingConstants.LEADING);
        summaryButton.setToolTipText("Net worth and every account's balance at a glance");
        summaryButton.setFocusable(false);
        summaryButton.addActionListener(e -> showSummary());
        JPanel left = new JPanel(new java.awt.BorderLayout(0, 4));
        left.add(summaryButton, java.awt.BorderLayout.NORTH);
        left.add(accountScroll, java.awt.BorderLayout.CENTER);
        left.setMinimumSize(new java.awt.Dimension(0, 0));   // the ledger can be dragged over it
        summaryButton.setMinimumSize(new java.awt.Dimension(0, summaryButton.getPreferredSize().height));
        treePane = left;
        mainSplit.setLeftComponent(left);
        setTreeWidth(divider);
        setupTreeToggle();
    }

    /** Shows the Summary screen (the tree's selection is cleared, so any account can be picked after). */
    private void showSummary() {
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();
        }
        accountTree.clearSelection();                   // leaves the ledger (and asks about an unsaved new row)
        if (selectedAccount() != null) {
            return;                                     // kept on the ledger ("Go Back" on an unsaved row)
        }
        showingSummary = true;
        summaryView.refresh();
        ((java.awt.CardLayout) rightCards.getLayout()).show(rightCards, "summary");
        summaryButton.setSelected(true);
        summaryButton.setText("\u25B8 Summary");       // ▸ marks the screen shown
    }

    private void showLedgerView() {
        if (!showingSummary) {
            return;
        }
        showingSummary = false;
        ((java.awt.CardLayout) rightCards.getLayout()).show(rightCards, "ledger");
        summaryButton.setText("Summary");
    }

    private void refreshSummaryIfShown() {
        if (showingSummary && summaryView != null) {
            summaryView.refresh();
        }
    }

    /** Selects an account in the tree by id (which shows its ledger). */
    private void selectAccountInTree(long id) {
        for (int row = 0; row < accountTree.getRowCount(); row++) {
            Object node = accountTree.getPathForRow(row).getLastPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Account a && a.id() == id) {
                accountTree.setSelectionRow(row);
                accountTree.scrollRowToVisible(row);
                return;
            }
        }
    }

    /** A heading in the account tree for one account type ("Credit Cards"). */
    private record TypeGroup(AccountType type) {
        @Override
        public String toString() {
            return switch (type) {
                case CHECKING -> "Checking";
                case SAVINGS -> "Savings";
                case CREDIT_CARD -> "Credit Cards";
                case CASH -> "Cash";
                case LOAN -> "Loans";
                case OTHER_ASSET -> "Other Assets";
                case ENVELOPE -> "Envelopes";
            };
        }
    }

    /** Selects the first account in the tree (skipping the type headings), if there is one. */
    private void selectFirstAccount() {
        for (int row = 0; row < accountTree.getRowCount(); row++) {
            Object node = accountTree.getPathForRow(row).getLastPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Account) {
                accountTree.setSelectionRow(row);
                return;
            }
        }
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
        if (chosenTreeWidth > 0) {
            setTreeWidth(chosenTreeWidth);              // the width it was dragged to
            updateTreeMinimumWidth();
            return;
        }
        java.awt.Insets in = accountScroll.getInsets();
        int width = accountTree.getPreferredSize().width
                + (showTreeBalances ? widestTreeBalance() + 16 + TREE_BALANCE_MARGIN : 0)   // room for the balances
                + in.left + in.right
                + accountScroll.getVerticalScrollBar().getPreferredSize().width   // room if it scrolls
                + 10;                                                              // a little breathing room
        setTreeWidth(Math.max(treeMinimumWidth(), Math.min(TREE_MAX_WIDTH, width)));
        updateTreeMinimumWidth();
    }

    // ----- Hiding and showing the account list ----------------------------------------

    /** The account list's width as the user dragged it (0: fitted to the names); kept between runs. */
    private int chosenTreeWidth;
    private boolean treeShown = true;
    private int settingTreeWidth;                        // > 0 while the width is set here, not dragged
    private int treeDividerSize;
    private JPanel treePane;                             // Summary above the account tree
    private final JButton treeToggle = new JButton();

    /** Sets the account list's width (or the width it gets when shown again, while hidden). */
    private void setTreeWidth(int width) {
        lastTreeWidth = width;
        if (treeShown) {
            settingTreeWidth++;
            try {
                mainSplit.setDividerLocation(width);
            } finally {
                settingTreeWidth--;
            }
        }
    }

    private int lastTreeWidth = 250;

    /** The button at the left of the menu bar (and Ctrl+B) that hides and shows the account list. */
    private void setupTreeToggle() {
        treeDividerSize = mainSplit.getDividerSize();
        treeToggle.setIcon(new SidebarIcon(() -> treeShown));
        treeToggle.putClientProperty("JButton.buttonType", "toolBarButton");
        treeToggle.setFocusable(false);
        treeToggle.addActionListener(e -> setTreeShown(!treeShown));
        menuBar.add(treeToggle, 0);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_B, menuShortcut()), "toggleTree");
        getRootPane().getActionMap().put("toggleTree", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setTreeShown(!treeShown);
            }
        });
        // Dragging the divider: that's the width from now on (and next time).
        mainSplit.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (settingTreeWidth == 0 && treeShown && mainSplit.isShowing()) {
                chosenTreeWidth = mainSplit.getDividerLocation();
                lastTreeWidth = chosenTreeWidth;
            }
        });
        updateTreeToggle();
    }

    /** Hides or shows the account list (with Summary above it); the ledger takes the room. */
    private void setTreeShown(boolean shown) {
        if (shown == treeShown) {
            return;
        }
        if (!shown) {
            lastTreeWidth = mainSplit.getDividerLocation();
        }
        treeShown = shown;
        treePane.setVisible(shown);
        mainSplit.setDividerSize(shown ? treeDividerSize : 0);
        if (shown) {
            setTreeWidth(lastTreeWidth);
        }
        mainSplit.revalidate();
        updateTreeToggle();
    }

    private void updateTreeToggle() {
        treeToggle.setToolTipText((treeShown ? "Hide" : "Show") + " the account list ("
                + (System.getProperty("os.name", "").toLowerCase().contains("mac") ? "Cmd" : "Ctrl") + "+B)");
        treeToggle.repaint();
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
            applyColumns();                              // its type may have changed
            updateSummary(a);
            return;
        }
        if (ledgerTable.isEditing()) {
            ledgerTable.getCellEditor().stopCellEditing();   // save a half-finished cell first
        }
        resolveDraft(false);                             // a new transaction left without an amount
        ledgerModel.setAccount(a);                       // also resets the sort to Status, Date
        applyColumns();                                  // a loan's Interest and Principal
        hideReconciledBox.setVisible(a == null || !LedgerTableModel.isDebt(a.type()));   // no status column there
        applyFilter();
        ledgerTable.getTableHeader().repaint();          // update the sort arrows
        updateSummary(a);
    }

    /**
     * For a credit card with its terms entered: a third summary line with the
     * available credit and the minimum payment due ("" for other accounts).
     */
    private String cardLine(Account a) throws SQLException {
        CreditCard c = a.type() == AccountType.CREDIT_CARD ? accounts.findCard(a.id()) : null;
        if (c == null || (c.limitCents() <= 0 && !c.hasMinimumRule())) {
            return "";
        }
        long owed = -accounts.balanceThrough(a.id(), LocalDate.now());
        StringBuilder s = new StringBuilder("<br>");
        if (c.limitCents() > 0) {
            s.append("Available credit: ").append(colored(c.available(owed))).append(" of ")
             .append(Money.format(c.limitCents())).append(" (").append(c.utilizationPercent(owed)).append("% used)");
        }
        if (c.hasMinimumRule() && owed > 0) {
            LocalDate due = CreditCard.nextDay(c.dueDay(), LocalDate.now());
            s.append(c.limitCents() > 0 ? "&nbsp;&nbsp; " : "").append("Minimum payment: ")
             .append(Money.format(c.minimumPayment(owed)))
             .append(due == null ? "" : " due " + due.format(LedgerTableModel.DISPLAY_DATE));
        }
        return s.toString();
    }

    /** An amount for an HTML label: red when negative, green when positive. */
    private static String colored(long cents) {
        String text = Money.format(cents);
        if (cents == 0) {
            return text;
        }
        Color c = cents < 0 ? NEGATIVE : POSITIVE;
        return String.format("<font color='#%02x%02x%02x'>%s</font>", c.getRed(), c.getGreen(), c.getBlue(), text);
    }

    /**
     * Fills the summary pane: account name and type, the cleared balance
     * (reconciled + cleared), and the total balance (every transaction).
     */
    private void updateSummary(Account a) {
        if (a == null) {
            balanceChart.setData(null, null);
            showWebsite(null);
            accountNameLabel.setText("No account selected");
            balanceLabel.setText(" ");
            return;
        }
        accountNameLabel.setText(a.name() + "  (" + a.type() + ")");
        showWebsite(a);
        try {
            LocalDate today = LocalDate.now();
            LocalDate from = today.minusYears(1);
            if (a.type() == AccountType.LOAN) {
                // A loan: what's left to pay, falling, from the day it was made.
                AccountRepository.Details d = accounts.findDetails(a.id());
                if (d != null && d.startDate() != null && d.startDate().isBefore(today)) {
                    from = d.startDate();
                }
                long[] owed = accounts.dailyBalances(a.id(), from, today);
                for (int i = 0; i < owed.length; i++) {
                    owed[i] = -owed[i];
                }
                balanceChart.setData(from, owed);
            } else {
                // What the bank has posted: the cleared balance, day by day.
                balanceChart.setData(from, accounts.dailyBalances(a.id(), from, today, true));
            }
        } catch (SQLException e) {
            balanceChart.setData(null, null);
        }
        try {
            if (a.type() == AccountType.LOAN) {
                // A loan: what's left to pay as of today, and what's been paid on it so far (as lenders show it).
                LocalDate today = LocalDate.now();
                long remaining = -accounts.balanceThrough(a.id(), today);
                long[] paid = accounts.loanPaid(a.id(), today);
                balanceLabel.setText("<html><div align='right'><b><font size='+1'>Remaining Balance: "
                        + Money.format(remaining) + "</font></b><br>"
                        + "Principal Remaining: " + Money.format(accounts.principalOwed(a.id(), today)) + "<br>"
                        + "Total Interest Paid: " + Money.format(paid[1]) + "<br>"
                        + "Total Amount Paid: " + Money.format(paid[0]) + "</div></html>");
                return;
            }
            AccountRepository.Balances b = accounts.balances(a);
            balanceLabel.setText("<html><div align='right'><b><font size='+1'>Cleared Balance: "
                    + colored(b.clearedCents()) + "</font></b><br>"
                    + "Total Balance (all transactions): " + colored(b.totalCents())
                    + cardLine(a) + "</div></html>");
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
        remindToBackUp();                                // may ask something: while the window's still there
        saveWindow();
        setVisible(false);                               // gone at once; what's left happens out of sight
        finalSync();
        db.close();
        config.close();
    }//GEN-LAST:event_formWindowClosing

    private void newAccountItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_newAccountItemActionPerformed
        openAccounts(true);                        // Manage Accounts, with the form empty for a new account
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
