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
 * Files attached to transactions and to bank statements (reconciliations).
 *
 * Attached files are copied into folders next to the data file, one folder
 * per account:
 *
 *   <data file folder>/<Account>/Attachments/Transactions/YYYY/MM/DD/<transaction id>/
 *   <data file folder>/<Account>/Attachments/Statements/YYYY/MM/DD/
 *
 * (the date is the transaction's date, or the statement date). The database
 * keeps each file's path relative to the data file's folder, so the whole
 * folder can be moved or backed up together.
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

    /** Copies a file in as an attachment of a transaction. */
    Attachment addToTransaction(Account account, long txnId, LocalDate date, Path source)
            throws SQLException, IOException {
        String dir = folderName(account.name()) + "/Attachments/Transactions/" + datePath(date) + "/" + txnId;
        return add(account.id(), txnId, null, dir, source);
    }

    /** Copies a file in as the bank statement for a reconciliation. */
    Attachment addToStatement(Account account, long reconciliationId, LocalDate statementDate, Path source)
            throws SQLException, IOException {
        String dir = folderName(account.name()) + "/Attachments/Statements/" + datePath(statementDate);
        return add(account.id(), null, reconciliationId, dir, source);
    }

    private Attachment add(long accountId, Long txnId, Long reconciliationId, String dir, Path source)
            throws SQLException, IOException {
        Path folder = root.resolve(dir);
        Files.createDirectories(folder);
        Path target = unusedName(folder, source.getFileName().toString());
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
        int slash = a.path().indexOf('/');
        if (slash > 0) {
            Path alt = root.resolve(folderName(account.name()) + a.path().substring(slash));
            if (Files.exists(alt)) {
                return alt;
            }
        }
        return p;
    }

    // ----- Removing and renaming ---------------------------------------

    /** Removes an attachment and deletes its copied file (and its folder, if now empty). */
    void remove(Attachment a, Account account) throws SQLException, IOException {
        Path p = file(a, account);
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM attachment WHERE id = ?")) {
            ps.setLong(1, a.id());
            ps.executeUpdate();
        }
        Files.deleteIfExists(p);
        Path dir = p.getParent();
        try (var s = Files.list(dir)) {
            if (s.findAny().isEmpty()) {
                Files.delete(dir);
            }
        } catch (IOException ignored) {
            // an empty folder left behind is harmless
        }
    }

    /**
     * After an account is renamed: renames its attachments folder to match,
     * and updates the saved paths. Does nothing if the folder name is the same
     * or the account has no folder yet.
     * @throws IOException if a folder with the new name already exists, or the move fails
     */
    void renameAccountFolder(String oldName, String newName) throws SQLException, IOException {
        String from = folderName(oldName);
        String to = folderName(newName);
        if (from.equals(to) || !Files.isDirectory(root.resolve(from))) {
            return;
        }
        if (Files.exists(root.resolve(to))) {
            throw new IOException("A folder named \"" + to + "\" already exists next to the data file.");
        }
        Files.move(root.resolve(from), root.resolve(to));
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE attachment SET path = ? || substr(path, ?) WHERE substr(path, 1, ?) = ?")) {
            ps.setString(1, to + "/");
            ps.setInt(2, from.length() + 2);
            ps.setInt(3, from.length() + 1);
            ps.setString(4, from + "/");
            ps.executeUpdate();
        } catch (SQLException e) {
            Files.move(root.resolve(to), root.resolve(from));   // put the folder back
            throw e;
        }
    }
}
