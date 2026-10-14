package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.time.LocalDate;

/**
 * One transaction as the ledger shows it: a txn row plus its total amount.
 * Mutable on purpose, because the ledger edits one cell (field) at a time.
 */
public class LedgerEntry {

    long id;                  // 0 = not saved yet (the blank row at the bottom)
    LocalDate date;
    String checkNumber;
    String payee;
    String category;          // full path, e.g. "Food:Groceries"; null = none
    String tag;               // tag name; null = none
    Color tagColor;           // the tag's color (read from the database)
    String description;
    String memo;
    TxnStatus status = TxnStatus.NONE;
    long amountCents;         // negative = payment, positive = deposit
    long balanceCents;        // running balance in date order (calculated, not stored)
    boolean toEnvelope;       // a transfer whose other account is an envelope (read from the database)
    int attachments;          // how many files are attached (read from the database)
    String download;          // from a bank download: "M" matched, "N" new; null = entered by hand
    long interestCents;       // a loan payment's calculated interest, shown (read from the database)
    long interestSplitCents;  // the part of it recorded in the payment, lowering its effect on the balance
    boolean scheduledInterest; // a loan's calculated scheduled-interest charge (read-only but for its status)

    /** A separate copy, for trying out changes before saving them. */
    LedgerEntry copy() {
        LedgerEntry c = new LedgerEntry();
        c.id = id;
        c.date = date;
        c.checkNumber = checkNumber;
        c.payee = payee;
        c.category = category;
        c.tag = tag;
        c.tagColor = tagColor;
        c.description = description;
        c.memo = memo;
        c.status = status;
        c.amountCents = amountCents;
        c.balanceCents = balanceCents;
        c.toEnvelope = toEnvelope;
        c.attachments = attachments;
        c.download = download;
        c.interestCents = interestCents;
        c.interestSplitCents = interestSplitCents;
        c.scheduledInterest = scheduledInterest;
        return c;
    }

    /** True if every field a user can edit is the same in both. */
    boolean sameContent(LedgerEntry o) {
        return java.util.Objects.equals(date, o.date)
                && java.util.Objects.equals(blank(checkNumber), blank(o.checkNumber))
                && java.util.Objects.equals(blank(payee), blank(o.payee))
                && java.util.Objects.equals(blank(category), blank(o.category))
                && java.util.Objects.equals(blank(tag), blank(o.tag))
                && java.util.Objects.equals(blank(description), blank(o.description))
                && java.util.Objects.equals(blank(memo), blank(o.memo))
                && status == o.status && amountCents == o.amountCents;
    }

    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * What it does to the account's balance: the amount, less any interest
     * recorded in a loan payment (so just its principal).
     */
    long netCents() {
        return amountCents - interestSplitCents;
    }

    boolean isSaved() {
        return id != 0;
    }
}
