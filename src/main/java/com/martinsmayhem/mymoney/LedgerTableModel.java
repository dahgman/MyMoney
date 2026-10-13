package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.table.AbstractTableModel;

/**
 * The ledger for one account, edited in place.
 *
 * Every saved transaction is a row, plus one blank row for entering a new
 * transaction, placed just below the last transaction dated today or earlier
 * (so above any future-dated ones). The blank row is saved as soon as it gets
 * a Payment or Deposit amount, and a fresh blank row takes its place. Edits to saved rows
 * are written to the database as soon as each cell is changed.
 *
 * A reconciled transaction is locked, except for its status, category and
 * tag: none of them changes any account balance. (Changing the status away
 * from Reconciled must be confirmed.) A reconciled transaction can't be made
 * into a transfer or stop being one, since that would change another
 * account's balance.
 *
 * Rows can be sorted by several columns at once (see sortBy); whatever the
 * sort, future-dated transactions come after the current ones. Balance is the running balance in date order,
 * except when sorting by Status first: then it runs in the order shown, so
 * the last cleared row's balance is the cleared balance. Sorting by Status puts transfers to an
 * envelope (whatever their own status) after Reconciled and Cleared and
 * before Pending, Paid, Scheduled and no status.
 *
 * Rows can also be filtered (see setFilter): by text found in any column,
 * and by hiding reconciled transactions. Filtering only hides rows; Balance
 * still counts every transaction, shown or not.
 */
public class LedgerTableModel extends AbstractTableModel {

    public static final int DATE = 0;
    public static final int NUM = 1;
    public static final int PAYEE = 2;
    public static final int CATEGORY = 3;
    public static final int TAG = 4;
    public static final int DESCRIPTION = 5;
    public static final int MEMO = 6;
    public static final int STATUS = 7;
    public static final int PAYMENT = 8;
    public static final int DEPOSIT = 9;
    public static final int BALANCE = 10;
    public static final int FILES = 11;          // how many attachments (double-click to open)
    public static final int DOWNLOAD = 12;       // from a bank download: matched or new (shown next to C)

    /** Column headers (Files shows as a paperclip icon, so it has no text). */
    private static final String[] COLUMNS = {
        "Date", "Num", "Payee", "Category", "Tag", "Description", "Memo", "C",
        "Payment", "Deposit", "Balance", "", "D"
    };
    /** A column's full name (the header may be shorter, like "C" for Status). */
    public static String columnName(int column) {
        return NAMES[column];
    }

    /** Column names for messages and Undo labels ("Undo Change Status"). */
    private static final String[] NAMES = {
        "Date", "Num", "Payee", "Category", "Tag", "Description", "Memo", "Status",
        "Payment", "Deposit", "Balance", "Files", "Downloaded"
    };

    /** How dates are shown in the ledger (also used by DateCellEditor). */
    static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /** Date formats accepted when typing. A date without a year means this year. */
    private static final DateTimeFormatter[] INPUT_DATES = {
        strict("M/d/uuuu"), strict("M/d/uu"), strict("M-d-uuuu"), strict("M-d-uu"), strict("uuuu-M-d")
    };
    private static final DateTimeFormatter[] INPUT_MONTH_DAY = {
        strict("M/d"), strict("M-d")
    };

    private final TransactionRepository transactions;
    private final Consumer<String> onError;     // shows an error message to the user
    private final Predicate<String> confirm;    // asks a yes/no question; true = yes
    private final Runnable onChanged;           // called after anything is saved

    private Account account;
    private String lastAction = "Change";        // name of the last saved change, for Undo
    /** One level of sorting: a column and its direction. */
    public record SortKey(int column, boolean ascending) {
    }

    private final List<LedgerEntry> all = new ArrayList<>();       // every transaction, with balances
    private final List<LedgerEntry> entries = new ArrayList<>();   // the rows shown (filtered, sorted)
    private String filterText = "";              // lower case; "" = no text filter
    private Function<String, String> payeeDebtCategory = p -> null;   // payee -> "[Debt Account]" or null
    private boolean hideReconciled;
    /** The sort used whenever an account is selected: Status, then Date. */
    private static final List<SortKey> DEFAULT_SORT =
            List.of(new SortKey(STATUS, true), new SortKey(DATE, true));

    private final List<SortKey> sortKeys = new ArrayList<>(DEFAULT_SORT);
    private LedgerEntry draft = newDraft(LocalDate.now());   // the blank row
    private int draftIndex;                                   // its row: below the last current transaction

