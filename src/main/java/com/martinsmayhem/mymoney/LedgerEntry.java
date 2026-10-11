package com.martinsmayhem.mymoney;

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
    String description;
    String memo;
    TxnStatus status = TxnStatus.NONE;
    long amountCents;         // negative = payment, positive = deposit

    boolean isSaved() {
        return id != 0;
    }
}
