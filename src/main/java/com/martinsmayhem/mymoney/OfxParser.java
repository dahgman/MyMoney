package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the transactions from a QFX / OFX bank download.
 *
 * Handles both OFX 1.x (SGML, where simple elements like <TRNAMT>-12.34 have
 * no closing tag) and OFX 2.x (XML). Only what an import needs is read: each
 * <STMTTRN>'s date, amount, name, memo, check number and FITID (the bank's
 * id for the transaction, used to skip ones already imported), and the
 * account the statement is for: its number (ACCTID), the bank's routing
 * number (BANKID), the bank's name (ORG) and what kind of account it is.
 */
final class OfxParser {

    /** What a QFX / OFX file contains, for importing. */
    static final class OfxFile {
        final List<DownloadImporter.Download> transactions = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        String accountNumber;     // ACCTID of the first statement, or null
        String routingNumber;     // BANKID (bank accounts only), or null
        String bankName;          // the bank's ORG name, or null
        AccountType accountType;  // from ACCTTYPE, or CREDIT_CARD for a card statement; null if unknown
    }

    private OfxParser() {
    }

    static OfxFile parse(Path file) throws IOException {
        return parseText(QifParser.readText(file));
    }

    /** For testing: parses OFX text directly. */
    static OfxFile parseText(String text) {
        OfxFile ofx = new OfxFile();
        int start = text.indexOf('<');
        if (start < 0 || !text.toUpperCase().contains("<OFX>")) {
            ofx.warnings.add("This doesn't look like a QFX / OFX file (there is no <OFX> section).");
            return ofx;
        }
        Fields current = null;
        // Every tag starts with '<'; what follows its '>' up to the next '<' is its value.
        for (String token : text.substring(start + 1).split("<")) {
            int close = token.indexOf('>');
            if (close < 0) {
                continue;
            }
            String tag = token.substring(0, close).trim().toUpperCase();
            String value = decode(token.substring(close + 1).trim());
            switch (tag) {
                case "STMTTRN" -> current = new Fields();
                case "/STMTTRN" -> {
                    if (current != null) {
                        add(ofx, current);
                    }
                    current = null;
                }
                case "ACCTID" -> {
                    if (ofx.accountNumber == null && !value.isEmpty()) {
                        ofx.accountNumber = value;
                    }
                }
                case "BANKID" -> {
                    if (ofx.routingNumber == null && !value.isEmpty()) {
                        ofx.routingNumber = value;
                    }
                }
                case "ORG" -> {
                    if (ofx.bankName == null && !value.isEmpty()) {
                        ofx.bankName = value;
                    }
                }
                case "CCACCTFROM" -> {
                    if (ofx.accountType == null) {
                        ofx.accountType = AccountType.CREDIT_CARD;
                    }
                }
                case "ACCTTYPE" -> {
                    if (ofx.accountType == null) {
                        ofx.accountType = switch (value.toUpperCase()) {
                            case "CHECKING" -> AccountType.CHECKING;
                            case "SAVINGS", "MONEYMRKT", "CD" -> AccountType.SAVINGS;
                            case "CREDITLINE" -> AccountType.LOAN;
                            default -> null;
                        };
                    }
                }
                default -> {
                    if (current != null && !value.isEmpty()) {
                        switch (tag) {
                            case "DTPOSTED" -> current.date = value;
                            case "TRNAMT" -> current.amount = value;
                            case "NAME" -> current.name = value;
                            case "MEMO" -> current.memo = value;
                            case "CHECKNUM" -> current.check = value;
                            case "FITID" -> current.fitid = value;
                            default -> {
                                // TRNTYPE, SIC, REFNUM, ... not needed
                            }
                        }
                    }
                }
            }
        }
        if (current != null) {
            add(ofx, current);                                  // a last <STMTTRN> never closed
        }
        return ofx;
    }

    /** One <STMTTRN>'s raw values. */
    private static final class Fields {
        String date;
        String amount;
        String name;
        String memo;
        String check;
        String fitid;
    }

    private static void add(OfxFile ofx, Fields f) {
        try {
            if (f.date == null || f.date.length() < 8 || f.amount == null) {
                throw new IllegalArgumentException("it has no date or amount");
            }
            LocalDate date = LocalDate.of(Integer.parseInt(f.date.substring(0, 4)),
                    Integer.parseInt(f.date.substring(4, 6)), Integer.parseInt(f.date.substring(6, 8)));
            long cents = QifParser.amount(f.amount.replace("+", ""));
            ofx.transactions.add(new DownloadImporter.Download(date, cents, f.name, f.memo, f.check,
                    f.fitid == null ? null : "OFX:" + f.fitid));
        } catch (RuntimeException e) {
            ofx.warnings.add("Skipped a transaction (" + (f.name == null ? "no name" : f.name) + "): "
                    + (e.getMessage() == null ? "unreadable" : e.getMessage()));
        }
    }

    private static String decode(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }
}