    public LedgerTableModel(TransactionRepository transactions,
                            Consumer<String> onError, Predicate<String> confirm,
                            Runnable onChanged) {
        this.transactions = transactions;
        this.onError = onError;
        this.confirm = confirm;
        this.onChanged = onChanged;
    }

    /** The account being shown, or null. */
    public Account currentAccount() {
        return account;
    }

    /**
     * Same account, possibly changed (name, opening balance) or with changed
     * transactions (after Undo): reloads it, keeping the sort and the blank row.
     */
    public void updateAccount(Account account) {
        String oldDefault = defaultCategory();
        this.account = account;
        if (java.util.Objects.equals(draft.category, oldDefault)) {
            draft.category = defaultCategory();         // the default changed: an untouched blank row follows
        }
        refresh();
    }

    /**
     * How to find the debt a payee pays (a "[Loan]" or "[Card]" transfer
     * category). Typing such a payee in the blank row fills in that category,
     * so the payment reduces the debt's balance.
     */
    public void setPayeeDebtCategory(Function<String, String> lookup) {
        payeeDebtCategory = lookup;
    }

    /** The account's default category for new transactions, or null. */
    private String defaultCategory() {
        return account == null ? null : account.defaultCategory();
    }

    /** A short name for the last change saved, like "Edit Payee" (shown as "Undo Edit Payee"). */
    public String lastAction() {
        return lastAction;
    }

    /** Shows a different account's ledger (or nothing, for null), in the default sort. */
    public void setAccount(Account account) {
        this.account = account;
        this.draft = newDraft(LocalDate.now());
        draft.category = defaultCategory();
        sortKeys.clear();
        sortKeys.addAll(DEFAULT_SORT);
        load();
        fireTableDataChanged();
    }

    /**
     * Shows only transactions with this text (ignoring case) in any column,
     * and, if hideReconciled, only those not reconciled. Blank text matches
     * everything. The blank new-transaction row is always shown.
     */
    public void setFilter(String text, boolean hideReconciled) {
        String t = text == null ? "" : text.trim().toLowerCase();
        if (t.equals(filterText) && hideReconciled == this.hideReconciled) {
            return;
        }
        filterText = t;
        this.hideReconciled = hideReconciled;
        applyFilter();
        fireTableDataChanged();
    }

    /** True if some transactions are hidden by the filter. */
    public boolean isFiltered() {
        return entries.size() < all.size();
    }

    /** How many transactions the account has, shown or not. */
    public int transactionCount() {
        return all.size();
    }

    /** How many transactions are shown (not counting the blank row). */
    public int shownCount() {
        return entries.size();
    }

    /** A saved row's amount in cents (negative = payment); 0 for the blank row. */
    public long amountCents(int row) {
        return isSavedRow(row) ? entryAt(row).amountCents : 0;
    }

    /** A saved row's running balance in cents; 0 for the blank row. */
    public long balanceCents(int row) {
        return isSavedRow(row) ? entryAt(row).balanceCents : 0;
    }

    /** True for a saved transaction, false for the blank entry row. */
    public boolean isSavedRow(int row) {
        return account != null && row >= 0 && row <= entries.size() && row != draftIndex;
    }

    /** True for the blank new-transaction row at the bottom. */
    public boolean isDraftRow(int row) {
        return account != null && row == draftIndex;
    }

    /** The row of the blank new-transaction row, or -1 if no account is shown. */
    public int draftRow() {
        return account == null ? -1 : draftIndex;
    }

    /** The saved transaction shown in a row (not the blank row: check isSavedRow first). */
    private LedgerEntry entryAt(int row) {
        return entries.get(row < draftIndex ? row : row - 1);
    }

