package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fewer days ahead: scheduled transactions already created past the new
 * limit are taken back if untouched, and created again in time; edited,
 * entered or cleared ones stay.
 */
class ScheduleTrimTest {

    @TempDir
    Path dir;
    private Database db;
    private ScheduleRepository schedules;
    private TransactionRepository transactions;
    private long checking;
    private long savings;
    private final LocalDate today = LocalDate.now();

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        schedules = new ScheduleRepository(db);
        transactions = new TransactionRepository(db);
        AccountRepository accounts = new AccountRepository(db);
        checking = accounts.insert("Checking", AccountType.CHECKING, 0, null);
        savings = accounts.insert("Savings", AccountType.SAVINGS, 0, null);
    }

    /** Every day from tomorrow: one occurrence per day ahead. */
    private long daily(String payee, String category) throws Exception {
        return schedules.save(new ScheduleRepository.Schedule(0, checking, payee, category, null, null, "memo",
                -1_000, new Recurrence(Recurrence.Unit.DAY, 1, 0, today.plusDays(1), null, null)));
    }

    private List<LedgerEntry> dated(long account, String payee) throws Exception {
        return transactions.findByAccount(account).stream().filter(e -> payee.equals(e.payee)).toList();
    }

    @Test
    void fewerDaysTakesBackTheUntouchedOnes() throws Exception {
        daily("Coffee", "Food");
        schedules.generate(30, new ArrayList<>());
        assertEquals(30, dated(checking, "Coffee").size());

        assertEquals(20, schedules.trim(10));
        List<LedgerEntry> left = dated(checking, "Coffee");
        assertEquals(10, left.size());
        assertTrue(left.stream().allMatch(e -> !e.date.isAfter(today.plusDays(10))));

        // More days again: created again, once each.
        schedules.generate(30, new ArrayList<>());
        assertEquals(30, dated(checking, "Coffee").size());
        assertEquals(0, schedules.trim(30), "nothing past the limit");
    }

    @Test
    void touchedOnesStay() throws Exception {
        daily("Coffee", "Food");
        schedules.generate(30, new ArrayList<>());
        List<LedgerEntry> all = dated(checking, "Coffee");
        LedgerEntry edited = all.get(20);                 // a new amount, still Scheduled
        edited.amountCents = -1_250;
        transactions.update(edited);
        LedgerEntry entered = all.get(21);                // entered: no longer Scheduled
        entered.status = TxnStatus.NONE;
        transactions.update(entered);
        LedgerEntry noted = all.get(22);                  // a different memo
        noted.memo = "with a friend";
        transactions.update(noted);

        assertEquals(17, schedules.trim(10));
        List<Long> left = dated(checking, "Coffee").stream().map(e -> e.id).toList();
        assertEquals(13, left.size());
        assertTrue(left.containsAll(List.of(edited.id, entered.id, noted.id)));

        schedules.generate(30, new ArrayList<>());        // back to 30: no doubles beside the kept ones
        assertEquals(30, dated(checking, "Coffee").size());
    }

    @Test
    void aTransferGoesFromBothAccounts() throws Exception {
        daily("To savings", "[Savings]");
        schedules.generate(20, new ArrayList<>());
        assertEquals(20, transactions.findByAccount(savings).size());
        schedules.trim(5);
        assertEquals(5, dated(checking, "To savings").size());
        assertEquals(5, transactions.findByAccount(savings).size(), "the other side goes too");
    }

    @Test
    void perWeekdayAmountsCountAsUntouched() throws Exception {
        schedules.save(new ScheduleRepository.Schedule(0, checking, "Cleaning Co", null, null, null, null, -8_849,
                new Recurrence(Recurrence.Unit.MONTH, 1, 31, today, null, null), java.time.DayOfWeek.TUESDAY.getValue()));
        schedules.generate(100, new ArrayList<>());
        int created = dated(checking, "Cleaning Co").size();
        assertTrue(created >= 3);
        schedules.trim(0);
        assertTrue(dated(checking, "Cleaning Co").size() <= 1, "each month's own amount is the schedule's");
    }
}
