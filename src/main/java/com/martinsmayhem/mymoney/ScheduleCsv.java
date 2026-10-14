package com.martinsmayhem.mymoney;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scheduled transactions as CSV (comma-separated values, for a spreadsheet):
 * Export writes every schedule, Import reads them back, and the Template is
 * the same columns with instructions and examples.
 *
 * Columns (the header row names them; their order doesn't matter):
 *   Account, Payee, Category, Tag, Description, Memo, Payment, Deposit,
 *   Repeats, Every, Unit, Second Day, First Date, End Date, Times
 *
 * Repeats is one of Daily, Weekly, Every 2 weeks, Twice a month, Monthly,
 * Every 3 months, Every 6 months, Yearly, or Custom (with Every = a number
 * and Unit = Days, Weeks, Months or Years). Twice a month needs Second Day
 * (1-31; 31 = the last day). It ends on End Date, or after Times times, or
 * (both blank) never. Lines starting with # are comments.
 */
final class ScheduleCsv {

    static final String[] COLUMNS = {"Account", "Payee", "Category", "Tag", "Description", "Memo", "Payment",
        "Deposit", "Repeats", "Every", "Unit", "Second Day", "First Date", "End Date", "Times"};

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private ScheduleCsv() {
    }

    /** One line read from a file: the schedule, or why it couldn't be read. */
    record Row(int line, String account, String payee, String amount, String repeats, String firstDate,
               ScheduleRepository.Schedule schedule, String problem) {
    }

    // ----- Writing -------------------------------------------------------------

    /** Every schedule, one per line, after the header. */
    static String export(List<ScheduleRepository.Schedule> schedules, Map<Long, String> accountNames) {
        StringBuilder out = new StringBuilder();
        line(out, COLUMNS);
        for (ScheduleRepository.Schedule s : schedules) {
            line(out, values(s, accountNames.getOrDefault(s.accountId(), "")));
        }
        return out.toString();
    }

    /** The template: instructions (as # comments), the header, and example rows using a real account. */
    static String template(List<String> accountNames) {
        String acct = accountNames.isEmpty() ? "Checking" : accountNames.get(0);
        StringBuilder out = new StringBuilder();
        for (String c : new String[] {
            "# MyMoney scheduled transactions. Lines starting with # are ignored; delete the examples and add your own.",
            "# Account: the name of an account in MyMoney" + (accountNames.isEmpty() ? "."
                    : " (yours: " + String.join(", ", accountNames) + ")."),
            "# Category: a category like Food:Groceries, or [Account Name] for a transfer. Payee, Tag, Description, Memo: optional.",
            "# Payment or Deposit: the amount in one of them, like 1500.00.",
            "# Repeats: Daily, Weekly, Every 2 weeks, Twice a month, Monthly, Every 3 months, Every 6 months, Yearly, or Custom.",
            "#   Custom: Every = a number and Unit = Days, Weeks, Months or Years (e.g. every 10 days).",
            "#   Twice a month: on First Date's day and on Second Day (1-31; 31 = the last day of the month).",
            "# First Date: the first time it happens (MM/DD/YYYY). Monthly on the 31st falls on the last day of shorter months.",
            "# Ends: put a date in End Date, or a number in Times (how many times in all), or leave both blank for never."}) {
            out.append(c).append("\r\n");
        }
        line(out, COLUMNS);
        LocalDate first = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        String[][] examples = {
            {acct, "Landlord", "Housing:Rent", "", "", "", "1500.00", "", "Monthly", "", "", "",
                d(first), "", ""},
            {acct, "Employer", "Income:Salary", "", "", "", "", "2500.00", "Every 2 weeks", "", "", "",
                d(first.plusDays(4)), "", ""},
            {acct, "Employer", "Income:Salary", "", "Paid on the 15th and last day", "", "", "1800.00",
                "Twice a month", "", "", "31", d(first.plusDays(14)), "", ""},
            {acct, "Car Loan Co", "Auto:Loan", "", "", "", "350.00", "", "Monthly", "", "", "", d(first.plusDays(9)),
                "", "36"},
            {acct, "Insurance Co", "Insurance:Auto", "", "", "", "600.00", "", "Every 6 months", "", "", "",
                d(first.plusDays(19)), d(first.plusYears(3)), ""},
            {acct, "Water Dept", "Utilities:Water", "", "", "", "45.00", "", "Custom", "3", "Weeks", "",
                d(first.plusDays(6)), "", ""},
            {acct, "Streaming Co", "Entertainment", "", "", "", "15.99", "", "Yearly", "", "", "",
                d(first.plusDays(2)), "", ""},
        };
        for (String[] e : examples) {
            line(out, e);
        }
        return out.toString();
    }

    private static String[] values(ScheduleRepository.Schedule s, String account) {
        Recurrence r = s.recurrence();
        Recurrence.Preset p = Recurrence.Preset.of(r.unit(), r.interval());
        boolean custom = p == Recurrence.Preset.CUSTOM;
        return new String[] {
            account, text(s.payee()), text(s.category()), text(s.tag()), text(s.description()), text(s.memo()),
            s.amountCents() < 0 ? plain(-s.amountCents()) : "",
            s.amountCents() > 0 ? plain(s.amountCents()) : "",
            custom ? "Custom" : p.label,
            custom ? String.valueOf(r.interval()) : "",
            custom ? unitName(r.unit()) : "",
            r.unit() == Recurrence.Unit.SEMIMONTH ? String.valueOf(r.secondDay()) : "",
            d(r.start()),
            r.endDate() == null ? "" : d(r.endDate()),
            r.maxCount() == null ? "" : String.valueOf(r.maxCount())
        };
    }

    private static void line(StringBuilder out, String[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            String v = values[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")
                    || v.startsWith(" ") || v.endsWith(" ")) {
                v = "\"" + v.replace("\"", "\"\"") + "\"";
            }
            out.append(v);
        }
        out.append("\r\n");
    }

    private static String d(LocalDate date) {
        return date.format(DATE);
    }

    /** 150000 -> "1500.00" (no $ or commas, so spreadsheets read it as a number). */
    private static String plain(long cents) {
        return java.math.BigDecimal.valueOf(cents, 2).toPlainString();
    }

    private static String unitName(Recurrence.Unit u) {
        return u.name().charAt(0) + u.name().substring(1).toLowerCase(Locale.ROOT) + "s";
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    // ----- Reading -------------------------------------------------------------

    /**
     * Reads a file's schedules. Each data line becomes a Row: with a
     * Schedule if it is good, otherwise with the problem. Throws
     * IllegalArgumentException if the header is missing required columns.
     */
    static List<Row> parse(String text, List<Account> accounts) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);                   // byte-order mark (Excel)
        }
        List<List<String>> records = records(text);
        Map<String, Integer> col = null;
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            List<String> r = records.get(i);
            int lineNo = lineOf(text, i);
            if (r.isEmpty() || (r.size() == 1 && r.get(0).isBlank()) || r.get(0).trim().startsWith("#")) {
                continue;                               // blank or comment
            }
            if (col == null) {
                col = header(r);
                continue;
            }
            rows.add(row(lineNo, r, col, accounts));
        }
        if (col == null) {
            throw new IllegalArgumentException("The file has no header row (Account, Payee, ...).");
        }
        return rows;
    }

    private static Map<String, Integer> header(List<String> r) {
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < r.size(); i++) {
            col.put(r.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        List<String> missing = new ArrayList<>();
        for (String need : new String[] {"Account", "Repeats", "First Date"}) {
            if (!col.containsKey(need.toLowerCase(Locale.ROOT))) {
                missing.add(need);
            }
        }
        if (!col.containsKey("payment") && !col.containsKey("deposit")) {
            missing.add("Payment or Deposit");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The header row is missing: " + String.join(", ", missing)
                    + ".\nUse the template (Template... button) for the right columns.");
        }
        return col;
    }

    private static Row row(int lineNo, List<String> r, Map<String, Integer> col, List<Account> accounts) {
        String accountName = get(r, col, "Account");
        String payee = get(r, col, "Payee");
        String payment = get(r, col, "Payment");
        String deposit = get(r, col, "Deposit");
        String repeats = get(r, col, "Repeats");
        String first = get(r, col, "First Date");
        String shownAmount = !payment.isEmpty() ? "-" + payment : deposit;
        try {
            Account account = null;
            for (Account a : accounts) {
                if (a.name().equalsIgnoreCase(accountName)) {
                    account = a;
                }
            }
            if (accountName.isEmpty()) {
                throw new IllegalArgumentException("Account is empty.");
            }
            if (account == null) {
                throw new IllegalArgumentException("There is no account named \"" + accountName + "\".");
            }
            if (!payment.isEmpty() && !deposit.isEmpty()) {
                throw new IllegalArgumentException("Payment and Deposit are both filled in; use one.");
            }
            long amount = !payment.isEmpty() ? -Math.abs(Money.parse(payment))
                    : !deposit.isEmpty() ? Math.abs(Money.parse(deposit)) : 0;
            if (amount == 0) {
                throw new IllegalArgumentException("Payment or Deposit needs an amount.");
            }
            String category = get(r, col, "Category");
            if (TransactionRepository.isTransfer(category)) {
                String other = category.substring(1, category.length() - 1).trim();
                if (other.equalsIgnoreCase(account.name())) {
                    throw new IllegalArgumentException("An account can't transfer to itself.");
                }
                if (accounts.stream().noneMatch(a -> a.name().equalsIgnoreCase(other))) {
                    throw new IllegalArgumentException("There is no account named \"" + other + "\" to transfer to.");
                }
            } else if (!category.isEmpty()) {
                category = CategoryRepository.normalize(category);
            }
            String tag = get(r, col, "Tag");
            Recurrence rec = recurrence(repeats, get(r, col, "Every"), get(r, col, "Unit"),
                    get(r, col, "Second Day"), first, get(r, col, "End Date"), get(r, col, "Times"));
            ScheduleRepository.Schedule s = new ScheduleRepository.Schedule(0, account.id(), blank(payee),
                    blank(category), tag.isEmpty() ? null : TagRepository.normalize(tag),
                    blank(get(r, col, "Description")), blank(get(r, col, "Memo")), amount, rec);
            return new Row(lineNo, accountName, payee, shownAmount, repeats, first, s, null);
        } catch (IllegalArgumentException e) {
            return new Row(lineNo, accountName, payee, shownAmount, repeats, first, null, e.getMessage());
        }
    }

    /** Reads the schedule columns. @throws IllegalArgumentException with a message fit to show */
    static Recurrence recurrence(String repeats, String every, String unit, String secondDay, String first,
                                 String end, String times) {
        Recurrence.Unit u;
        int interval;
        String key = repeats.trim().toLowerCase(Locale.ROOT).replace("-", " ").replaceAll("\\s+", " ");
        switch (key) {
            case "daily" -> { u = Recurrence.Unit.DAY; interval = 1; }
            case "weekly" -> { u = Recurrence.Unit.WEEK; interval = 1; }
            case "every 2 weeks", "biweekly", "bi weekly", "every other week", "fortnightly" -> { u = Recurrence.Unit.WEEK; interval = 2; }
            case "twice a month", "semimonthly", "semi monthly" -> { u = Recurrence.Unit.SEMIMONTH; interval = 1; }
            case "monthly" -> { u = Recurrence.Unit.MONTH; interval = 1; }
            case "every 3 months", "quarterly" -> { u = Recurrence.Unit.MONTH; interval = 3; }
            case "every 6 months", "semiannually", "semi annually", "twice a year" -> {
                u = Recurrence.Unit.MONTH;
                interval = 6;
            }
            case "yearly", "annually", "every year" -> { u = Recurrence.Unit.YEAR; interval = 1; }
            case "custom", "every...", "every" -> {
                u = switch (unit.trim().toLowerCase(Locale.ROOT).replaceAll("s$", "")) {
                    case "day" -> Recurrence.Unit.DAY;
                    case "week" -> Recurrence.Unit.WEEK;
                    case "month" -> Recurrence.Unit.MONTH;
                    case "year" -> Recurrence.Unit.YEAR;
                    default -> throw new IllegalArgumentException("Custom needs Unit: Days, Weeks, Months or Years"
                            + (unit.isBlank() ? "." : " (not \"" + unit + "\")."));
                };
                interval = number(every, "Every", 1, 999);
            }
            case "" -> throw new IllegalArgumentException("Repeats is empty.");
            default -> throw new IllegalArgumentException("Repeats \"" + repeats + "\" isn't one of: Daily, Weekly,"
                    + " Every 2 weeks, Twice a month, Monthly, Every 3 months, Every 6 months, Yearly, Custom.");
        }
        int second = 0;
        if (u == Recurrence.Unit.SEMIMONTH) {
            second = number(secondDay, "Second Day", 1, 31);
        }
        if (first.isBlank()) {
            throw new IllegalArgumentException("First Date is empty.");
        }
        LocalDate start = date(first, "First Date");
        LocalDate endDate = end.isBlank() ? null : date(end, "End Date");
        Integer count = times.isBlank() ? null : number(times, "Times", 1, 9999);
        if (endDate != null && count != null) {
            throw new IllegalArgumentException("Fill in End Date or Times, not both.");
        }
        if (endDate != null && endDate.isBefore(start)) {
            throw new IllegalArgumentException("End Date is before First Date.");
        }
        return new Recurrence(u, interval, second, start, endDate, count);
    }

    private static int number(String text, String what, int min, int max) {
        try {
            int n = Integer.parseInt(text.trim());
            if (n < min || n > max) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " needs a whole number from " + min + " to " + max
                    + (text.isBlank() ? "." : " (not \"" + text + "\")."));
        }
    }

    private static LocalDate date(String text, String what) {
        try {
            return LocalDate.parse(text.trim(), DATE);     // the file's own format, whatever the preference
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(what + " \"" + text + "\" isn't a date (use MM/DD/YYYY).");
        }
    }

    private static String get(List<String> r, Map<String, Integer> col, String name) {
        Integer i = col.get(name.toLowerCase(Locale.ROOT));
        return i == null || i >= r.size() ? "" : r.get(i).trim();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ----- CSV -------------------------------------------------------------------

    /** Splits CSV text into records of fields (quotes may hold commas, quotes and line breaks). */
    static List<List<String>> records(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    f.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    f.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(f.toString());
                f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(f.toString());
                f.setLength(0);
                records.add(fields);
                fields = new ArrayList<>();
            } else {
                f.append(c);
            }
        }
        if (f.length() > 0 || !fields.isEmpty()) {
            fields.add(f.toString());
            records.add(fields);
        }
        return records;
    }

    /** The file line a record starts on (1-based), counting line breaks outside quotes. */
    private static int lineOf(String text, int record) {
        int line = 1;
        int seen = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length() && seen < record; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '\n') {
                seen++;
            }
            if (c == '\n') {
                line++;
            }
        }
        return line;
    }
}
