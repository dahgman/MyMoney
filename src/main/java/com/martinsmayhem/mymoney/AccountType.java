package com.martinsmayhem.mymoney;

/** Kinds of accounts. The enum name is what gets stored in account.type. */
public enum AccountType {
    CHECKING("Checking"),
    SAVINGS("Savings"),
    CREDIT_CARD("Credit Card"),
    CASH("Cash"),
    LOAN("Loan"),
    OTHER_ASSET("Other Asset"),
    ENVELOPE("Envelope");          // a child account that sets money aside inside its parent

    private final String label;

    AccountType(String label) {
        this.label = label;
    }

    /** Shown in combo boxes and labels. */
    @Override
    public String toString() {
        return label;
    }
}
