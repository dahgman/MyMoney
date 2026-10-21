package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Paychecks (main, secondary, ...) and what's due between them: the
 * periods, which transactions count, the settings, and the ledger's Show:.
 */
class PaychecksTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private ScheduleRepository schedules;
    private TransactionRepository transactions;
    private Paychecks paychecks;
    private long checking;
    private long savings;

    /** Every other Friday, from Friday 2 October 2026. */
    private static final Recurrence BIWEEKLY =
            new Recurrence(Recurrence.Unit.WEEK, 2, 0, LocalDate.of(2026, 10, 2), null, null);

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        accounts = new AccountRepository(db);
        schedules = new ScheduleRepository(db);
        transactions = new TransactionRepository(db);
        paychecks = new Paychecks(db);
        checking = accounts.insert("Checking", AccountType.CHECKING, 100_000, null);
        savings = accounts.insert("Savings", AccountType.SAVINGS, 0, null);
    }

    private ScheduleRepository.Schedule paycheck(String payee, Recurrence r, Integer rank) {
        return new ScheduleRepository.Schedule(0, checking, payee, "Income:Salary", null, null, null,
                250_000, r, null, rank);
    }

    private LedgerEntry txn(long account, String payee, long cents, String category, TxnStatus status,
            LocalDate date) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = date;
        e.payee = payee;
        e.amountCents = cents;
        e.category = category;
        e.status = status;
        transactions.insert(account, e);
        return e;
    }

    @Test
    void thePeriods() {
        ScheduleRepository.Schedule p = paycheck("Employer", BIWEEKLY, 1);
        LocalDate wed = LocalDate.of(2026, 10, 21);
        Paychecks.Period until = Paychecks.period(p, Paychecks.Mode.UNTIL_NEXT, wed);
        assertEquals(wed, until.from());
        assertEquals(LocalDate.of(2026, 10, 30), until.payday());
        assertEquals(LocalDate.of(2026, 10, 29), until.last());

        Paychecks.Period next = Paychecks.period(p, Paychecks.Mode.NEXT_PERIOD, wed);
        assertEquals(LocalDate.of(2026, 10, 30), next.from());
        assertEquals(LocalDate.of(2026, 11, 12), next.last());
        assertTrue(next.contains(LocalDate.of(2026, 10, 30)), "a bill due on payday is that paycheck's");
        assertFalse(next.contains(LocalDate.of(2026, 11, 13)));

        // On payday itself, until-next runs to the following one.
        Paychecks.Period onPayday = Paychecks.period(p, Paychecks.Mode.UNTIL_NEXT, LocalDate.of(2026, 10, 30));
        assertEquals(LocalDate.of(2026, 11, 13), onPayday.payday());

        Recurrence ended = new Recurrence(Recurrence.Unit.WEEK, 2, 0, LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 3, 1), null);
        assertNull(Paychecks.period(paycheck("Old job", ended, 2), Paychecks.Mode.UNTIL_NEXT, wed));
    }

    @Test
    void rankNames() {
        assertEquals("Main", Paychecks.rankName(1));
        assertEquals("Secondary", Paychecks.rankName(2));
        assertEquals("Tertiary", Paychecks.rankName(3));
        assertEquals("4th", Paychecks.rankName(4));
    }

    @Test
    void settingsAreKeptAndStartOff() throws Exception {
        assertEquals(Paychecks.Settings.OFF, paychecks.settings());
        paychecks.save(new Paychecks.Settings(true, 2, Paychecks.Mode.NEXT_PERIOD));
        assertEquals(new Paychecks.Settings(true, 2, Paychecks.Mode.NEXT_PERIOD), new Paychecks(db).settings());
    }

    @Test
    void offMeansNoPeriod() throws Exception {
        schedules.save(paycheck("Employer", BIWEEKLY, 1));
        assertNull(paychecks.current(Paychecks.Mode.UNTIL_NEXT, LocalDate.of(2026, 10, 21)));
        paychecks.save(new Paychecks.Settings(true, 1, Paychecks.Mode.UNTIL_NEXT));
        assertEquals(LocalDate.of(2026, 10, 30),
                paychecks.current(Paychecks.Mode.UNTIL_NEXT, LocalDate.of(2026, 10, 21)).payday());
        paychecks.save(new Paychecks.Settings(true, 3, Paychecks.Mode.UNTIL_NEXT));
        assertNull(paychecks.current(Paychecks.Mode.UNTIL_NEXT, LocalDate.of(2026, 10, 21)), "no tertiary paycheck");
    }

    @Test
    void oneScheduleToARank() throws Exception {
        long main = schedules.save(paycheck("Employer", BIWEEKLY, 1));
        long side = schedules.save(paycheck("Side job", BIWEEKLY, 2));
        assertEquals(List.of(main, side), paychecks.paychecks().stream().map(ScheduleRepository.Schedule::id).toList());
        schedules.save(paycheck("New job", BIWEEKLY, 1));
        assertNull(schedules.find(main).paycheck(), "the old main paycheck isn't one any more");
        assertEquals(Integer.valueOf(2), schedules.find(side).paycheck());
        assertEquals("New job", paychecks.paycheck(1).payee());
    }

    @Test
    void whatsDue() throws Exception {
        LocalDate wed = LocalDate.of(2026, 10, 21);
        txn(checking, "Power", -9_000, "Utilities", TxnStatus.SCHEDULED, LocalDate.of(2026, 10, 25));
        txn(checking, "Gas", -4_000, "Auto", TxnStatus.PENDING, LocalDate.of(2026, 10, 22));
        txn(checking, "Store", -1_000, "Food", TxnStatus.NONE, wed);
        txn(checking, "Cleared", -500, "Food", TxnStatus.CLEARED, LocalDate.of(2026, 10, 23));
        txn(checking, "Reconciled", -500, "Food", TxnStatus.RECONCILED, LocalDate.of(2026, 10, 23));
        txn(checking, "Later", -500, "Food", TxnStatus.SCHEDULED, LocalDate.of(2026, 10, 30));
        txn(checking, "Earlier", -500, "Food", TxnStatus.SCHEDULED, LocalDate.of(2026, 10, 20));
        txn(checking, null, -20_000, "[Savings]", TxnStatus.SCHEDULED, LocalDate.of(2026, 10, 26));
        long closed = accounts.insert("Old", AccountType.CHECKING, 0, null);
        txn(closed, "Gone", -700, "Food", TxnStatus.SCHEDULED, LocalDate.of(2026, 10, 24));
        try (java.sql.Statement st = db.getConnection().createStatement()) {
            st.execute("UPDATE account SET closed = 1 WHERE id = " + closed);
        }

        Paychecks.Period p = Paychecks.period(paycheck("Employer", BIWEEKLY, 1), Paychecks.Mode.UNTIL_NEXT, wed);
        List<Paychecks.Upcoming> due = paychecks.upcoming(p);
        assertEquals(List.of("Store", "Gas", "Power", "Checking"),
                due.stream().map(u -> u.entry().payee == null ? u.account().name() : u.entry().payee).toList(),
                "not cleared, in the period, open accounts, a transfer once (from Checking)");
        assertEquals(-20_000, due.get(3).entry().amountCents);
    }

    @Test
    void theLedgerShowsThePeriod() throws Exception {
        LocalDate wed = LocalDate.of(2026, 10, 21);
        txn(checking, "Power", -9_000, "Utilities", TxnStatus.SCHEDULED, LocalDate.of(2026, 10, 25));
        txn(checking, "Cleared", -500, "Food", TxnStatus.CLEARED, LocalDate.of(2026, 10, 23));
        txn(checking, "Later", -500, "Food", TxnStatus.SCHEDULED, LocalDate.of(2026, 11, 20));
        LedgerTableModel model = new LedgerTableModel(transactions, m -> {
            throw new AssertionError(m);
        }, q -> true, () -> { });
        model.setAccount(accounts.findAll().stream().filter(a -> a.id() == checking).findFirst().orElseThrow());
        assertEquals(3, model.shownCount());
        model.setPeriod(Paychecks.period(paycheck("Employer", BIWEEKLY, 1), Paychecks.Mode.UNTIL_NEXT, wed));
        assertEquals(1, model.shownCount());
        assertTrue(model.isFiltered());
        model.setPeriod(null);
        assertEquals(3, model.shownCount());
    }

    @Test
    void theCsvKeepsThePaycheck() throws Exception {
        Account a = accounts.findAll().get(0);
        String csv = ScheduleCsv.export(List.of(paycheck("Employer", BIWEEKLY, 2)), Map.of(checking, "Checking"));
        assertTrue(csv.contains("Paycheck"), csv);
        assertEquals(Integer.valueOf(2), ScheduleCsv.parse(csv, List.of(a)).get(0).schedule().paycheck());
        List<ScheduleCsv.Row> template = ScheduleCsv.parse(ScheduleCsv.template(List.of("Checking")), List.of(a));
        assertTrue(template.stream().allMatch(r -> r.problem() == null));
        assertTrue(template.stream().anyMatch(r -> Integer.valueOf(1).equals(r.schedule().paycheck())));
    }

    @Test
    void anOlderFileGetsTheColumn() throws Exception {
        long id = schedules.save(paycheck("Employer", BIWEEKLY, null));
        try (java.sql.Statement st = db.getConnection().createStatement()) {
            st.execute("ALTER TABLE schedule DROP COLUMN paycheck");
            st.execute("UPDATE meta SET value = '22' WHERE key = 'schema_version'");
        }
        db.close();
        db = Database.open(dir.resolve("T.MyMoney"), "password1");
        assertNull(new ScheduleRepository(db).find(id).paycheck());
        assertEquals(Database.SCHEMA_VERSION, 23);
    }
}
