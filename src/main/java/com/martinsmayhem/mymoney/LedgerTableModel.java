package com.martinsmayhem.mymoney;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.table.AbstractTableModel;

/**
 * The ledger for one account, edited in place.
 *
 * Every saved transaction is a row, followed by one blank row for entering a
 * new transaction. The blank row is saved as soon as it gets a Payment or
 * Deposit amount, and a fresh blank row appears below it. Edits to saved rows
 * are written to the database as soon as each cell is changed.
 *
 * A reconciled transaction is locked: only its status can be changed, and
 * changing it away from Reconciled must be confirmed.
 */
public class LedgerTableModel extends AbstractTableModel {

    public static final int DATE = 0;
    public static final int NUM = 1;
    public static final int PAYEE = 2;
    public static final int CATEGORY = 3;
    public static final int DESCRIPTION = 4;
    public static final int MEMO = 5;
    public static final int STATUS = 6;
    public static final int PAYMENT = 7;
    public static final int DEPOSIT = 8;
    public static final int BALANCE = 9;

    private static final String[] COLUMNS = {
        "Date", "Num", "Payee", "Category", "Description", "Memo", "Status",
        "Payment", "Deposit", "Balance"
    };

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

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
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final List<Long> balances = new ArrayList<>();   // running balance per entry
    private LedgerEntry draft = newDraft(LocalDate.now());   // the blank row

    public LedgerTableModel(TransactionRepository transactions,
                            Consumer<String> onError, Predicate<String> confirm,
                            Runnable onChanged) {
        this.transactions = transactions;
        this.onError = onError;
        this.confirm = confirm;
        this.onChanged = onChanged;
    }

    /** Shows a different account's ledger (or nothing, for null). */
    public void setAccount(Account account) {
        this.account = account;
        this.draft = newDraft(LocalDate.now());
        load();
        fireTableDataChanged();
    }

    /** True for a saved transaction, false for the blank entry row. */
    public boolean isSavedRow(int row) {
        return row >= 0 && row < entries.size();
    }

    /** True for a reconciled transaction, which can't be edited or deleted. */
    public boolean isLocked(int row) {
        return isSavedRow(row) && entries.get(row).status == TxnStatus.RECONCILED;
    }

    public void deleteRow(int row) {
        if (!isSavedRow(row) || isLocked(row)) {
            return;
        }
        try {
            transactions.delete(entries.get(row).id);
        } catch (SQLException e) {
            onError.accept("Could not delete the transaction:\n" + e.getMessage());
        }
        refresh();
        onChanged.run();
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
        if (account == null || column == BALANCE) {
            return false;
        }
        if (isLocked(row)) {
            return column == STATUS;            // reconciled: only the status can change
        }
        return true;
    }

    @Override
    public Object getValueAt(int row, int column) {
        LedgerEntry e = isSavedRow(row) ? entries.get(row) : draft;
        return switch (column) {
            case DATE -> e.date == null ? "" : e.date.format(DISPLAY_DATE);
            case NUM -> text(e.checkNumber);
            case PAYEE -> text(e.payee);
            case CATEGORY -> text(e.category);
            case DESCRIPTION -> text(e.description);
            case MEMO -> text(e.memo);
            case STATUS -> e.status;
            case PAYMENT -> e.amountCents < 0 ? Money.format(-e.amountCents) : "";
            case DEPOSIT -> e.amountCents > 0 ? Money.format(e.amountCents) : "";
            case BALANCE -> isSavedRow(row) ? Money.format(balances.get(row)) : "";
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
        String text = value == null ? "" : value.toString().trim();
        boolean isDraft = !isSavedRow(row);
        LedgerEntry e = isDraft ? draft : entries.get(row);

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

        // 1. Put the typed value into the entry. Bad input changes nothing.
        try {
            switch (column) {
                case DATE -> e.date = parseDate(text);
                case NUM -> e.checkNumber = text;
                case PAYEE -> e.payee = text;
                case CATEGORY -> e.category = text.isEmpty() ? null : CategoryRepository.normalize(text);
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
                    return;
                }
            }
        } catch (IllegalArgumentException ex) {
            onError.accept(ex.getMessage());
            return;
        }

        // 2. Save it.
        try {
            if (isDraft) {
                boolean amountEntered = (column == PAYMENT || column == DEPOSIT) && e.amountCents != 0;
                if (!amountEntered) {
                    fireTableRowsUpdated(row, row);      // keep filling in the blank row
                    return;
                }
                transactions.insert(account.id(), draft);
                draft = newDraft(draft.date);           // next entry starts on the same date
            } else {
                transactions.update(e);
            }
        } catch (SQLException ex) {
            onError.accept("Could not save the transaction:\n" + ex.getMessage());
        }
        refresh();
        onChanged.run();
    }

    // ---------------------------------------------------------------------

    /** Re-reads the account's transactions and recomputes running balances. */
    private void load() {
        entries.clear();
        balances.clear();
        if (account == null) {
            return;
        }
        try {
            entries.addAll(transactions.findByAccount(account.id()));
        } catch (SQLException e) {
            onError.accept("Could not load transactions:\n" + e.getMessage());
        }
        long balance = account.openingBalanceCents();
        for (LedgerEntry e : entries) {
            balance += e.amountCents;
            balances.add(balance);
        }
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
