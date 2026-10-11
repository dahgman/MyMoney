package com.martinsmayhem.mymoney;

/**
 * One row of the account table.
 * parentId is null for a top-level account and set for an envelope.
 */
public record Account(long id, String name, AccountType type, long openingBalanceCents,
                      String currency, boolean closed, Long parentId) {

    /** The account tree and combo boxes display accounts by name. */
    @Override
    public String toString() {
        return name;
    }
}
