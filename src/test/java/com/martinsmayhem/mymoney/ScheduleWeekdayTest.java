package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schedules billed on the last day of the month, for a weekly service: the
 * amount is so much for each of a weekday in the month (88.49 per Tuesday:
 * 353.96 in August 2026, with 4; 442.45 in September 2026, with 5).
 */
class ScheduleWeekdayTest {

    @TempDir
    Path dir;
    private Database db;
    private ScheduleRepository schedules;
    private TransactionRepository transactions;
    private long checking;

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        schedules = new ScheduleRepository(db);
        transactions = new TransactionRepository(db);
        checking = new AccountRepository(db).insert("Checking", AccountType.CHECKING, 0, null);
    }

    private static Recurrence lastDayMonthly(LocalDate start) {
        return new Recurrence(Recurrence.Unit.MONTH, 1, 31, start, null, null);
    }

    private ScheduleRepository.Schedule cleaning(LocalDate start) {
        return new ScheduleRepository.Schedule(0, checking, "Cleaning Co", "Household:Cleaning", null, null, null,
                -8_849, lastDayMonthly(start), DayOfWeek.TUESDAY.getValue());
    }

    @Test
    void theBillsAsTheyCame() {
        ScheduleRepository.Schedule s = cleaning(LocalDate.of(2026, 8, 1));
        assertEquals(4, ScheduleRepository.Schedule.weekdaysIn(YearMonth.of(2026, 8), DayOfWeek.TUESDAY));
        assertEquals(5, ScheduleRepository.Schedule.weekdaysIn(YearMonth.of(2026, 9), DayOfWeek.TUESDAY));
        assertEquals(-35_396, s.amountOn(LocalDate.of(2026, 8, 31)));
        assertEquals(-44_245, s.amountOn(LocalDate.of(2026, 9, 30)));
        assertEquals("-$88.49 × Tuesdays", s.describeAmount());
        assertEquals(-8_849, new ScheduleRepository.Schedule(0, checking, null, null, null, null, null, -8_849,
                lastDayMonthly(LocalDate.of(2026, 8, 1))).amountOn(LocalDate.of(2026, 9, 30)), "a fixed amount: fixed");
    }

    @Test
    void everyWeekdayCountsRight() {
        for (int m = 1; m <= 12; m++) {
            YearMonth month = YearMonth.of(2026, m);
            for (DayOfWeek d : DayOfWeek.values()) {
                int counted = 0;
                for (int day = 1; day <= month.lengthOfMonth(); day++) {
                    if (month.atDay(day).getDayOfWeek() == d) {
                        counted++;
                    }
                }
                assertEquals(counted, ScheduleRepository.Schedule.weekdaysIn(month, d), month + " " + d);
            }
        }
    }

    @Test
    void theLastDayOfEveryMonthWhateverDayItStarted() {
        Recurrence r = lastDayMonthly(LocalDate.of(2027, 12, 10));
        assertEquals(List.of(LocalDate.of(2027, 12, 31), LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 29),
                LocalDate.of(2028, 3, 31), LocalDate.of(2028, 4, 30)),
                r.datesBetween(LocalDate.of(2027, 12, 1), LocalDate.of(2028, 4, 30)), "leap-year February too");
        assertEquals("Monthly (last day)", r.describe());
        Recurrence quarterly = new Recurrence(Recurrence.Unit.MONTH, 3, 31, LocalDate.of(2026, 2, 3), null, null);
        assertEquals(List.of(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 5, 31), LocalDate.of(2026, 8, 31)),
                quarterly.datesBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30)));
        Recurrence plain = new Recurrence(Recurrence.Unit.MONTH, 1, 0, LocalDate.of(2026, 2, 28), null, null);
        assertEquals(LocalDate.of(2026, 3, 28), plain.datesBetween(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)).get(0),
                "without it, the same day each month as before");
    }

    @Test
    void savedLoadedAndEnteredWithEachMonthsAmount() throws Exception {
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        long id = schedules.save(cleaning(first));
        ScheduleRepository.Schedule loaded = schedules.find(id);
        assertEquals(Integer.valueOf(DayOfWeek.TUESDAY.getValue()), loaded.perWeekday());
        assertTrue(loaded.recurrence().lastDayOfMonth());

        schedules.generate(100, new ArrayList<>());
        List<LedgerEntry> entered = transactions.findByAccount(checking);
        assertTrue(entered.size() >= 3, "three months ahead: " + entered.size());
        for (LedgerEntry e : entered) {
            assertEquals(e.date.lengthOfMonth(), e.date.getDayOfMonth(), "on the last day: " + e.date);
            int tuesdays = ScheduleRepository.Schedule.weekdaysIn(YearMonth.from(e.date), DayOfWeek.TUESDAY);
            assertEquals(-8_849L * tuesdays, e.amountCents, e.date + " has " + tuesdays + " Tuesdays");
            assertEquals(TxnStatus.SCHEDULED, e.status);
        }

        // Changing the rate changes the untouched upcoming ones.
        schedules.save(new ScheduleRepository.Schedule(id, checking, "Cleaning Co", "Household:Cleaning", null, null,
                null, -9_000, lastDayMonthly(first), DayOfWeek.TUESDAY.getValue()));
        schedules.generate(100, new ArrayList<>());
        for (LedgerEntry e : transactions.findByAccount(checking)) {
            assertEquals(-9_000L * ScheduleRepository.Schedule.weekdaysIn(YearMonth.from(e.date), DayOfWeek.TUESDAY),
                    e.amountCents);
        }
    }

    @Test
    void aFixedScheduleStaysFixed() throws Exception {
        long id = schedules.save(new ScheduleRepository.Schedule(0, checking, "Rent", null, null, null, null,
                -150_000, new Recurrence(Recurrence.Unit.MONTH, 1, 0, LocalDate.now(), null, null)));
        assertNull(schedules.find(id).perWeekday());
        assertTrue(!schedules.find(id).recurrence().lastDayOfMonth());
    }

    @Test
    void theCsvKeepsTheRule() throws Exception {
        Account a = new AccountRepository(db).findAll().get(0);
        String csv = ScheduleCsv.export(List.of(cleaning(LocalDate.of(2026, 11, 1))), Map.of(checking, "Checking"));
        assertTrue(csv.contains("Per Weekday") && csv.contains("Tuesday"), csv);
        ScheduleRepository.Schedule back = ScheduleCsv.parse(csv, List.of(a)).get(0).schedule();
        assertEquals(Integer.valueOf(2), back.perWeekday());
        assertTrue(back.recurrence().lastDayOfMonth());
        assertEquals(-8_849, back.amountCents());

        List<ScheduleCsv.Row> template = ScheduleCsv.parse(ScheduleCsv.template(List.of("Checking")), List.of(a));
        assertTrue(template.stream().allMatch(r -> r.problem() == null), "the template's examples all read");
        assertTrue(template.stream().anyMatch(r -> r.schedule().perWeekday() != null));
    }

    @Test
    void anOlderFileGetsTheColumn() throws Exception {
        long id = schedules.save(new ScheduleRepository.Schedule(0, checking, "Rent", null, null, null, null,
                -150_000, new Recurrence(Recurrence.Unit.MONTH, 1, 0, LocalDate.now(), null, null)));
        try (java.sql.Statement st = db.getConnection().createStatement()) {
            st.execute("ALTER TABLE schedule DROP COLUMN amount_weekday");
            st.execute("UPDATE meta SET value = '21' WHERE key = 'schema_version'");
        }
        db.close();
        db = Database.open(dir.resolve("T.MyMoney"), "password1");
        assertNull(new ScheduleRepository(db).find(id).perWeekday());
    }
}
