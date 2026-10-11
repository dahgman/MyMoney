package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a QIF (Quicken Interchange Format) file into plain objects.
 * Nothing is written to the database here; see QifImporter for that.
 *
 * Handles bank, cash, credit card and other asset/liability transactions,
 * account lists (including multi-account "AutoSwitch" exports), the
 * category list, and the class list (MyMoney tags). Investment transactions
 * are counted and skipped.
 *
 * A category field may end with "/" and a tag (QIF calls it a class), like
 * "Food:Groceries/Vacation". Only the first tag is kept if there are several.
 */
final class QifParser {

    // ----- What a QIF file contains --------------------------------------

    static final class QifAccount {
        String name;
        String type;              // Bank, Cash, CCard, Oth A, Oth L, Invst, ...
    }

    static final class QifCategory {
        String name;
        boolean income;
    }

    static final class QifSplit {
        String category;          // category path, "[Account]" for a transfer, or null
        String tag;
        String memo;
        long amountCents;
    }

    static final class QifTransaction {
        String rawDate;
        LocalDate date;
        long amountCents;
        boolean hasAmount;
        TxnStatus status = TxnStatus.NONE;
        String number;
        String payee;
        String memo;
        String category;          // category path, "[Account]" for a transfer, or null
        String tag;               // first tag after "/" (or the first split's tag)
        final List<QifSplit> splits = new ArrayList<>();
    }

    /** Transactions that follow one "!Type:" header. */
    static final class Block {
        final QifAccount account;           // null if the file never named one
        final String type;
        final List<QifTransaction> transactions = new ArrayList<>();

        Block(QifAccount account, String type) {
            this.account = account;
            this.type = type;
        }
    }

    static final class QifFile {
        final List<QifAccount> accounts = new ArrayList<>();
        final List<QifCategory> categories = new ArrayList<>();
        final List<String> tags = new ArrayList<>();             // from the class list
        final List<Block> blocks = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int investmentSkipped;

        /** True if some transactions came with no account name. */
        boolean hasUnnamedTransactions() {
            return blocks.stream().anyMatch(b -> b.account == null && !b.transactions.isEmpty());
        }

        /** The QIF type of the first unnamed block (used for a new account's type). */
        String unnamedType() {
            return blocks.stream().filter(b -> b.account == null && !b.transactions.isEmpty())
                    .map(b -> b.type).findFirst().orElse("Bank");
        }
    }

    // ----- Parsing --------------------------------------------------------

    private final QifFile qif = new QifFile();
    private String section = "";
    private boolean autoSwitch;
    private QifAccount currentAccount;
    private Block currentBlock;

    private QifParser() {
    }

    static QifFile parse(Path file) throws IOException {
        return new QifParser().run(readText(file));
    }

    /** For testing: parses QIF text directly. */
    static QifFile parseText(String text) {
        return new QifParser().run(text);
    }

