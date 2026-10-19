package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    public static final int INTEREST = 13;       // loans: the interest in a payment (calculated)
    public static final int PRINCIPAL = 14;      // loans: the rest of a payment
    public static final int TYPE = 15;           // like POS or DIRECTDEBIT (a bank download's TRNTYPE)
    public static final int PRINCIPAL_BALANCE = 16;  // loans: the principal still owed after each transaction
    public static final int BANK_DATE = 17;      // the date the bank posted it (from a bank download, or typed)

    /** True for the columns only a loan's ledger has (not a credit card's). */
    public static boolean isLoanOnlyColumn(int column) {
        return column == PRINCIPAL_BALANCE;
    }

    /** True for the columns only a debt's (loan's or credit card's) ledger shows. */
    public static boolean isLoanColumn(int column) {
        return column == INTEREST || column == PRINCIPAL;
    }

    /** True for a loan or credit card: what's owed, with its own, shorter ledger. */
    public static boolean isDebt(AccountType type) {
        return type == AccountType.LOAN || type == AccountType.CREDIT_CARD;
    }

    /**
     * The columns a loan's or credit card's ledger has: Date, Num, Type,
     * Charge, Payment, Interest, Principal, Balance and Files. (Any of them
     * can still be hidden.)
     */
    private static final java.util.Set<Integer> DEBT_COLUMNS =
            java.util.Set.of(DATE, NUM, TYPE, PAYMENT, DEPOSIT, INTEREST, PRINCIPAL, BALANCE, PRINCIPAL_BALANCE, FILES);

    /** Column headers (Files shows as a paperclip icon, so it has no text). */
    private static final String[] COLUMNS = {
        "Date", "Num", "Payee", "Category", "Tag", "Description", "Memo", "C",
        "Payment", "Deposit", "Balance", "", "D", "Interest", "Principal", "Type", "Principal Bal.", "Bank Date"
    };
    /** A column's full name (the header may be shorter, like "C" for Status). */
    public static String columnName(int column) {
        return NAMES[column];
    }

    /** Column names for messages and Undo labels ("Undo Change Status"). */
    private static final String[] NAMES = {
        "Date", "Num", "Payee", "Category", "Tag", "Description", "Memo", "Status",
        "Payment", "Deposit", "Balance", "Files", "Downloaded", "Interest", "Principal", "Type",
        "Principal Balance", "Bank Date"
    };

    /**
     * The date formats to choose from (Preferences > Date format), as
     * patterns. The first is the default.
     */
    static final String[] DATE_FORMATS = {"MM/dd/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "MM-dd-yyyy", "dd.MM.yyyy"};

    /** How dates are shown everywhere (see setDateFormat). */
    static DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern(DATE_FORMATS[0]);
    private static String datePattern = DATE_FORMATS[0];

    /** Shows (and reads) dates in this format from now on: one of DATE_FORMATS (anything else: the default). */
    static void setDateFormat(String pattern) {
        datePattern = java.util.Arrays.asList(DATE_FORMATS).contains(pattern) ? pattern : DATE_FORMATS[0];
        DISPLAY_DATE = DateTimeFormatter.ofPattern(datePattern);
    }

    /** The date format's pattern, like "MM/dd/yyyy" (for the phone's snapshot). */
    static String datePattern() {
        return datePattern;
    }

    /** The date format as people write it: "MM/DD/YYYY". */
    static String dateFormatExample() {
        return datePattern.toUpperCase();
    }

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
    private int filterColumn = -1;               // the column the filter text is looked for in; -1 = all
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
        if (account != null && isDebt(account.type())) {
            sortKeys.add(new SortKey(DATE, true));       // no status column in a debt's ledger: just in date order
        } else {
            sortKeys.addAll(DEFAULT_SORT);
        }
        load();
        fireTableDataChanged();
    }

    /** True for the columns a loan's or credit card's ledger leaves out (see DEBT_COLUMNS). */
    public static boolean isHiddenForDebts(int column) {
        return !DEBT_COLUMNS.contains(column);
    }

    /**
     * Shows only transactions with this text (ignoring case) in any column,
     * and, if hideReconciled, only those not reconciled. Blank text matches
     * everything. The blank new-transaction row is always shown.
     *
     * Text starting with a column's name and a colon looks in that column
     * only: "payee: walmart", "memo:card 2549", "type:pos". The name is the
     * column's header or full name, ignoring case ("C" or "Status"; in a
     * loan, "Charge" and "Payment" too). Text before a colon that isn't a
     * column's name is searched for as it is, in every column.
     */
    public void setFilter(String text, boolean hideReconciled) {
        String t = text == null ? "" : text.trim().toLowerCase();
        int column = -1;
        int colon = t.indexOf(':');
        if (colon > 0) {
            column = columnNamed(t.substring(0, colon).trim());
            if (column >= 0) {
                t = t.substring(colon + 1).trim();
            }
        }
        if (t.equals(filterText) && column == filterColumn && hideReconciled == this.hideReconciled) {
            return;
        }
        filterText = t;
        filterColumn = column;
        this.hideReconciled = hideReconciled;
        applyFilter();
        fireTableDataChanged();
    }

    /**
     * The column with this header or name (ignoring case), or -1. Headers as
     * shown come first, so in a loan "payment" is its Payment column.
     */
    private int columnNamed(String name) {
        AccountType type = account == null ? null : account.type();
        for (int c = 0; c < COLUMNS.length; c++) {
            if (!header(c, type).isEmpty() && header(c, type).equalsIgnoreCase(name)) {
                return c;
            }
        }
        for (int c = 0; c < NAMES.length; c++) {
            if (NAMES[c].equalsIgnoreCase(name)) {
                return c;
            }
        }
        return -1;
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
    public long principalBalanceCents(int row) {
        return isSavedRow(row) ? entryAt(row).principalBalanceCents : 0;
    }

    public long balanceCents(int row) {
        return isSavedRow(row) ? entryAt(row).balanceCents : 0;
    }

    /**
     * How long the bank took with a row's transaction: "Posted 3 days after
     * the date entered", or null if its bank date isn't known.
     */
    public String bankDelay(int row) {
        if (!isSavedRow(row) || entryAt(row).bankDate == null || entryAt(row).date == null) {
            return null;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(entryAt(row).date, entryAt(row).bankDate);
        return days == 0 ? "Posted on the date entered"
                : "Posted " + Math.abs(days) + (Math.abs(days) == 1 ? " day " : " days ")
                        + (days > 0 ? "after" : "before") + " the date entered";
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

    /** Sets one column to the same value on several rows (see the Map version). */
    public String bulkSet(int[] rows, int column, Object value) {
        return bulkSet(rows, java.util.Map.of(column, value));
    }

    /**
     * Sets several fields (Date, Num, Type, Payee, Category, Tag, Description,
     * Memo, Status) to the same values on several rows at once, saving them
     * all together, as one change.
     *
     * A reconciled transaction takes only what can't change a balance: its
     * status, tag, payee, and (unless it's a transfer, or becoming one) its
     * category; its other fields are left as they are. One being reconciled
     * keeps its status; a split transaction keeps its categories. Taking
     * reconciled rows off Reconciled must be confirmed.
     *
     * @param values each field's column and new value: text, a LocalDate for
     *               Date, a TxnStatus for Status (blank text removes it)
     * @return a note about what was left alone, or null if everything changed
     *         (also null if cancelled or on error)
     */
    public String bulkSet(int[] rows, java.util.Map<Integer, Object> values) {
        if (account == null || values.isEmpty()) {
            return null;
        }
        Object status = values.get(STATUS);
        if (status != null) {
            int unreconciling = 0;
            for (int row : rows) {
                if (isLocked(row) && status != TxnStatus.RECONCILED) {
                    unreconciling++;
                }
            }
            if (unreconciling > 0 && !confirm.test((unreconciling == 1 ? "1 of the selected transactions is"
                    : unreconciling + " of the selected transactions are") + " reconciled. Change "
                    + (unreconciling == 1 ? "it" : "them") + " to " + status + "?\n\n"
                    + (unreconciling == 1 ? "It" : "They") + " will no longer be locked, and your"
                    + " reconciled balance may no longer match your statement.")) {
                return null;
            }
        }
        List<LedgerEntry> changes = new ArrayList<>();
        int selected = 0;
        int reconciled = 0;                              // rows with a field left alone because they're reconciled
        int splits = 0;
        int reconciling = 0;
        try {
            for (int row : rows) {
                if (!isSavedRow(row)) {
                    continue;
                }
                selected++;
                LedgerEntry e = entryAt(row);
                LedgerEntry changed = e.copy();
                boolean any = false;
                boolean leftReconciled = false;
                for (java.util.Map.Entry<Integer, Object> v : values.entrySet()) {
                    int column = v.getKey();
                    Object value = v.getValue() instanceof LocalDate d ? d.format(DISPLAY_DATE) : v.getValue();
                    if (column == STATUS && e.status == TxnStatus.RECONCILING) {
                        reconciling++;
                    } else if (e.status == TxnStatus.RECONCILED && column != STATUS && column != TAG
                            && column != PAYEE && !(column == CATEGORY && reconciledCategoryChangeOk(e.category, value))) {
                        leftReconciled = true;
                    } else if (column == CATEGORY && TransactionRepository.SPLIT_LABEL.equals(e.category)) {
                        splits++;
                    } else if (e.scheduledInterest && column != STATUS) {
                        leftReconciled = true;                  // calculated: as for a reconciled one
                    } else {
                        apply(changed, column, value);
                        any = true;
                    }
                }
                if (leftReconciled) {
                    reconciled++;
                }
                if (any && !changed.sameContent(e)) {
                    changes.add(changed);
                }
            }
        } catch (IllegalArgumentException ex) {
            onError.accept(ex.getMessage());
            return null;
        }
        if (!changes.isEmpty()) {
            lastAction = values.size() == 1 ? "Change " + NAMES[values.keySet().iterator().next()] : "Edit Transactions";
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
                : "Changed " + changes.size() + " of " + selected + " transactions.");
        if (reconciling > 0) {
            note.append("\n").append(reconciling)
                .append(reconciling == 1 ? " transaction is" : " transactions are")
                .append(" part of an unfinished reconciliation and kept ")
                .append(reconciling == 1 ? "its status; use Reconcile Account to change it."
                        : "their status; use Reconcile Account to change them.");
        }
        if (reconciled > 0) {
            note.append("\n").append(reconciled).append(" reconciled ")
                .append(reconciled == 1 ? "transaction kept" : "transactions kept")
                .append(" what can't change on a reconciled transaction (only its status, tag, payee and,"
                        + " unless it's a transfer, category can).");
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

    /**
     * A column's header for an account of this type. In a loan's or credit
     * card's ledger, money in (lowering what's owed) is a Payment, and money
     * out (owing more) is a Charge, as lenders put it.
     */
    public static String header(int column, AccountType type) {
        if (isDebt(type) && column == PAYMENT) {
            return "Charge";
        }
        if (isDebt(type) && column == DEPOSIT) {
            return "Payment";
        }
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
        if (isLoanColumn(column)) {
            // A loan payment's interest and principal: calculated, but can be typed over.
            if (account.type() != AccountType.LOAN) {
                return false;
            }
            if (!isSavedRow(row)) {
                return true;                            // a principal-only or interest-only payment
            }
            LedgerEntry e = entryAt(row);
            return !e.scheduledInterest && e.amountCents >= 0 && !isLocked(row);
        }
        if (column == STATUS && isSavedRow(row) && entryAt(row).status == TxnStatus.RECONCILING) {
            return false;                       // only Reconcile Account changes these
        }
        if (isSavedRow(row) && entryAt(row).scheduledInterest) {
            return column == STATUS;                // calculated from the loan's terms and payments
        }
        if (isLocked(row)) {
            // Reconciled: status, tag, payee and category only (a transfer's category stays put): none
            // of them changes a balance.
            return column == STATUS || column == TAG || column == PAYEE || column == BANK_DATE
                    || (column == CATEGORY && reconciledCategoryChangeOk(entryAt(row).category, null));
        }
        return true;
    }

    @Override
    public Object getValueAt(int row, int column) {
        LedgerEntry e = isSavedRow(row) ? entryAt(row) : draft;
        return switch (column) {
            case DATE -> e.date == null ? "" : e.date.format(DISPLAY_DATE);
            case BANK_DATE -> e.bankDate == null ? "" : e.bankDate.format(DISPLAY_DATE);
            case NUM -> text(e.checkNumber);
            case PAYEE -> text(e.payee);
            case CATEGORY -> text(e.category);
            case TAG -> text(e.tag);
            case DESCRIPTION -> text(e.description);
            case MEMO -> text(e.memo);
            case TYPE -> text(e.type);
            case STATUS -> e.status;
            case PAYMENT -> e.amountCents < 0 ? Money.format(-e.amountCents) : "";
            case DEPOSIT -> e.amountCents > 0 ? Money.format(e.amountCents) : "";
            case BALANCE -> isSavedRow(row) ? Money.format(e.balanceCents) : "";
            case PRINCIPAL_BALANCE -> isSavedRow(row) ? Money.format(e.principalBalanceCents) : "";
            case FILES -> e.attachments > 0 ? String.valueOf(e.attachments) : "";
            case DOWNLOAD -> e.download == null ? "" : e.download;     // drawn as an icon
            // A loan payment: how much of it was interest, and how much principal (what's owed went down by).
            case INTEREST -> e.interestCents != 0 ? Money.format(e.interestCents) : "";
            case PRINCIPAL -> e.amountCents > 0 ? Money.format(e.amountCents - e.interestCents) : "";
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
                boolean amountEntered = (column == PAYMENT || column == DEPOSIT || isLoanColumn(column))
                        && e.amountCents != 0;
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
            case BANK_DATE -> e.bankDate = text.isEmpty() ? null : parseDate(text);   // blank: not known
            case NUM -> e.checkNumber = text;
            case PAYEE -> e.payee = text;
            case CATEGORY -> e.category = text.isEmpty() ? null
                    : TransactionRepository.isTransfer(text) ? text      // "[Savings]" = transfer
                    : CategoryRepository.normalize(text);
            case TAG -> e.tag = text.isEmpty() ? null : TagRepository.normalize(text);
            case DESCRIPTION -> e.description = text;
            case MEMO -> e.memo = text;
            case TYPE -> e.type = TransactionRepository.normalizeType(text);
            case STATUS -> e.status = value instanceof TxnStatus s ? s : TxnStatus.NONE;
            case PAYMENT -> {
                if (!text.isEmpty()) {
                    e.amountCents = -Money.parseTyped(text);
                } else if (e.amountCents < 0) {
                    e.amountCents = 0;
                }
            }
            case DEPOSIT -> {
                if (!text.isEmpty()) {
                    e.amountCents = Money.parseTyped(text);
                } else if (e.amountCents > 0) {
                    e.amountCents = 0;
                }
            }
            case INTEREST, PRINCIPAL -> applyInterestOrPrincipal(e, column, text);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * A loan payment's interest or principal, typed over the calculated one.
     * With no payment yet (a new row), the typed amount is the payment, all
     * of it interest or all principal: an interest-only or principal-only
     * payment. Otherwise the payment stays as it is and the other part is
     * the rest of it. Blank: back to the calculated split.
     */
    private static void applyInterestOrPrincipal(LedgerEntry e, int column, String text) {
        if (text.isEmpty()) {
            e.interestOverride = null;                  // calculated again
            return;
        }
        long typed = Math.abs(Money.parseTyped(text));
        if (e.amountCents <= 0) {
            e.amountCents = typed;
            e.interestOverride = column == INTEREST ? typed : 0L;
        } else {
            if (typed > e.amountCents) {
                throw new IllegalArgumentException((column == INTEREST ? "Interest" : "Principal") + " of "
                        + Money.format(typed) + " is more than the payment of " + Money.format(e.amountCents)
                        + ". Change the payment first, or enter this on a new row.");
            }
            e.interestOverride = column == INTEREST ? typed : e.amountCents - typed;
        }
        e.interestCents = e.interestOverride;            // shown straight away; saving recalculates the rest
    }

    /** True if a row's interest and principal were typed in, not calculated. */
    public boolean isManualInterest(int row) {
        return isSavedRow(row) && entryAt(row).interestOverride != null;
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
            case BANK_DATE -> by(e -> e.bankDate, Comparator.<LocalDate>naturalOrder(), ascending);
            case NUM -> by(e -> blankToNull(e.checkNumber), LedgerTableModel::compareNumbers, ascending);
            case PAYEE -> by(e -> blankToNull(e.payee), String.CASE_INSENSITIVE_ORDER, ascending);
            case CATEGORY -> by(e -> blankToNull(e.category), String.CASE_INSENSITIVE_ORDER, ascending);
            case TAG -> by(e -> blankToNull(e.tag), String.CASE_INSENSITIVE_ORDER, ascending);
            case DESCRIPTION -> by(e -> blankToNull(e.description), String.CASE_INSENSITIVE_ORDER, ascending);
            case MEMO -> by(e -> blankToNull(e.memo), String.CASE_INSENSITIVE_ORDER, ascending);
            case TYPE -> by(e -> blankToNull(e.type), String.CASE_INSENSITIVE_ORDER, ascending);
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
            case PRINCIPAL_BALANCE -> by(e -> e.principalBalanceCents, Comparator.<Long>naturalOrder(), ascending);
            case FILES -> by(e -> e.attachments == 0 ? null : e.attachments, Comparator.<Integer>naturalOrder(), ascending);
            case DOWNLOAD -> by(e -> e.download, Comparator.<String>naturalOrder(), ascending);
            case INTEREST -> by(e -> e.interestCents, Comparator.<Long>naturalOrder(), ascending);
            case PRINCIPAL -> by(e -> e.amountCents > 0 ? e.amountCents - e.interestCents : null,
                    Comparator.<Long>naturalOrder(), ascending);
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
        long principal = balance;
        for (LedgerEntry e : all) {
            balance += e.netCents();                     // (a loan payment holding its interest: just its principal)
            e.balanceCents = balance;
            // A loan's principal: payments lower it by their principal part; the scheduled interest isn't principal.
            if (!e.scheduledInterest) {
                principal += e.amountCents > 0 ? e.amountCents - e.interestCents : e.amountCents;
            }
            e.principalBalanceCents = principal;
        }
    }

    /** True if the filter text appears in any column, as shown or (for amounts) as typed. */
    private boolean matchesText(LedgerEntry e) {
        if (filterText.isEmpty()) {
            return true;
        }
        String[] fields = filterColumn >= 0 ? fields(e, filterColumn) : new String[] {
            e.date == null ? "" : e.date.format(DISPLAY_DATE),
            e.bankDate == null ? "" : e.bankDate.format(DISPLAY_DATE),
            e.checkNumber, e.payee, e.category, e.tag, e.description, e.memo, e.type,
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

    /** What the filter looks at in one column: as shown, and (for amounts) as typed. */
    private static String[] fields(LedgerEntry e, int column) {
        return switch (column) {
            case DATE -> new String[] {e.date == null ? "" : e.date.format(DISPLAY_DATE)};
            case BANK_DATE -> new String[] {e.bankDate == null ? "" : e.bankDate.format(DISPLAY_DATE)};
            case NUM -> new String[] {e.checkNumber};
            case PAYEE -> new String[] {e.payee};
            case CATEGORY -> new String[] {e.category};
            case TAG -> new String[] {e.tag};
            case DESCRIPTION -> new String[] {e.description};
            case MEMO -> new String[] {e.memo};
            case TYPE -> new String[] {e.type};
            case STATUS -> new String[] {e.status == TxnStatus.NONE ? "" : e.status.label()};
            case PAYMENT -> e.amountCents < 0 ? amounts(-e.amountCents) : new String[0];
            case DEPOSIT -> e.amountCents > 0 ? amounts(e.amountCents) : new String[0];
            case BALANCE -> amounts(e.balanceCents);
            case PRINCIPAL_BALANCE -> amounts(e.principalBalanceCents);
            case INTEREST -> e.interestCents != 0 ? amounts(e.interestCents) : new String[0];
            case PRINCIPAL -> e.amountCents > 0 ? amounts(e.amountCents - e.interestCents) : new String[0];
            case FILES -> new String[] {e.attachments > 0 ? String.valueOf(e.attachments) : ""};
            case DOWNLOAD -> new String[] {"M".equals(e.download) ? "matched" : "N".equals(e.download) ? "new" : ""};
            default -> new String[0];
        };
    }

    private static String[] amounts(long cents) {
        return new String[] {Money.format(cents), plainAmount(cents)};
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

    /**
     * A typed date, read in the order of the date format (month first, day
     * first or year first): with /, - or . between the parts (10/11/2026,
     * 10-11-26; a two-digit year is 20xx), as 8 digits with nothing between
     * (10112026, or 20261011 year first), or without the year (10/11: this
     * year). Starting with a four-digit year is always year, month, day.
     */
    static LocalDate parseDate(String text) {
        String t = text.trim();
        boolean dayFirst = datePattern.startsWith("dd");
        try {
            if (t.matches("\\d{8}")) {
                int a = Integer.parseInt(t.substring(0, 2));
                int b = Integer.parseInt(t.substring(2, 4));
                if (datePattern.startsWith("yyyy")) {
                    return LocalDate.of(Integer.parseInt(t.substring(0, 4)), Integer.parseInt(t.substring(4, 6)),
                            Integer.parseInt(t.substring(6, 8)));
                }
                int year = Integer.parseInt(t.substring(4, 8));
                return dayFirst ? LocalDate.of(year, b, a) : LocalDate.of(year, a, b);
            }
            String[] p = t.split("[/.-]", -1);
            if ((p.length == 2 || p.length == 3) && java.util.Arrays.stream(p).allMatch(s -> s.matches("\\d{1,4}"))) {
                if (p.length == 3 && p[0].length() == 4) {
                    return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                }
                if (p[0].length() <= 2 && p[1].length() <= 2) {
                    int first = Integer.parseInt(p[0]);
                    int second = Integer.parseInt(p[1]);
                    int year = LocalDate.now().getYear();
                    if (p.length == 3) {
                        if (p[2].length() != 2 && p[2].length() != 4) {
                            throw new java.time.DateTimeException("year");
                        }
                        year = Integer.parseInt(p[2]) + (p[2].length() == 2 ? 2000 : 0);
                    }
                    return dayFirst ? LocalDate.of(year, second, first) : LocalDate.of(year, first, second);
                }
            }
        } catch (java.time.DateTimeException | NumberFormatException ignored) {
            // not a real date (like 02/30): said below
        }
        throw new IllegalArgumentException("\"" + text + "\" is not a valid date. Use " + dateFormatExample()
                + " (or 8 digits, like " + LocalDate.now().format(DateTimeFormatter.ofPattern(
                        datePattern.replaceAll("[^yMd]", ""))) + ").");
    }

    /**
     * Makes a date field tidy itself: as soon as 8 digits making a date are
     * typed, and when the field is left with any date that can be read, the
     * text becomes that date in the date format.
     */
    static void formatDatesIn(javax.swing.JTextField field) {
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                String t = field.getText().trim();
                if (t.matches("\\d{8}")) {
                    javax.swing.SwingUtilities.invokeLater(() -> tidy(field));   // not while the text is changing
                }
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                tidy(field);
            }
        });
    }

    private static void tidy(javax.swing.JTextField field) {
        String t = field.getText().trim();
        if (t.isEmpty()) {
            return;
        }
        try {
            String formatted = parseDate(t).format(DISPLAY_DATE);
            if (!formatted.equals(field.getText())) {
                field.setText(formatted);
            }
        } catch (IllegalArgumentException ignored) {
            // left as typed, for whoever reads it to report
        }
    }
}
