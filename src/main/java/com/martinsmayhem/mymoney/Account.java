package com.martinsmayhem.mymoney;

/**
 * One row of the account table (what the ledger and balances need; the rest
 * of an account's details are in AccountRepository.Details).
 * parentId is null for a top-level account and set for an envelope.
 * defaultCategory is the category path new transactions get, or null.
 */
public record Account(long id, String name, AccountType type, long openingBalanceCents,
                      String currency, boolean closed, Long parentId,
                      long balanceAdjustmentCents, String defaultCategory) {

    /** For code that only knows the original fields: no adjustment, no default category. */
    public Account(long id, String name, AccountType type, long openingBalanceCents,
                   String currency, boolean closed, Long parentId) {
        this(id, name, type, openingBalanceCents, currency, closed, parentId, 0, null);
    }

    /** Where every balance starts: the opening balance plus the balance adjustment. */
    public long startingBalanceCents() {
        return openingBalanceCents + balanceAdjustmentCents;
    }

    /** The account tree and combo boxes display accounts by name. */
    @Override
    public String toString() {
        return name;
    }
}
