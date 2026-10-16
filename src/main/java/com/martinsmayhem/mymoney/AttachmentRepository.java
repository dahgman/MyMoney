package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Files attached to transactions, and account statements: added on their
 * own (any account, any time) or while reconciling.
 *
 * Attached files are copied into an attachments folder beside the data file
 * (in the data file's own folder, which is named after it):
 *
 *   <data file folder>/attachments/statements/<Account>/YYYY/<Account>_statement_YYYY-MM-DD.<ext>
 *   <data file folder>/attachments/transactions/<Account>/YYYY/MM/DD/<transaction id>-<payee>.<ext>
 *
 * Each file is named when it's added, keeping the original file's extension.
 * A second of the same name gets " (2)" ("1234-Walmart (2).pdf"); a
 * transaction with no payee is just "1234.pdf". When a transaction's date or
 * payee changes, its files are renamed or moved to match (see
 * fileTransactionAttachments). A statement renamed by hand keeps its name.
 * The database keeps each file's path relative to the data file's folder, so
 * the whole folder can be moved or backed up together.
 */
final class AttachmentRepository {

    /** One attached file. path is relative to the data file's folder ('/' separated). */
    record Attachment(long id, String fileName, String path) {
        @Override
        public String toString() {
            return fileName;
        }
    }

    private final Connection conn;
    private final Path root;

    private UndoLog undoLog;

    /**
     * Keeps attaching, removing, renaming and moving files out of Undo: the
     * files are copied, moved or deleted on disk, which Undo can't put back,
     * so it mustn't change their listing either. (Rows going with a
     * transaction or reconciliation that's deleted are still put back by its
     * Undo: their files were never deleted.)
     */
    void setUndoLog(UndoLog undoLog) {
        this.undoLog = undoLog;
    }

    private long mark() throws SQLException {
        return undoLog == null ? -1 : undoLog.mark();
    }

    private void untrack(long mark) throws SQLException {
        if (undoLog != null && mark >= 0) {
            undoLog.dropSince(mark);
        }
    }

    AttachmentRepository(Database db) {
        this.conn = db.getConnection();
        this.root = db.getFile().getParent();
    }

    /** The folder an account's attachments go in, named after the account. */
    static String folderName(String accountName) {
        String s = accountName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        s = s.replaceAll("[. ]+$", "");                 // Windows doesn't allow trailing dots or spaces
        return s.isEmpty() ? "Account" : s;
    }

    // ----- Adding --------------------------------------------------------

    /**
     * Copies a file in as an attachment of a transaction, named for it
     * ("<id>-<payee>.<ext>") in its date's folder.
     */
    Attachment addToTransaction(Account account, long txnId, LocalDate date, Path source)
            throws SQLException, IOException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            String dir = transactionDir(account.name(), date);
            return add(account.id(), txnId, null, dir, source,
                    transactionFileName(txnId, payeeOf(txnId), extension(source.getFileName().toString())));
        } finally {
            untrack(mark);
        }
    }

    /** Where transaction attachments and statements are filed, under the data file's folder. */
    static final String TRANSACTIONS = "attachments/transactions/";
    static final String STATEMENTS = "attachments/statements/";

    /** The folder a transaction's files go in: its account's, by its date. */
    private static String transactionDir(String accountName, LocalDate date) {
        return TRANSACTIONS + folderName(accountName) + "/" + datePath(date);
    }

    /** "1234-Walmart.pdf" (just "1234.pdf" with no payee). */
    static String transactionFileName(long txnId, String payee, String ext) {
        String p = payee == null ? "" : folderName(payee.trim());
        if (payee == null || payee.isBlank()) {
            p = "";
        }
        if (p.length() > 60) {
            p = p.substring(0, 60).trim();               // keep paths a sensible length
        }
        return txnId + (p.isEmpty() ? "" : "-" + p) + ext;
    }

    /** ".pdf" from "Receipt.PDF" ("" for a name without one). */
    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1 ? fileName.substring(dot).toLowerCase(java.util.Locale.ROOT) : "";
    }

    private String payeeOf(long txnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT p.name FROM txn t LEFT JOIN payee p ON p.id = t.payee_id WHERE t.id = ?")) {
            ps.setLong(1, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Copies a file in as the bank statement for a reconciliation. */
    Attachment addToStatement(Account account, long reconciliationId, LocalDate statementDate, Path source)
            throws SQLException, IOException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            return add(account.id(), null, reconciliationId, statementDir(account.name(), statementDate), source,
                    statementFileName(account.name(), statementDate, extension(source.getFileName().toString())));
        } finally {
            untrack(mark);
        }
    }

    /**
     * Copies a file in as one of an account's statements, dated date (filed
     * with the statements saved while reconciling).
     */
    Attachment addStatement(Account account, LocalDate date, Path source) throws SQLException, IOException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            Attachment a = add(account.id(), null, null, statementDir(account.name(), date), source,
                    statementFileName(account.name(), date, extension(source.getFileName().toString())));
            try (PreparedStatement ps = conn.prepareStatement("UPDATE attachment SET statement_date = ? WHERE id = ?")) {
                ps.setString(1, date.toString());
                ps.setLong(2, a.id());
                ps.executeUpdate();
            }
            return a;
        } finally {
            untrack(mark);
        }
    }

    /** Copies source into dir, as name (null: its own name), and lists it. */
    private Attachment add(long accountId, Long txnId, Long reconciliationId, String dir, Path source, String name)
            throws SQLException, IOException {
        Path folder = root.resolve(dir);
        Files.createDirectories(folder);
        Path target = unusedName(folder, name != null ? name : source.getFileName().toString());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        String path = dir + "/" + target.getFileName();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO attachment"
                + " (account_id, txn_id, reconciliation_id, file_name, path, added_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, accountId);
            TransactionRepository.setId(ps, 2, txnId);
            TransactionRepository.setId(ps, 3, reconciliationId);
            ps.setString(4, target.getFileName().toString());
            ps.setString(5, path);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            Files.deleteIfExists(target);               // don't leave an unlisted copy behind
            throw e;
        }
        long id;
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_insert_rowid()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            id = rs.getLong(1);
        }
        return new Attachment(id, target.getFileName().toString(), path);
    }

    /** name, or "name (2).ext", "name (3).ext", ... if it's taken. */
    private static Path unusedName(Path folder, String name) {
        Path p = folder.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; Files.exists(p); n++) {
            p = folder.resolve(base + " (" + n + ")" + ext);
        }
        return p;
    }

    private static String datePath(LocalDate d) {
        return String.format("%04d/%02d/%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    // ----- Reading -------------------------------------------------------

    List<Attachment> forTransaction(long txnId) throws SQLException {
        return list("SELECT id, file_name, path FROM attachment WHERE txn_id = ? ORDER BY id", txnId);
    }

    /**
     * One of an account's statements: added on its own, or saved while
     * reconciling (fromReconcile; dated by the reconciliation).
     */
    record Statement(Attachment attachment, LocalDate date, boolean fromReconcile) {
    }

    /** An account's statements, newest first. */
    List<Statement> statements(long accountId) throws SQLException {
        List<Statement> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.id, a.file_name, a.path, COALESCE(a.statement_date, r.statement_date), a.reconciliation_id
                FROM attachment a LEFT JOIN reconciliation r ON r.id = a.reconciliation_id
                WHERE a.account_id = ? AND a.txn_id IS NULL
                  AND (a.statement_date IS NOT NULL OR a.reconciliation_id IS NOT NULL)
                ORDER BY 4 DESC, a.id DESC
                """)) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rec = rs.getLong(5);
                    boolean fromReconcile = !rs.wasNull();
                    list.add(new Statement(new Attachment(rs.getLong(1), rs.getString(2), rs.getString(3)),
                            LocalDate.parse(rs.getString(4)), fromReconcile));
                }
            }
        }
        return list;
    }

    /** The folder a statement is filed in: its account's statements folder, by year. */
    private static String statementDir(String accountName, LocalDate date) {
        return STATEMENTS + folderName(accountName) + "/" + String.format("%04d", date.getYear());
    }

    /** "Checking_statement_2026-09-30.pdf" */
    static String statementFileName(String accountName, LocalDate date, String ext) {
        return folderName(accountName) + "_statement_" + date + ext;
    }

    /** True if a file name is the one statements get for this date (or a numbered copy of it). */
    private static boolean isStatementName(String fileName, String accountName, LocalDate date) {
        String base = statementFileName(accountName, date, "");
        return fileName.startsWith(base)
                && fileName.substring(base.length()).matches("( \\(\\d+\\))?(\\.[^.]*)?");
    }

    /**
     * Re-dates a statement added on its own: to the new year's folder, and,
     * if it still has the name it was given, renamed for the new date.
     */
    void redateStatement(Statement s, Account account, LocalDate date) throws SQLException, IOException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            if (s.fromReconcile()) {
                throw new SQLException("This statement was saved while reconciling; its date is the reconciliation's.");
            }
            String dir = statementDir(account.name(), date);
            String name = isStatementName(s.attachment().fileName(), account.name(), s.date())
                    ? statementFileName(account.name(), date, extension(s.attachment().fileName()))
                    : s.attachment().fileName();       // renamed by hand: kept
            if (s.attachment().path().equals(dir + "/" + name)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE attachment SET statement_date = ? WHERE id = ?")) {
                    ps.setString(1, date.toString());
                    ps.setLong(2, s.attachment().id());
                    ps.executeUpdate();
                }
                return;
            }
            Path from = file(s.attachment(), account);
            Path folder = root.resolve(dir);
            Files.createDirectories(folder);
            Path to = unusedName(folder, name);
            Files.move(from, to);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE attachment SET statement_date = ?, path = ?, file_name = ? WHERE id = ?")) {
                ps.setString(1, date.toString());
                ps.setString(2, dir + "/" + to.getFileName());
                ps.setString(3, to.getFileName().toString());
                ps.setLong(4, s.attachment().id());
                ps.executeUpdate();
            } catch (SQLException e) {
                Files.move(to, from);                       // put it back
                throw e;
            }
            removeEmptyFolders(from.getParent());
        } finally {
            untrack(mark);
        }
    }

    List<Attachment> forReconciliation(long reconciliationId) throws SQLException {
        return list("SELECT id, file_name, path FROM attachment WHERE reconciliation_id = ? ORDER BY id",
                reconciliationId);
    }

    private List<Attachment> list(String sql, long id) throws SQLException {
        List<Attachment> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Attachment(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return list;
    }

    /**
     * Where an attachment's file is. If it isn't at its saved path (say the
     * account was renamed and the rename was undone), looks in the account's
     * current folder too.
     */
    Path file(Attachment a, Account account) {
        Path p = root.resolve(a.path());
        if (Files.exists(p) || account == null) {
            return p;
        }
        String[] parts = a.path().split("/", -1);
        int accountPart = a.path().startsWith("attachments/") ? 2 : 0;   // (0: filed the old way, <Account>/...)
        if (parts.length > accountPart + 1) {
            parts[accountPart] = folderName(account.name());
            Path alt = root.resolve(String.join("/", parts));
            if (Files.exists(alt)) {
                return alt;
            }
        }
        return p;
    }

    // ----- Removing and renaming ---------------------------------------

    /** Removes an attachment and deletes its copied file (and its folder, if now empty). */
    void remove(Attachment a, Account account) throws SQLException, IOException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            Path p = file(a, account);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachment WHERE id = ?")) {
                ps.setLong(1, a.id());
                ps.executeUpdate();
            }
            Files.deleteIfExists(p);
            removeEmptyFolders(p.getParent());
        } finally {
            untrack(mark);
        }
    }

    /**
     * Removes dir if it's empty, then each folder above it that's left
     * empty, up to (not including) the data file's folder.
     */
    private void removeEmptyFolders(Path dir) {
        Path d = dir;
        while (d != null && d.startsWith(root) && !d.equals(root)) {
            try (var s = Files.list(d)) {
                if (s.findAny().isPresent()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.delete(d);
            } catch (IOException e) {
                return;
            }
            d = d.getParent();
        }
    }

    /**
     * Keeps every transaction's files named and filed for it: in its date's
     * folder, as "<id>-<payee>.<ext>". Moves and renames any that aren't
     * (filed the old way, in a folder per transaction; or the transaction's
     * date or payee changed since), then tidies away emptied folders. A file
     * that can't be found is left listed as it is. Returns how many moved.
     *
     * The paths it saves are bookkeeping, not something to undo (see
     * setUndoLog): run after each change (and after Undo), so the listing
     * always follows the files.
     */
    int fileTransactionAttachments() throws SQLException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            record Filed(long id, long txnId, String path, String fileName, String account, LocalDate date,
                         String payee) {
            }
            List<Filed> all = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT at.id, at.txn_id, at.path, at.file_name, a.name, t.txn_date, p.name
                    FROM attachment at
                    JOIN txn t ON t.id = at.txn_id
                    JOIN account a ON a.id = t.account_id
                    LEFT JOIN payee p ON p.id = t.payee_id
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        all.add(new Filed(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), LocalDate.parse(rs.getString(6)), rs.getString(7)));
                    }
                }
            }
            int moved = 0;
            for (Filed f : all) {
                String dir = transactionDir(f.account(), f.date());
                String ext = extension(f.fileName());
                String base = transactionFileName(f.txnId(), f.payee(), "");
                String current = f.path().substring(f.path().lastIndexOf('/') + 1);
                boolean rightFolder = f.path().equals(dir + "/" + current);
                boolean rightName = current.equals(base + ext)
                        || current.matches(java.util.regex.Pattern.quote(base) + " \\(\\d+\\)"
                                + java.util.regex.Pattern.quote(ext));
                if (rightFolder && rightName) {
                    continue;
                }
                Path from = root.resolve(f.path());
                if (!Files.isRegularFile(from)) {
                    continue;                               // missing: nothing to move
                }
                try {
                    Path folder = root.resolve(dir);
                    Files.createDirectories(folder);
                    Path to = unusedName(folder, base + ext);
                    Files.move(from, to);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE attachment SET path = ?, file_name = ? WHERE id = ?")) {
                        ps.setString(1, dir + "/" + to.getFileName());
                        ps.setString(2, to.getFileName().toString());
                        ps.setLong(3, f.id());
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        Files.move(to, from);               // put it back
                        throw e;
                    }
                    removeEmptyFolders(from.getParent());
                    moved++;
                } catch (IOException e) {
                    // left where it is; it's still found there
                }
            }
            return moved;
        } finally {
            untrack(mark);
        }
    }

    /**
     * Renames an attached file (its copy, in the same folder). A name typed
     * without an extension keeps the file's own (".pdf"). Returns it renamed.
     * @throws IllegalArgumentException for a name that can't be a file's, or one already used there
     */
    Attachment rename(Attachment a, Account account, String newName) throws SQLException, IOException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            String name = newName == null ? "" : newName.trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..")
                    || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || name.endsWith(".")) {
                throw new IllegalArgumentException("\"" + name + "\" can't be a file name. Leave out \\ / : * ? \" < > |"
                        + " and don't end it with a dot.");
            }
            String oldName = a.fileName();
            int dot = oldName.lastIndexOf('.');
            String ext = dot > 0 ? oldName.substring(dot) : "";
            if (!ext.isEmpty() && name.lastIndexOf('.') <= 0) {
                name = name + ext;                          // "September" -> "September.pdf"
            }
            if (name.equals(oldName)) {
                return a;
            }
            Path from = file(a, account);
            Path to = from.resolveSibling(name);
            if (Files.exists(to) && !name.equalsIgnoreCase(oldName)) {
                throw new IllegalArgumentException("There's already a file named \"" + name + "\" there.");
            }
            Files.move(from, to);
            String dir = a.path().substring(0, a.path().lastIndexOf('/') + 1);
            String path = dir + name;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE attachment SET file_name = ?, path = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setString(2, path);
                ps.setLong(3, a.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                Files.move(to, from);                       // put it back
                throw e;
            }
            return new Attachment(a.id(), name, path);
        } finally {
            untrack(mark);
        }
    }

    /**
     * Statements filed anywhere but their place (filed by an older version:
     * <Account>/Attachments/Statements/...) move into it, named as a
     * statement added now would be: attachments/statements/<Account>/YYYY/
     * <Account>_statement_YYYY-MM-DD.<ext>. Statements already in their
     * account's folder are left as they are (a name typed for one is kept).
     * Run when a file is opened; a file that's missing is left listed where
     * it was. Returns how many moved.
     */
    int fileStatements() throws SQLException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            record Filed(long id, String path, String fileName, String account, LocalDate date) {
            }
            List<Filed> all = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT at.id, at.path, at.file_name, a.name, COALESCE(at.statement_date, r.statement_date)
                    FROM attachment at
                    JOIN account a ON a.id = at.account_id
                    LEFT JOIN reconciliation r ON r.id = at.reconciliation_id
                    WHERE at.txn_id IS NULL AND (at.statement_date IS NOT NULL OR at.reconciliation_id IS NOT NULL)
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        all.add(new Filed(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                LocalDate.parse(rs.getString(5))));
                    }
                }
            }
            int moved = 0;
            for (Filed f : all) {
                if (f.path().startsWith(STATEMENTS + folderName(f.account()) + "/")) {
                    continue;                               // in its place
                }
                Path from = root.resolve(f.path());
                if (!Files.isRegularFile(from)) {
                    continue;
                }
                try {
                    String dir = statementDir(f.account(), f.date());
                    Path folder = root.resolve(dir);
                    Files.createDirectories(folder);
                    Path to = unusedName(folder, statementFileName(f.account(), f.date(), extension(f.fileName())));
                    Files.move(from, to);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE attachment SET path = ?, file_name = ? WHERE id = ?")) {
                        ps.setString(1, dir + "/" + to.getFileName());
                        ps.setString(2, to.getFileName().toString());
                        ps.setLong(3, f.id());
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        Files.move(to, from);               // put it back
                        throw e;
                    }
                    removeEmptyFolders(from.getParent());
                    moved++;
                } catch (IOException e) {
                    // left where it was; it's still found there
                }
            }
            return moved;
        } finally {
            untrack(mark);
        }
    }

    /** The folder an account's statements are filed under (it may not exist yet). */
    Path statementsFolder(Account account) {
        return root.resolve(STATEMENTS + folderName(account.name()));
    }

    /**
     * After an account is renamed: renames its two folders to match
     * (attachments/statements/<Account> and attachments/transactions/<Account>),
     * renames its statements that have the names they were given
     * ("<Account>_statement_..."), and updates the saved paths. Does nothing
     * if the folder name is the same.
     * @throws IOException if a folder with the new name already exists, or a move fails
     */
    void renameAccountFolder(String oldName, String newName) throws SQLException, IOException {
        long mark = mark();                         // not an undo step: files move with it
        try {
            String from = folderName(oldName);
            String to = folderName(newName);
            if (from.equals(to)) {
                return;
            }
            List<String> moved = new ArrayList<>();
            try {
                for (String base : new String[] {STATEMENTS, TRANSACTIONS, ""}) {   // "": filed the old way
                    Path a = root.resolve(base + from);
                    Path b = root.resolve(base + to);
                    if (!Files.isDirectory(a)) {
                        continue;
                    }
                    if (Files.exists(b)) {
                        throw new IOException("A folder named \"" + base + to + "\" already exists.");
                    }
                    Files.createDirectories(b.getParent());
                    Files.move(a, b);
                    moved.add(base);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE attachment SET path = ? || substr(path, ?) WHERE substr(path, 1, ?) = ?")) {
                        ps.setString(1, base + to + "/");
                        ps.setInt(2, (base + from).length() + 2);
                        ps.setInt(3, (base + from).length() + 1);
                        ps.setString(4, base + from + "/");
                        ps.executeUpdate();
                    }
                }
            } catch (IOException | SQLException e) {
                for (String base : moved) {                 // put the folders back
                    Files.move(root.resolve(base + to), root.resolve(base + from));
                }
                throw e;
            }
            // Statements named for the account take its new name.
            List<Attachment> named = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, file_name, path FROM attachment"
                    + " WHERE txn_id IS NULL AND substr(path, 1, ?) = ?")) {
                ps.setInt(1, (STATEMENTS + to).length() + 1);
                ps.setString(2, STATEMENTS + to + "/");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getString(2).startsWith(from + "_statement_")) {
                            named.add(new Attachment(rs.getLong(1), rs.getString(2), rs.getString(3)));
                        }
                    }
                }
            }
            for (Attachment a : named) {
                Path file = root.resolve(a.path());
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                Path target = unusedName(file.getParent(), to + a.fileName().substring(from.length()));
                Files.move(file, target);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE attachment SET path = ?, file_name = ? WHERE id = ?")) {
                    ps.setString(1, a.path().substring(0, a.path().lastIndexOf('/') + 1) + target.getFileName());
                    ps.setString(2, target.getFileName().toString());
                    ps.setLong(3, a.id());
                    ps.executeUpdate();
                }
            }
        } finally {
            untrack(mark);
        }
    }
}