    /**
     * Puts the blank row just below the last transaction dated today or
     * earlier, so it sits above the future-dated ones (at the top if every
     * transaction is in the future, at the bottom if none is).
     */
    private void placeDraft() {
        LocalDate today = LocalDate.now();
        draftIndex = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).date.isAfter(today)) {
                draftIndex = i + 1;
            }
        }
    }

    /** True if something besides the date (and the default category) has been typed into the new-transaction row. */
    public boolean draftHasData() {
        return account != null
                && (!text(draft.checkNumber).isBlank() || !text(draft.payee).isBlank()
                    || !text(draft.category).equals(text(defaultCategory())) || !text(draft.tag).isBlank()
                    || !text(draft.description).isBlank()
                    || !text(draft.memo).isBlank() || draft.status != TxnStatus.NONE);
    }

    /** Saves the new-transaction row as it is, even with no amount ($0.00). */
    public void recordDraft() {
        if (account == null) {
            return;
        }
        lastAction = "New Transaction";
        try {
            transactions.insert(account.id(), draft);
            draft = nextDraft(draft.date);
        } catch (SQLException ex) {
            onError.accept("Could not save the transaction:\n" + ex.getMessage());
        }
        refresh();
        onChanged.run();
    }

    /** Clears the new-transaction row (keeping its date). */
    public void discardDraft() {
        draft = nextDraft(draft.date);
        if (account != null) {
            fireTableRowsUpdated(draftIndex, draftIndex);
        }
    }

    /** For a saved transfer row, the other account's name; otherwise null. */
    public String transferAccount(int row) {
        if (!isSavedRow(row)) {
            return null;
        }
        String c = entryAt(row).category;
        return TransactionRepository.isTransfer(c) ? c.substring(1, c.length() - 1) : null;
    }

    /** The tag color for a row, or null if it has no (saved) tag. */
    public Color tagColor(int row) {
        return isSavedRow(row) ? entryAt(row).tagColor : null;
    }

    /** Re-reads the current account's transactions, keeping the sort (after tags change). */
    public void reload() {
        refresh();
    }

    /**
     * The row of the transaction dated today, or else the latest one before
     * today (the most recently entered, if several share that date).
     * -1 if every transaction is in the future or there are none.
     */
    public int rowNearestToday() {
        LocalDate today = LocalDate.now();
        int best = -1;
        for (int i = 0; i < entries.size(); i++) {
            LedgerEntry e = entries.get(i);
            if (e.date.isAfter(today)) {
                continue;
            }
            if (best < 0) {
                best = i;
                continue;
            }
            LedgerEntry b = entries.get(best);
            if (e.date.isAfter(b.date) || (e.date.equals(b.date) && e.id > b.id)) {
                best = i;
            }
        }
        return best < 0 || best < draftIndex ? best : best + 1;     // skip over the blank row
    }

    /** True for a saved transaction dated after today. */
    public boolean isFuture(int row) {
        return isSavedRow(row) && entryAt(row).date.isAfter(LocalDate.now());
    }

    /**
     * A reconciled transaction's category may change unless the old or new
     * category is a transfer or it is a split transaction.
     */
    private static boolean reconciledCategoryChangeOk(String oldCategory, Object newValue) {
        String newText = newValue == null ? "" : newValue.toString().trim();
        return !TransactionRepository.isTransfer(oldCategory)
                && !TransactionRepository.SPLIT_LABEL.equals(oldCategory)
                && !TransactionRepository.isTransfer(newText);
    }

    /** A saved row's transaction id; 0 for the blank row. */
    public long transactionId(int row) {
        return isSavedRow(row) ? entryAt(row).id : 0;
    }

    /** A copy of a saved row's transaction (null for the blank row), e.g. to schedule it. */
    public LedgerEntry entryCopy(int row) {
        return isSavedRow(row) ? entryAt(row).copy() : null;
    }

    /** A saved row's date; null for the blank row. */
    public LocalDate transactionDate(int row) {
        return isSavedRow(row) ? entryAt(row).date : null;
    }

    /** True for a reconciled transaction: only its status, category and tag can be changed, and it can't be deleted. */
    public boolean isLocked(int row) {
        return isSavedRow(row) && entryAt(row).status == TxnStatus.RECONCILED;
    }

    public void deleteRow(int row) {
        deleteRows(new int[] {row});
    }

    /**
     * Deletes the saved, unreconciled transactions among these rows (and the
     * other side of any transfers), all at once. Other rows are left alone.
     */
    public void deleteRows(int[] rows) {
        List<Long> ids = new ArrayList<>();
        for (int row : rows) {
            if (isSavedRow(row) && !isLocked(row)) {
                ids.add(entryAt(row).id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        lastAction = ids.size() == 1 ? "Delete Transaction" : "Delete Transactions";
        try {
            transactions.deleteAll(ids);
        } catch (SQLException e) {
            onError.accept("Could not delete " + (ids.size() == 1 ? "the transaction" : "the transactions")
                    + ", so nothing was deleted:\n" + e.getMessage());
        }
        refresh();
        onChanged.run();
    }

    /** How many of these rows are saved transactions with a memo. */
    public int countWithMemo(int[] rows) {
        int n = 0;
        for (int row : rows) {
            if (isSavedRow(row) && !text(entryAt(row).memo).isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** How many of these rows came from a bank download (show the downloaded flag). */
    public int countDownloaded(int[] rows) {
        int n = 0;
        for (int row : rows) {
            if (isSavedRow(row) && entryAt(row).download != null) {
                n++;
            }
        }
        return n;
    }

    /** Clears the downloaded flag on these rows (see TransactionRepository.clearDownloaded). */
    public void clearDownloaded(int[] rows) {
        List<Long> ids = new ArrayList<>();
        for (int row : rows) {
            if (isSavedRow(row) && entryAt(row).download != null) {
                ids.add(entryAt(row).id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        lastAction = "Clear Downloaded Flag";
        try {
            transactions.clearDownloaded(ids);
        } catch (SQLException ex) {
            onError.accept("Could not clear the downloaded flag, so nothing was changed:\n" + ex.getMessage());
        }
        refresh();
        onChanged.run();
    }

    /** How many of these rows have both a memo and a description already. */
    public int countWithMemoAndDescription(int[] rows) {
        int n = 0;
        for (int row : rows) {
            if (isSavedRow(row) && !text(entryAt(row).memo).isBlank()
                    && !text(entryAt(row).description).isBlank()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Moves each row's memo into its description and clears the memo.
     * Rows without a memo are left alone. Where there is already a
     * description, the memo either replaces it or is added after it
     * ("description - memo"). Reconciled rows are included: neither field
     * affects a balance.
     */
    public void moveMemoToDescription(int[] rows, boolean append) {
        List<LedgerEntry> changes = new ArrayList<>();
        for (int row : rows) {
            if (!isSavedRow(row) || text(entryAt(row).memo).isBlank()) {
                continue;
            }
            LedgerEntry e = entryAt(row).copy();
            String memo = e.memo.trim();
            String description = text(e.description).trim();
            e.description = append && !description.isEmpty() ? description + " - " + memo : memo;
            e.memo = null;
            changes.add(e);
        }
        if (changes.isEmpty()) {
            return;
        }
        lastAction = "Move Memo to Description";
        try {
            transactions.updateAll(changes);
        } catch (SQLException ex) {
            onError.accept("Could not move the memos, so nothing was changed:\n" + ex.getMessage());
        }
        refresh();
        onChanged.run();
    }

    /**
     * Sets one column (Date, Payee, Category, Tag or Status) to the same value
     * on several rows at once, saving them all together. Reconciled rows are
     * skipped, except for Status, Tag and (non-transfer) Category changes; split
     * transactions are skipped when changing Category. Taking reconciled rows
     * off Reconciled must be confirmed.
     *
     * @param value the text to set, or a TxnStatus for the Status column
     * @return a note about skipped rows, or null if every row was changed
     *         (also null if cancelled or on error)
     */
    public String bulkSet(int[] rows, int column, Object value) {
        List<LedgerEntry> changes = new ArrayList<>();
        int reconciled = 0;
        int splits = 0;
        int reconciling = 0;
        if (column == STATUS) {
            int unreconciling = 0;
            for (int row : rows) {
                if (isLocked(row) && value != TxnStatus.RECONCILED) {
                    unreconciling++;
                }
            }
            if (unreconciling > 0 && !confirm.test((unreconciling == 1 ? "1 of the selected transactions is"
                    : unreconciling + " of the selected transactions are") + " reconciled. Change "
                    + (unreconciling == 1 ? "it" : "them") + " to " + value + "?\n\n"
                    + (unreconciling == 1 ? "It" : "They") + " will no longer be locked, and your"
                    + " reconciled balance may no longer match your statement.")) {
                return null;
            }
        }
        try {
            for (int row : rows) {
                if (!isSavedRow(row)) {
                    continue;
                }
                LedgerEntry e = entryAt(row);
                if (column == STATUS && e.status == TxnStatus.RECONCILING) {
                    reconciling++;
                } else if (e.status == TxnStatus.RECONCILED && column != STATUS && column != TAG
                        && !(column == CATEGORY && reconciledCategoryChangeOk(e.category, value))) {
                    reconciled++;
                } else if (column == CATEGORY && TransactionRepository.SPLIT_LABEL.equals(e.category)) {
                    splits++;
                } else {
                    LedgerEntry changed = e.copy();
                    apply(changed, column, value);
                    changes.add(changed);
                }
            }
        } catch (IllegalArgumentException ex) {
            onError.accept(ex.getMessage());
            return null;
        }
        if (!changes.isEmpty()) {
            lastAction = "Change " + NAMES[column];
            try {
                transactions.updateAll(changes);
            } catch (SQLException ex) {
                onError.accept("Could not change the transactions, so nothing was changed:\n" + ex.getMessage());
                refresh();
                return null;
            }
            refresh();
            onChanged.run();
        }
        if (reconciled == 0 && splits == 0 && reconciling == 0) {
            return null;
        }
        StringBuilder note = new StringBuilder(changes.isEmpty() ? "Nothing was changed."
                : "Changed " + changes.size() + " of " + (changes.size() + reconciled + splits + reconciling)
                        + " transactions.");
        if (reconciling > 0) {
            note.append("\n").append(reconciling)
                .append(reconciling == 1 ? " transaction is" : " transactions are")
                .append(" part of an unfinished reconciliation; use Reconcile Account to change ")
                .append(reconciling == 1 ? "it." : "them.");
        }
        if (reconciled > 0) {
            note.append("\n").append(reconciled).append(" reconciled ")
                .append(reconciled == 1 ? "transaction was" : "transactions were").append(" left alone")
                .append(column == CATEGORY ? " (a reconciled transfer, or a change to a transfer,"
                        + " would change another account's balance)." : ".");
        }
        if (splits > 0) {
            note.append("\n").append(splits).append(" split ")
                .append(splits == 1 ? "transaction keeps its" : "transactions keep their").append(" categories.");
        }
        return note.toString();
    }

    // ----- TableModel ---------------------------------------------------

    @Override
    public int getRowCount() {
        return account == null ? 0 : entries.size() + 1;   // +1 for the blank row
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
        return column == STATUS ? TxnStatus.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        if (account == null || column == BALANCE || column == FILES || column == DOWNLOAD) {
            return false;
        }
        if (column == STATUS && isSavedRow(row) && entryAt(row).status == TxnStatus.RECONCILING) {
            return false;                       // only Reconcile Account changes these
        }
        if (isLocked(row)) {
            // Reconciled: status, tag and category only (a transfer's category stays put).
            return column == STATUS || column == TAG
                    || (column == CATEGORY && reconciledCategoryChangeOk(entryAt(row).category, null));
        }
        return true;
    }

    @Override
    public Object getValueAt(int row, int column) {
        LedgerEntry e = isSavedRow(row) ? entryAt(row) : draft;
        return switch (column) {
            case DATE -> e.date == null ? "" : e.date.format(DISPLAY_DATE);
            case NUM -> text(e.checkNumber);
            case PAYEE -> text(e.payee);
            case CATEGORY -> text(e.category);
            case TAG -> text(e.tag);
            case DESCRIPTION -> text(e.description);
            case MEMO -> text(e.memo);
            case STATUS -> e.status;
            case PAYMENT -> e.amountCents < 0 ? Money.format(-e.amountCents) : "";
            case DEPOSIT -> e.amountCents > 0 ? Money.format(e.amountCents) : "";
            case BALANCE -> isSavedRow(row) ? Money.format(e.balanceCents) : "";
            case FILES -> e.attachments > 0 ? String.valueOf(e.attachments) : "";
            case DOWNLOAD -> e.download == null ? "" : e.download;     // drawn as an icon
            default -> "";
        };
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (account == null) {
            return;
        }
        if (!isCellEditable(row, column)) {
            return;
        }
        boolean isDraft = !isSavedRow(row);
        LedgerEntry e = isDraft ? draft : entryAt(row);

        // Un-reconciling needs a yes from the user.
        if (column == STATUS && e.status == TxnStatus.RECONCILED && !isDraft) {
            TxnStatus newStatus = value instanceof TxnStatus s ? s : TxnStatus.NONE;
            if (newStatus == TxnStatus.RECONCILED) {
                return;                               // no change
            }
            if (!confirm.test("This transaction is reconciled. Change its status to "
                    + newStatus.label() + "?\n\nIt will no longer be locked, and your"
                    + " reconciled balance may no longer match your statement.")) {
                fireTableRowsUpdated(row, row);       // redraw the purple dot
                return;
            }
        }

        if (column == CATEGORY && isLocked(row) && !reconciledCategoryChangeOk(e.category, value)) {
            onError.accept("A reconciled transaction can't be made into a transfer,"
                    + " because that would change another account's balance.");
            return;
        }

        // 1. Put the typed value into the entry. Bad input changes nothing.
        LedgerEntry before = e.copy();
        try {
            if (!apply(e, column, value)) {
                return;
            }
        } catch (IllegalArgumentException ex) {
            onError.accept(ex.getMessage());
            return;
        }
        if (!isDraft && e.sameContent(before)) {
            return;                                     // nothing actually changed: no save, no undo step
        }
        if (isDraft && column == PAYEE && (text(e.category).isBlank()
                || text(e.category).equals(text(defaultCategory())))) {
            String debt = payeeDebtCategory.apply(e.payee);
            if (debt != null && account != null && !debt.equalsIgnoreCase("[" + account.name() + "]")) {
                e.category = debt;                      // a debt payment: transfer it to the debt account
            }
        }

        // 2. Save it.
        try {
            if (isDraft) {
                boolean amountEntered = (column == PAYMENT || column == DEPOSIT) && e.amountCents != 0;
                if (!amountEntered) {
                    fireTableRowsUpdated(row, row);      // keep filling in the blank row
                    return;
                }
                lastAction = "New Transaction";
                transactions.insert(account.id(), draft);
                draft = nextDraft(draft.date);           // next entry starts on the same date
            } else {
                lastAction = column == STATUS ? "Change Status" : "Edit " + NAMES[column];
                transactions.update(e);
            }
        } catch (SQLException ex) {
            onError.accept("Could not save the transaction:\n" + ex.getMessage());
        }
        refresh();
        onChanged.run();
    }

    /**
     * Puts a typed (or chosen) value into one field of an entry.
     * Returns false for a column that can't be set this way.
     * @throws IllegalArgumentException for bad input; the entry is unchanged
     */
    private static boolean apply(LedgerEntry e, int column, Object value) {
        String text = value == null ? "" : value.toString().trim();
        switch (column) {
            case DATE -> e.date = parseDate(text);
            case NUM -> e.checkNumber = text;
            case PAYEE -> e.payee = text;
            case CATEGORY -> e.category = text.isEmpty() ? null
                    : TransactionRepository.isTransfer(text) ? text      // "[Savings]" = transfer
                    : CategoryRepository.normalize(text);
            case TAG -> e.tag = text.isEmpty() ? null : TagRepository.normalize(text);
            case DESCRIPTION -> e.description = text;
            case MEMO -> e.memo = text;
            case STATUS -> e.status = value instanceof TxnStatus s ? s : TxnStatus.NONE;
            case PAYMENT -> {
                if (!text.isEmpty()) {
                    e.amountCents = -Money.parse(text);
                } else if (e.amountCents < 0) {
                    e.amountCents = 0;
                }
            }
            case DEPOSIT -> {
                if (!text.isEmpty()) {
                    e.amountCents = Money.parse(text);
                } else if (e.amountCents > 0) {
                    e.amountCents = 0;
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ----- Sorting ------------------------------------------------------

    /**
     * Sorts by a column (a header click).
     * Plain click: sort by this column only; if it already was the only
     * sort column, flip between ascending and descending.
     * Ctrl+click: add this column as the next sort level; if it is already
     * one of the levels, flip its direction.
     */
    public void sortBy(int column, boolean addLevel) {
        int i = sortLevelOf(column);
        if (addLevel) {
            if (i >= 0) {
                flip(i);
            } else {
                sortKeys.add(new SortKey(column, true));
            }
        } else if (i == 0 && sortKeys.size() == 1) {
            flip(0);
        } else {
            sortKeys.clear();
            sortKeys.add(new SortKey(column, true));
        }
        applyFilter();                   // re-sorts, and the running balance may follow the new order
        fireTableDataChanged();
    }

    /** Header suffix: " ▲" / " ▼", numbered when sorting by several columns. */
    public String sortIndicator(int column) {
        int i = sortLevelOf(column);
        if (i < 0) {
            return "";
        }
        String arrow = sortKeys.get(i).ascending() ? " \u25B2" : " \u25BC";
        return sortKeys.size() > 1 ? arrow + (i + 1) : arrow;
    }

    private int sortLevelOf(int column) {
        for (int i = 0; i < sortKeys.size(); i++) {
            if (sortKeys.get(i).column() == column) {
                return i;
            }
        }
        return -1;
    }

    private void flip(int level) {
        SortKey k = sortKeys.get(level);
        sortKeys.set(level, new SortKey(k.column(), !k.ascending()));
    }

    /** Current before future; then the sort levels in order, then date and entry order to break ties. */
    private Comparator<LedgerEntry> sortOrder() {
        // Future-dated transactions always come after the rest, whatever the sort,
        // so the blank row (placed after the last current one) divides the two.
        LocalDate today = LocalDate.now();
        Comparator<LedgerEntry> order = Comparator.comparing(e -> e.date.isAfter(today));
        for (SortKey k : sortKeys) {
            order = order.thenComparing(columnOrder(k.column(), k.ascending()));
        }
        return order.thenComparing(e -> e.date).thenComparingLong(e -> e.id);
    }

    /** How one column sorts. Blank values always go last (amounts: see amountOrder). */
    private static Comparator<LedgerEntry> columnOrder(int column, boolean ascending) {
        return switch (column) {
            case DATE -> by(e -> e.date, Comparator.<LocalDate>naturalOrder(), ascending);
            case NUM -> by(e -> blankToNull(e.checkNumber), LedgerTableModel::compareNumbers, ascending);
            case PAYEE -> by(e -> blankToNull(e.payee), String.CASE_INSENSITIVE_ORDER, ascending);
            case CATEGORY -> by(e -> blankToNull(e.category), String.CASE_INSENSITIVE_ORDER, ascending);
            case TAG -> by(e -> blankToNull(e.tag), String.CASE_INSENSITIVE_ORDER, ascending);
            case DESCRIPTION -> by(e -> blankToNull(e.description), String.CASE_INSENSITIVE_ORDER, ascending);
            case MEMO -> by(e -> blankToNull(e.memo), String.CASE_INSENSITIVE_ORDER, ascending);
            case STATUS -> {
                // Future transactions are all simply upcoming: status doesn't separate them, so the
                // next sort level (normally Date) keeps them in date order, Scheduled or not.
                LocalDate today = LocalDate.now();
                yield by(e -> e.date.isAfter(today) ? FUTURE_RANK : statusRank(e),
                        Comparator.<Integer>naturalOrder(), ascending);
            }
            case PAYMENT -> amountOrder(true, ascending);
            case DEPOSIT -> amountOrder(false, ascending);
            case BALANCE -> by(e -> e.balanceCents, Comparator.<Long>naturalOrder(), ascending);
            case FILES -> by(e -> e.attachments == 0 ? null : e.attachments, Comparator.<Integer>naturalOrder(), ascending);
            case DOWNLOAD -> by(e -> e.download, Comparator.<String>naturalOrder(), ascending);
            default -> (a, b) -> 0;
        };
    }

    /** Every future-dated transaction's place when sorting by Status (they are kept apart anyway). */
    private static final Integer FUTURE_RANK = 0;

    /**
     * Where a current row goes when sorting by Status: Reconciled, Cleared, then
     * transfers to an envelope (whatever their status), then Pending, Paid
     * and Scheduled. Null (no status) always goes last.
     */
    private static Integer statusRank(LedgerEntry e) {
        if (e.toEnvelope) {
            return 2;
        }
        return switch (e.status) {
            case RECONCILED -> 0;
            case CLEARED -> 1;
            case RECONCILING -> 1;              // being reconciled: with the cleared ones
            case PENDING -> 3;
            case PAID -> 4;
            case SCHEDULED -> 5;
            case NONE -> null;
        };
    }

    /**
     * Payment or Deposit: rows with an amount in this column come first,
     * sorted by it; then rows whose amount is in the other column, sorted by
     * that amount in the same direction; then $0.00 rows.
     */
    private static Comparator<LedgerEntry> amountOrder(boolean payments, boolean ascending) {
        Comparator<LedgerEntry> group = Comparator.comparingInt(e -> {
            if (e.amountCents == 0) {
                return 2;
            }
            return (e.amountCents < 0) == payments ? 0 : 1;
        });
        Comparator<Long> size = ascending ? Comparator.<Long>naturalOrder() : Comparator.<Long>reverseOrder();
        return group.thenComparing(e -> Math.abs(e.amountCents), size);
    }

    private static <T> Comparator<LedgerEntry> by(Function<LedgerEntry, T> key,
            Comparator<T> order, boolean ascending) {
        return Comparator.comparing(key, Comparator.nullsLast(ascending ? order : order.reversed()));
    }

    /** Check numbers: numbers in numeric order (2 before 10), then other text. */
    private static int compareNumbers(String a, String b) {
        Long x = asLong(a);
        Long y = asLong(b);
        if (x != null && y != null) {
            return x.compareTo(y);
        }
        if (x != null || y != null) {
            return x != null ? -1 : 1;
        }
        return a.compareToIgnoreCase(b);
    }

    private static Long asLong(String s) {
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---------------------------------------------------------------------

    /** Re-reads the account's transactions, computes balances, filters and sorts. */
    private void load() {
        all.clear();
        if (account != null) {
            try {
                all.addAll(transactions.findByAccount(account.id()));   // date order
            } catch (SQLException e) {
                onError.accept("Could not load transactions:\n" + e.getMessage());
            }
        }
        applyFilter();
    }

    /**
     * Computes running balances over every transaction (shown or not), then
     * fills the shown rows: the ones that pass the filter, sorted.
     */
    private void applyFilter() {
        all.sort(Comparator.comparing((LedgerEntry e) -> e.date).thenComparingLong(e -> e.id));
        runBalances();
        if (sortKeys.get(0).column() == STATUS) {
            all.sort(sortOrder());       // sorted by Status: the balance runs in the order shown
            runBalances();
        }
        entries.clear();
        for (LedgerEntry e : all) {
            if (!(hideReconciled && e.status == TxnStatus.RECONCILED) && matchesText(e)) {
                entries.add(e);
            }
        }
        entries.sort(sortOrder());
        placeDraft();
    }

    /** Running balance down the all list, in its current order. */
    private void runBalances() {
        if (account == null) {
            return;
        }
        long balance = account.startingBalanceCents();
        for (LedgerEntry e : all) {
            balance += e.amountCents;
            e.balanceCents = balance;
        }
    }

    /** True if the filter text appears in any column, as shown or (for amounts) as typed. */
    private boolean matchesText(LedgerEntry e) {
        if (filterText.isEmpty()) {
            return true;
        }
        String[] fields = {
            e.date == null ? "" : e.date.format(DISPLAY_DATE),
            e.checkNumber, e.payee, e.category, e.tag, e.description, e.memo,
            e.status == TxnStatus.NONE ? "" : e.status.label(),
            Money.format(Math.abs(e.amountCents)),          // "$1,250.50"
            plainAmount(Math.abs(e.amountCents)),            // "1250.50"
            Money.format(e.balanceCents),
            plainAmount(e.balanceCents)
        };
        for (String f : fields) {
            if (f != null && f.toLowerCase().contains(filterText)) {
                return true;
            }
        }
        return false;
    }

    /** 125050 -> "1250.50", without "$" or commas, so typed amounts match too. */
    private static String plainAmount(long cents) {
        return java.math.BigDecimal.valueOf(cents, 2).toPlainString();
    }

    /**
     * Reloads, telling the table about added or removed rows rather than
     * replacing everything, so the selected row stays put (Enter moves down).
     */
    private void refresh() {
        int before = getRowCount();
        load();
        int after = getRowCount();
        if (after > before) {
            fireTableRowsInserted(before, after - 1);
        } else if (after < before) {
            fireTableRowsDeleted(after, before - 1);
        }
        if (after > 0) {
            fireTableRowsUpdated(0, after - 1);
        }
    }

    private static LedgerEntry newDraft(LocalDate date) {
        LedgerEntry e = new LedgerEntry();
        e.date = date;
        return e;
    }

    /** A fresh blank row on this date, with the account's default category filled in. */
    private LedgerEntry nextDraft(LocalDate date) {
        LedgerEntry e = newDraft(date);
        e.category = defaultCategory();
        return e;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    /** Accepts 10/11/2026, 10/11/26, 10-11-2026, 2026-10-11, or 10/11 (this year). */
    static LocalDate parseDate(String text) {
        for (DateTimeFormatter f : INPUT_DATES) {
            try {
                return LocalDate.parse(text, f);
            } catch (DateTimeParseException ignored) {
            }
        }
        for (DateTimeFormatter f : INPUT_MONTH_DAY) {
            try {
                return MonthDay.parse(text, f).atYear(LocalDate.now().getYear());
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new IllegalArgumentException("\"" + text + "\" is not a valid date. Use MM/DD/YYYY.");
    }
}
