package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Type column (OFX TRNTYPE) and the ledger filter's "column:" prefix. */
class TypeAndFilterTest {

    /** A Wells Fargo download, as banks send it: SGML, no closing tags on values. */
    private static final String QFX = "OFXHEADER:100DATA:OFXSGMLVERSION:102SECURITY:NONEENCODING:USASCII"
            + "CHARSET:1252COMPRESSION:NONEOLDFILEUID:NONENEWFILEUID:NONE<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>"
            + "<CURDEF>USD<BANKACCTFROM><BANKID>111111111<ACCTID>2222222222<ACCTTYPE>CHECKING</BANKACCTFROM>"
            + "<BANKTRANLIST>"
            + "<STMTTRN><TRNTYPE>DIRECTDEP<DTPOSTED>20261009110000.000[-7:PDT]<TRNAMT>22.85<FITID>202610091"
            + "<NAME>CITICARDS CASH REWARD<MEMO>261008 812139843790770</STMTTRN>"
            + "<STMTTRN><TRNTYPE>POS<DTPOSTED>20261009110000.000[-7:PDT]<TRNAMT>-312.00<FITID>202610092"
            + "<NAME>COSERV ELECTRIC<MEMO>PURCHASE 10/08 COSERV.COM TX CARD 2549</STMTTRN>"
            + "<STMTTRN><TRNTYPE>DIRECTDEBIT<DTPOSTED>20261009110000.000[-7:PDT]<TRNAMT>-69.00<FITID>202610093"
            + "<NAME>Credit One Bank Payment<MEMO>261008 54985857</STMTTRN>"
            + "</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";

    @TempDir
    Path dir;
    private Database db;
    private TransactionRepository transactions;
    private Account checking;

    // Windows won't let @TempDir delete a database file that is still open.
    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        AccountRepository accounts = new AccountRepository(db);
        long id = accounts.insert("Checking", AccountType.CHECKING, 0, null);
        checking = accounts.findAll().stream().filter(a -> a.id() == id).findFirst().orElseThrow();
        transactions = new TransactionRepository(db);
    }

    @Test
    void ofxTypesAreReadAndKept() throws Exception {
        OfxParser.OfxFile ofx = OfxParser.parseText(QFX);
        assertEquals(List.of("DIRECTDEP", "POS", "DIRECTDEBIT"),
                ofx.transactions.stream().map(DownloadImporter.Download::type).toList());

        DownloadImporter importer = new DownloadImporter(db);
        for (DownloadImporter.Download d : ofx.transactions) {
            importer.addNew(checking, d, d.payee());
        }
        List<LedgerEntry> ledger = transactions.findByAccount(checking.id());
        assertEquals(List.of("DIRECTDEP", "POS", "DIRECTDEBIT"), ledger.stream().map(e -> e.type).toList());
    }

    @Test
    void matchingADownloadGivesTheTransactionItsType() throws Exception {
        LedgerEntry typed = entry("COSERV ELECTRIC", -31_200, null);
        transactions.insert(checking.id(), typed);
        DownloadImporter importer = new DownloadImporter(db);
        DownloadImporter.Download pos = OfxParser.parseText(QFX).transactions.get(1);
        DownloadImporter.Candidate c = importer.candidates(checking.id(), pos, 3).get(0);
        importer.accept(pos, c);
        assertEquals("POS", transactions.findByAccount(checking.id()).get(0).type);
    }

    @Test
    void typesAreStoredInCapitalsAndOffered() throws Exception {
        transactions.insert(checking.id(), entry("Transfer in", 5_000, " zelle "));
        assertEquals("ZELLE", transactions.findByAccount(checking.id()).get(0).type);
        List<String> offered = transactions.findTypes();
        assertTrue(offered.containsAll(List.of("POS", "DIRECTDEBIT", "CHECK", "XFER", "ZELLE")));
    }

    @Test
    void filterLimitedToOneColumn() throws Exception {
        transactions.insert(checking.id(), entry("Walmart", -2_500, "POS", "groceries"));
        transactions.insert(checking.id(), entry("POS Systems Inc", -9_900, "DIRECTDEBIT", null));
        transactions.insert(checking.id(), entry("Employer", 150_000, "DIRECTDEP", "pos pay period"));
        LedgerTableModel model = new LedgerTableModel(transactions, m -> { }, q -> true, () -> { });
        model.setAccount(checking);

        assertEquals(3, shown(model, "pos"), "no colon: every column");
        assertEquals(1, shown(model, "type:pos"), "just the Type column");
        assertEquals(1, shown(model, "Type: POS"), "column name and text ignore case; spaces after the colon");
        assertEquals(1, shown(model, "payee:pos"));
        assertEquals(1, shown(model, "memo: pos"));
        assertEquals(2, shown(model, "type:direct"));
        assertEquals(1, shown(model, "payment:99.00"), "an amount column, as typed");
        assertEquals(1, shown(model, "deposit:$1,500"));
        assertEquals(3, shown(model, "type:"), "nothing after the colon: no limit");
        assertEquals(0, shown(model, "nosuchcolumn:pos"), "not a column: searched as it is");
    }

    @Test
    void loanHeadersNameTheirColumns() throws Exception {
        AccountRepository accounts = new AccountRepository(db);
        long id = accounts.insert("Car", AccountType.LOAN, -1_000_000, null);
        Account loan = accounts.findAll().stream().filter(a -> a.id() == id).findFirst().orElseThrow();
        transactions.insert(id, entry("Lender", 30_000, null, null));
        transactions.insert(id, entry("Late fee", -2_500, null, null));
        LedgerTableModel model = new LedgerTableModel(transactions, m -> { }, q -> true, () -> { });
        model.setAccount(loan);
        assertEquals(1, shown(model, "payment:300"), "in a loan, Payment is money paid in");
        assertEquals(1, shown(model, "charge:25"));
    }

    /** How many transactions the filter shows (not counting the blank new-transaction row). */
    private static int shown(LedgerTableModel model, String filter) {
        model.setFilter(filter, false);
        return model.getRowCount() - 1;
    }

    private static LedgerEntry entry(String payee, long cents, String type) {
        return entry(payee, cents, type, null);
    }

    private static LedgerEntry entry(String payee, long cents, String type, String memo) {
        LedgerEntry e = new LedgerEntry();
        e.date = java.time.LocalDate.of(2026, 10, 9);
        e.payee = payee;
        e.amountCents = cents;
        e.type = type;
        e.memo = memo;
        return e;
    }
}