    private QifFile run(String text) {
        List<String> record = new ArrayList<>();
        int recordStart = 1;
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            if (i == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);                       // byte-order mark
            }
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("!")) {
                finishRecord(record, recordStart);
                header(line.trim().toLowerCase());
                recordStart = i + 2;
            } else if (line.trim().equals("^")) {
                finishRecord(record, recordStart);
                recordStart = i + 2;
            } else {
                record.add(line.stripLeading());
            }
        }
        finishRecord(record, recordStart);                      // a last record without ^
        resolveDates();
        return qif;
    }

    private void header(String h) {
        if (h.startsWith("!option:autoswitch")) {
            autoSwitch = true;
        } else if (h.startsWith("!clear:autoswitch")) {
            autoSwitch = false;
        } else {
            section = h;
            if (isTransactionSection(h)) {
                currentBlock = new Block(currentAccount, h.substring("!type:".length()).trim());
                qif.blocks.add(currentBlock);
            }
        }
    }

    private static boolean isTransactionSection(String h) {
        return h.equals("!type:bank") || h.equals("!type:cash") || h.equals("!type:ccard")
                || h.equals("!type:oth a") || h.equals("!type:oth l");
    }

    private void finishRecord(List<String> record, int line) {
        if (record.isEmpty()) {
            return;
        }
        try {
            if (section.equals("!account")) {
                QifAccount a = account(record);
                if (a.name != null) {
                    qif.accounts.add(a);
                    if (!autoSwitch) {
                        currentAccount = a;            // the transactions that follow are its
                    }
                }
            } else if (section.startsWith("!type:invst")) {
                qif.investmentSkipped++;
            } else if (section.equals("!type:class")) {
                for (String l : record) {
                    if (l.charAt(0) == 'N') {
                        String tag = firstTag(l.substring(1));
                        if (tag != null) {
                            qif.tags.add(tag);
                        }
                    }
                }
            } else if (section.equals("!type:cat")) {
                QifCategory c = category(record);
                if (c.name != null) {
                    qif.categories.add(c);
                }
            } else if (isTransactionSection(section)) {
                currentBlock.transactions.add(transaction(record));
            }
            // Other sections (classes, memorized, securities, prices) are ignored.
        } catch (IllegalArgumentException e) {
            qif.warnings.add("Skipped the record at line " + line + ": " + e.getMessage());
        }
        record.clear();
    }

    private static QifAccount account(List<String> record) {
        QifAccount a = new QifAccount();
        for (String line : record) {
            String v = line.substring(1).trim();
            switch (line.charAt(0)) {
                case 'N' -> a.name = v;
                case 'T' -> a.type = v;
                default -> {
                }
            }
        }
        return a;
    }

    private static QifCategory category(List<String> record) {
        QifCategory c = new QifCategory();
        for (String line : record) {
            switch (line.charAt(0)) {
                case 'N' -> c.name = line.substring(1).trim();
                case 'I' -> c.income = true;
                case 'E' -> c.income = false;
                default -> {
                }
            }
        }
        return c;
    }

    private static QifTransaction transaction(List<String> record) {
        QifTransaction t = new QifTransaction();
        QifSplit split = null;
        for (String line : record) {
            String v = line.substring(1).trim();
            switch (line.charAt(0)) {
                case 'D' -> t.rawDate = v;
                case 'T' -> {
                    t.amountCents = amount(v);
                    t.hasAmount = true;
                }
                case 'U' -> {
                    if (!t.hasAmount) {
                        t.amountCents = amount(v);
                        t.hasAmount = true;
                    }
                }
                case 'C' -> t.status = status(v);
                case 'N' -> t.number = blankToNull(v);
                case 'P' -> t.payee = blankToNull(v);
                case 'M' -> t.memo = blankToNull(v);
                case 'L' -> {
                    String[] ct = categoryAndTag(v);
                    t.category = ct[0];
                    t.tag = ct[1];
                }
                case 'S' -> {
                    split = new QifSplit();
                    String[] ct = categoryAndTag(v);
                    split.category = ct[0];
                    split.tag = ct[1];
                    t.splits.add(split);
                }
                case 'E' -> {
                    if (split == null) {
                        split = new QifSplit();
                        t.splits.add(split);
                    }
                    split.memo = blankToNull(v);
                }
                case '$' -> {
                    if (split == null) {
                        split = new QifSplit();
                        t.splits.add(split);
                    }
                    split.amountCents = amount(v);
                }
                default -> {
                    // A (address), % (split percent), and anything unknown
                }
            }
        }
        if (t.rawDate == null) {
            throw new IllegalArgumentException("the transaction has no date.");
        }
        if (t.tag == null) {                     // split transactions: use the first split's tag
            for (QifSplit s : t.splits) {
                if (s.tag != null) {
                    t.tag = s.tag;
                    break;
                }
            }
        }
        return t;
    }

    // ----- Field values ---------------------------------------------------

    /** "-1,250.5" -> -125050. Rounds to whole cents. */
    static long amount(String text) {
        String s = text.replace(",", "").replace("$", "").trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a valid amount.");
        }
    }

    /**
     * The C line. Upper-case X (or R) = reconciled; lower-case x, c or * =
     * cleared; blank or anything else = no status. Case matters: CX and Cx differ.
     */
    static TxnStatus status(String text) {
        return switch (text.trim()) {
            case "X", "R" -> TxnStatus.RECONCILED;
            case "x", "c", "C", "*" -> TxnStatus.CLEARED;
            default -> TxnStatus.NONE;
        };
    }

    /**
     * Splits a category field into {category, tag}:
     * "Food:Groceries/Vacation" -> {"Food:Groceries", "Vacation"},
     * "[Savings]/Vacation" -> {"[Savings]", "Vacation"}, "/Vacation" -> {null, "Vacation"}.
     * Either part can be null.
     */
    static String[] categoryAndTag(String text) {
        String s = text.trim();
        String category;
        String rest;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            category = close > 1 ? s.substring(0, close + 1) : null;
            rest = close > 1 ? s.substring(close + 1) : "";
        } else {
            int slash = s.indexOf('/');
            category = slash >= 0 ? s.substring(0, slash) : s;
            rest = slash >= 0 ? s.substring(slash) : "";
        }
        int slash = rest.indexOf('/');
        String tag = slash >= 0 ? firstTag(rest.substring(slash + 1)) : null;
        return new String[] {blankToNull(category == null ? null : category.trim()), tag};
    }

    /** "Vacation:Hawaii" or "Vacation/Work" -> "Vacation" (only the first tag is kept). */
    static String firstTag(String tags) {
        String first = tags.split("[:/,]", 2)[0];
        return blankToNull(first.trim());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ----- Dates ----------------------------------------------------------

    /**
     * Converts every transaction's date. If any date starts with a number
     * over 12, the whole file is read as day/month instead of month/day.
     * Transactions with unreadable dates are dropped with a warning.
     */
    private void resolveDates() {
        boolean dayFirst = false;
        for (Block b : qif.blocks) {
            for (QifTransaction t : b.transactions) {
                String[] parts = t.rawDate.replace(" ", "").split("[^0-9]+");
                if (parts.length >= 2 && parts[0].length() <= 2 && !parts[0].isEmpty()
                        && Integer.parseInt(parts[0]) > 12) {
                    dayFirst = true;
                }
            }
        }
        for (Block b : qif.blocks) {
            var it = b.transactions.iterator();
            while (it.hasNext()) {
                QifTransaction t = it.next();
                try {
                    t.date = date(t.rawDate, dayFirst);
                } catch (IllegalArgumentException e) {
                    qif.warnings.add("Skipped a transaction with an unreadable date: \"" + t.rawDate + "\"");
                    it.remove();
                }
            }
        }
    }

    /**
     * Reads QIF dates: 10/11'26 and 10/11' 6 (Quicken, 2000 and later),
     * 10/11/26, 10/11/2026, 10-11-2026, 10.11.2026 and 2026-10-11.
     */
    static LocalDate date(String raw, boolean dayFirst) {
        String s = raw.replace(" ", "");
        try {
            String[] p;
            int year;
            int apostrophe = s.indexOf('\'');
            if (apostrophe >= 0) {
                p = s.substring(0, apostrophe).split("[/.-]");
                year = 2000 + Integer.parseInt(s.substring(apostrophe + 1));
            } else {
                p = s.split("[/.-]");
                if (p.length != 3) {
                    throw new IllegalArgumentException(raw);
                }
                if (p[0].length() == 4) {                       // 2026-10-11
                    return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                }
                year = Integer.parseInt(p[2]);
                if (p[2].length() <= 2) {                      // 2-digit year
                    int nowYY = LocalDate.now().getYear() % 100;
                    year += year <= nowYY + 10 ? 2000 : 1900;
                }
            }
            if (p.length != 2 && apostrophe >= 0) {
                throw new IllegalArgumentException(raw);
            }
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            return dayFirst ? LocalDate.of(year, b, a) : LocalDate.of(year, a, b);
        } catch (NumberFormatException | DateTimeException e) {
            throw new IllegalArgumentException(raw);
        }
    }

    // ----- Reading the file -----------------------------------------------

    /** UTF-8 if the file is valid UTF-8, otherwise Windows-1252 (older Quicken files). */
    private static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }
}
