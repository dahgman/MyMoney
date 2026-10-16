package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An account made for a bank download that no account has the number of. */
class ImportNewAccountTest {

    private static Account named(String name) {
        return new Account(1, name, AccountType.CHECKING, 0, null, false, null);
    }

    @Test
    void namedForTheBankTypeAndLastFourDigits() {
        BankImportDialog.FileAccount wf = new BankImportDialog.FileAccount("2222222222", "111111111", "WF",
                AccountType.CHECKING);
        assertEquals("WF Checking 2222", BankImportDialog.genericName(wf, List.of()));
        BankImportDialog.FileAccount card = new BankImportDialog.FileAccount("4111-1111-1111-2549", null, "Citi",
                AccountType.CREDIT_CARD);
        assertEquals("Citi Credit Card 2549", BankImportDialog.genericName(card, List.of()), "dashes ignored");
        BankImportDialog.FileAccount noBank = new BankImportDialog.FileAccount("987", null, null, null);
        assertEquals("Checking 987", BankImportDialog.genericName(noBank, List.of()), "short number; no bank or type");
    }

    @Test
    void neverTheNameOfAnExistingAccount() {
        BankImportDialog.FileAccount wf = new BankImportDialog.FileAccount("2222222222", null, "WF", AccountType.CHECKING);
        assertEquals("WF Checking 2222 (2)", BankImportDialog.genericName(wf, List.of(named("wf checking 2222"))));
        assertEquals("WF Checking 2222 (3)", BankImportDialog.genericName(wf,
                List.of(named("WF Checking 2222"), named("WF Checking 2222 (2)"))));
    }

    @Test
    void aFileThatDoesntSayWhoseItIs() {
        assertEquals("Imported Account", BankImportDialog.genericName(null, List.of()));
        assertEquals("Imported Account (2)", BankImportDialog.genericName(null, List.of(named("Imported Account"))));
    }
}
