package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An open, SQLCipher-encrypted MyMoney data file.
 * Use Database.create(...) for a new file and Database.open(...) for an existing one.
 */
public class Database implements AutoCloseable {

    /** File extension for MyMoney data files (without the dot). */
    public static final String EXTENSION = "MyMoney";

    /**
     * Current schema version.
     * Version history:
     *   1 - initial schema
     *   2 - account.parent_id (envelopes / child accounts), txn.description
     *   3 - tag table (name + color), txn.tag_id
     *   4 - split.transfer_txn_id (links the two sides of a transfer)
     *   5 - txn.pre_reconcile_status, reconciliation and attachment tables
     *   6 - txn.download_status and txn.download_id (bank downloads)
     *   7 - account details: bank name, start date, balance adjustment, account and
     *       routing numbers, default category, comments, website
     *   8 - payee_rename (payee names chosen for bank names, remembered from imports)
     *   9 - schedule (scheduled transactions); txn.schedule_id and txn.schedule_date
     *  10 - loan terms on account: interest rate, original amount, term, interest category
     *  11 - credit card terms on account: credit limit, statement and due days, minimum payment
     *  12 - loans: the lender's monthly payment and first payment date
     * When changing the schema: bump this, update createSchema(), and add a step to upgrade().
     */
    public static final int SCHEMA_VERSION = 23;

    private final Path file;
    private final Connection conn;
    private final String password;                  // to open it again after a rename

    private Database(Path file, String password) throws SQLException {
        this.file = file.toAbsolutePath();
        this.password = password;
        this.conn = DriverManager.getConnection(buildUrl(this.file, password));
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            close();
            throw e;
        }
    }

    /** Creates a brand-new encrypted file and builds the current schema in it. */
    public static Database create(Path file, String password) throws SQLException {
        if (Files.exists(file)) {
            throw new SQLException("File already exists: " + file);
        }
        Database db = new Database(file, password);
        try {
            db.createSchema();
            return db;
        } catch (SQLException e) {
            db.close();
            try {
                Files.deleteIfExists(file);   // don't leave a half-built file behind
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    /**
     * Opens an existing encrypted file, upgrading its schema if it is older.
     * Throws SQLException if the password is wrong.
     */
    public static Database open(Path file, String password) throws SQLException {
        if (!Files.isRegularFile(file)) {
            throw new SQLException("File not found: " + file);
        }
        Database db = new Database(file, password);
        try {
            int version = db.readSchemaVersion();   // a wrong password fails here
            if (version > SCHEMA_VERSION) {
                throw new SQLException("This file was created by a newer version of MyMoney.");
            }
            if (version < SCHEMA_VERSION) {
                Splash.step("Upgrading the file for this version of MyMoney…", 30);
                db.upgrade(version);
            }
            return db;
        } catch (SQLException e) {
            db.close();
            throw e;
        }
    }

    public Path getFile() {
        return file;
    }

    /**
     * Copies the data file to target (replacing it if it exists). The copy is
     * encrypted with the same password. Nothing can be written to the file
     * while it is being copied, so the copy is never half-saved.
     */
    public void backupTo(Path target) throws SQLException, IOException {
        try (Statement st = conn.createStatement()) {
            st.execute("BEGIN IMMEDIATE");          // holds off writes during the copy
            try {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(target);       // don't leave a partial backup behind
                throw e;
            } finally {
                st.execute("ROLLBACK");             // nothing was changed; just releases the lock
            }
        }
    }

    /**
     * What renaming a file did: the file, open again (at its new name, or,
     * if the rename failed, its old one), and what went wrong, if anything.
     */
    public record Renamed(Database database, Exception problem) {
    }

    /** The file's name without ".MyMoney": "Household" for Household.MyMoney. */
    public String baseName() {
        String name = file.getFileName().toString();
        String ext = "." + EXTENSION;
        return name.toLowerCase().endsWith(ext.toLowerCase()) ? name.substring(0, name.length() - ext.length()) : name;
    }

    /**
     * True if the file's folder is named after it (Household/Household.MyMoney,
     * as new files are made), so renaming the file renames its folder too.
     */
    public boolean hasOwnFolder() {
        Path folder = file.getParent();
        return folder != null && folder.getFileName() != null
                && folder.getFileName().toString().equals(baseName());
    }

    /**
     * Renames the file to newName.MyMoney, and, if its folder is named after
     * it, the folder too (everything in it, attachments and all, goes along:
     * their saved paths are relative to it). The file is closed to do it,
     * then opened again; this Database can't be used afterwards. If any step
     * fails, the names are put back and the file is opened where it was.
     * @throws IllegalArgumentException for a name that can't be a file's, or one already taken (nothing changed)
     */
    public Renamed renameTo(String newName) {
        String name = newName == null ? "" : newName.trim();
        String ext = "." + EXTENSION;
        if (name.toLowerCase().endsWith(ext.toLowerCase())) {
            name = name.substring(0, name.length() - ext.length()).trim();
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || name.endsWith(".")) {
            throw new IllegalArgumentException("\"" + name + "\" can't be a file name. Leave out \\ / : * ? \" < > |"
                    + " and don't end it with a dot.");
        }
        Path folder = file.getParent();
        boolean ownFolder = hasOwnFolder();
        Path newFolder = ownFolder ? folder.resolveSibling(name) : folder;
        Path renamedHere = folder.resolve(name + ext);              // the file renamed, before the folder is
        Path newFile = newFolder.resolve(name + ext);
        if (newFile.equals(file)) {
            throw new IllegalArgumentException("That's its name already.");
        }
        boolean caseOnly = newFile.toString().equalsIgnoreCase(file.toString());
        if (!caseOnly && Files.exists(renamedHere)) {
            throw new IllegalArgumentException("There's already a file named \"" + name + ext + "\" there.");
        }
        if (ownFolder && !caseOnly && Files.exists(newFolder)) {
            throw new IllegalArgumentException("There's already a folder named \"" + name + "\" in "
                    + folder.getParent() + ".");
        }

        close();
        List<Path[]> done = new ArrayList<>();                      // moves made, to undo on failure
        try {
            move(file, renamedHere, done);
            for (String side : new String[] {"-journal", "-wal", "-shm"}) {   // SQLite's companions, if any
                Path p = Path.of(file + side);
                if (Files.exists(p)) {
                    move(p, Path.of(renamedHere + side), done);
                }
            }
            if (ownFolder) {
                move(folder, newFolder, done);
            }
            return new Renamed(open(newFile, password), null);
        } catch (IOException | SQLException e) {
            for (int i = done.size() - 1; i >= 0; i--) {             // put everything back
                try {
                    Files.move(done.get(i)[1], done.get(i)[0]);
                } catch (IOException ignored) {
                    // (reported below if it can't be opened)
                }
            }
            try {
                return new Renamed(open(file, password), e);
            } catch (SQLException again) {
                throw new IllegalStateException("The file couldn't be renamed (" + e.getMessage()
                        + "), and couldn't be opened again at " + file + ": " + again.getMessage(), again);
            }
        }
    }

    private static void move(Path from, Path to, List<Path[]> done) throws IOException {
        if (from.toString().equalsIgnoreCase(to.toString()) && !from.equals(to)) {
            // Only the case changes: on Windows and macOS that's the same name, so go by way of another.
            Path step = from.resolveSibling(from.getFileName() + ".renaming");
            Files.move(from, step);
            Files.move(step, to);
        } else {
            Files.move(from, to);
        }
        done.add(new Path[] {from, to});
    }

    /** For data-access classes in this package (AccountRepository, ...). */
    Connection getConnection() {
        return conn;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }

    // ---------------------------------------------------------------------

    private static String buildUrl(Path file, String password) {
        // Path.toUri() produces a correctly escaped file:/// URI on Windows and Linux.
        String key = URLEncoder.encode(password, StandardCharsets.UTF_8).replace("+", "%20");
        String vfs = vfsFor(file);
        return "jdbc:sqlite:" + file.toUri() + "?" + (vfs == null ? "" : "vfs=" + vfs + "&")
                + "cipher=sqlcipher&legacy=4&key=" + key;
    }

    /**
     * How SQLite locks a file on a network share (SMB/CIFS, NFS, ...), on
     * Linux and macOS: with a "<file>.lock" folder made beside it while it
     * writes, rather than byte-range locks, which those shares often don't
     * honour the way SQLite needs ("database is locked", even on a new file).
     * "multipleciphers-" keeps the encryption on top. On a local disk, and
     * on Windows (whose SMB locks work), null: SQLite's usual locking.
     */
    static String vfsFor(Path file) {
        String type;
        try {
            Path p = file.toAbsolutePath();
            while (p != null && !Files.exists(p)) {
                p = p.getParent();                      // a new file: the folder it's going in
            }
            type = p == null ? "" : Files.getFileStore(p).type();
            if (p != null && Files.isReadable(Path.of("/proc/self/mounts"))) {
                // Linux: an automounted share shows as "autofs"; the mount table has what's under it.
                type = mountType(Files.readAllLines(Path.of("/proc/self/mounts")), p.toRealPath().toString(), type);
            }
        } catch (IOException | RuntimeException e) {
            type = "";
        }
        return vfsForType(type, System.getProperty("os.name"));
    }

    /**
     * From Linux's mount table (/proc/self/mounts lines: "what where type
     * options ..."), the type of the file system path is on: the mount with
     * the longest mount point that holds it, past any "autofs" there. def if
     * none is found.
     */
    static String mountType(java.util.List<String> mounts, String path, String def) {
        String best = null;
        String bestType = def;
        for (String line : mounts) {
            String[] f = line.split(" ");
            if (f.length < 3) {
                continue;
            }
            String where = f[1].replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\");
            boolean holds = path.equals(where) || path.startsWith(where.endsWith("/") ? where : where + "/");
            if (!holds) {
                continue;
            }
            boolean longer = best == null || where.length() > best.length();
            boolean sameButReal = best != null && where.length() == best.length() && "autofs".equals(bestType);
            if (longer || sameButReal) {
                best = where;
                bestType = f[2];
            }
        }
        return bestType;
    }

    /** vfsFor, given the file system's type ("cifs", "ext4", ...) and the operating system's name. */
    static String vfsForType(String fileSystemType, String osName) {
        if (osName == null || osName.toLowerCase().contains("win")) {
            return null;
        }
        String t = fileSystemType == null ? "" : fileSystemType.toLowerCase();
        for (String network : new String[] {"cifs", "smb", "nfs", "afp", "dav", "sshfs", "9p", "ceph", "gluster",
                "rclone"}) {
            if (t.contains(network)) {
                return "multipleciphers-unix-dotfile";
            }
        }
        return null;
    }

    /** True if opening failed because the file is locked (in use, or a lock left behind). */
    public static boolean isLocked(SQLException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("SQLITE_BUSY") || m.contains("database is locked");
    }

    /**
     * A lock left behind by a MyMoney that stopped mid-save on a network
     * share ("<file>.lock", see vfsFor), or null if there's none.
     */
    public static Path leftoverLock(Path file) {
        Path lock = Path.of(file.toAbsolutePath() + ".lock");
        return vfsFor(file) != null && Files.exists(lock) ? lock : null;
    }

    /** Clears a lock left behind (see leftoverLock). */
    public static void clearLock(Path lock) throws IOException {
        if (Files.isDirectory(lock)) {
            try (var s = Files.list(lock)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.deleteIfExists(lock);
    }

    private int readSchemaVersion() throws SQLException {
        String sql = "SELECT value FROM meta WHERE key = 'schema_version'";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("Not a MyMoney file.");
            }
            return Integer.parseInt(rs.getString(1));
        }
    }

    /**
     * Loan payments entered in the loan's ledger as money out (in the column
     * then called Payment, now Charge) raised what's owed instead of lowering
     * it. Any such entry that's plainly a payment (the loan's monthly payment,
     * to the cent or a cent off for the last one, with no category and no
     * transfer) becomes money in.
     */
    private void fixLoanPaymentSigns(Statement st) throws SQLException {
        List<long[]> loans = new ArrayList<>();           // account, regular payment
        try (ResultSet rs = st.executeQuery("SELECT id, interest_rate_milli, original_amount_cents, term_months,"
                + " loan_payment_cents FROM account WHERE type = 'LOAN'")) {
            while (rs.next()) {
                Loan l = new Loan(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getInt(4), null, null,
                        rs.getLong(5), null);
                loans.add(new long[] {rs.getLong(1), l.regularPayment()});
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE split SET amount_cents = -amount_cents
                WHERE amount_cents BETWEEN ? AND ? AND category_id IS NULL AND transfer_account_id IS NULL
                  AND loan_interest = 0
                  AND txn_id IN (SELECT id FROM txn WHERE account_id = ?)
                  AND (SELECT COUNT(*) FROM split o WHERE o.txn_id = split.txn_id) = 1
                """)) {
            for (long[] l : loans) {
                if (l[1] <= 0) {
                    continue;
                }
                ps.setLong(1, -l[1] - 1);
                ps.setLong(2, -l[1] + 1);
                ps.setLong(3, l[0]);
                ps.executeUpdate();
            }
        }
    }

    /** The tables that get a uid: an id the phone and the desktop agree on (see docs/SYNC-DESIGN.md). */
    static final String[] UID_TABLES = {"account", "category", "payee", "tag", "txn", "schedule"};

    /** SQL for a new random UUID (version 4), like "1b4e28ba-2fa1-41d2-883f-0016d3cca427". */
    static final String NEW_UUID = "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4'"
            + " || substr(hex(randomblob(2)), 2) || '-' || substr('89AB', 1 + abs(random()) % 4, 1)"
            + " || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6)))";

    /**
     * Schema 21, for syncing with the phone (docs/SYNC-DESIGN.md): a uid on
     * each synced table (every row gets one now, and every new row as it's
     * added); which accounts sync; paired phones; changes already applied;
     * and the file's own id. Safe to run again: what's there already is kept.
     */
    private static void addSyncSchema(Statement st) throws SQLException {
        for (String table : UID_TABLES) {
            if (!hasColumn(st, table, "uid")) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN uid TEXT");
            }
            st.execute("UPDATE " + table + " SET uid = " + NEW_UUID + " WHERE uid IS NULL");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_" + table + "_uid ON " + table + "(uid)");
            st.execute("CREATE TRIGGER IF NOT EXISTS " + table + "_uid AFTER INSERT ON " + table
                    + " WHEN NEW.uid IS NULL BEGIN UPDATE " + table + " SET uid = " + NEW_UUID
                    + " WHERE id = NEW.id; END");
        }
        if (!hasColumn(st, "account", "sync")) {
            st.execute("ALTER TABLE account ADD COLUMN sync INTEGER NOT NULL DEFAULT 0");
        }
        st.execute("""
                CREATE TABLE IF NOT EXISTS sync_device (
                    id                INTEGER PRIMARY KEY,
                    uid               TEXT    NOT NULL UNIQUE,     -- the phone's device id (its mailbox folder)
                    name              TEXT    NOT NULL,
                    key               TEXT    NOT NULL,            -- its 256-bit AES key, base64
                    include_scheduled INTEGER NOT NULL DEFAULT 1,  -- send it Scheduled transactions
                    applied_through   INTEGER NOT NULL DEFAULT 0,  -- the last change file applied
                    last_seen         TEXT,                        -- ISO instant of its last change file
                    paired_at         TEXT    NOT NULL
                )
                """);
        st.execute("""
                CREATE TABLE IF NOT EXISTS sync_op (
                    id         INTEGER PRIMARY KEY,
                    device_id  INTEGER NOT NULL REFERENCES sync_device(id) ON DELETE CASCADE,
                    op_id      TEXT    NOT NULL UNIQUE,            -- the phone's id for the change
                    applied_at TEXT    NOT NULL,
                    result     TEXT    NOT NULL,                   -- applied, applied-with-conflicts, rejected
                    conflicts  TEXT,                               -- comma-separated field names
                    message    TEXT
                )
                """);
        st.execute("INSERT OR IGNORE INTO meta (key, value) VALUES ('sync_file_id', " + NEW_UUID + ")");
    }

    /**
     * Schema 22: a schedule's amount can be per weekday (schedule.amount_weekday,
     * 1 = Monday ... 7 = Sunday; NULL = a fixed amount): its amount times the
     * number of those weekdays in each occurrence's month.
     */
    private static void addScheduleWeekday(Statement st) throws SQLException {
        if (!hasColumn(st, "schedule", "amount_weekday")) {
            st.execute("ALTER TABLE schedule ADD COLUMN amount_weekday INTEGER");
        }
    }

    /**
     * Schema 23: a scheduled deposit can be a paycheck (schedule.paycheck:
     * 1 = the main one, 2 = secondary, 3 = tertiary, ...; NULL = not one),
     * for showing what's due between paychecks (see Paychecks).
     */
    private static void addPaycheckRank(Statement st) throws SQLException {
        if (!hasColumn(st, "schedule", "paycheck")) {
            st.execute("ALTER TABLE schedule ADD COLUMN paycheck INTEGER");
        }
    }

    private static boolean hasColumn(Statement st, String table, String column) throws SQLException {
        try (java.sql.ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How many rows this connection has changed since the file was opened
     * (SQLite's total_changes()): if it's the same as before, nothing changed.
     */
    public long totalChanges() throws SQLException {
        try (Statement st = conn.createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT total_changes()")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Brings an older file up to SCHEMA_VERSION, one step at a time, all-or-nothing. */
    private void upgrade(int fromVersion) throws SQLException {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            if (fromVersion < 2) {
                st.execute("ALTER TABLE account ADD COLUMN parent_id INTEGER REFERENCES account(id)");
                st.execute("ALTER TABLE txn ADD COLUMN description TEXT");
                st.execute("CREATE INDEX idx_account_parent ON account(parent_id)");
            }
            if (fromVersion < 3) {
                st.execute("""
                    CREATE TABLE tag (
                        id    INTEGER PRIMARY KEY,
                        name  TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                        color TEXT    NOT NULL                 -- #RRGGBB
                    )
                    """);
                st.execute("ALTER TABLE txn ADD COLUMN tag_id INTEGER REFERENCES tag(id)");
                st.execute("CREATE INDEX idx_txn_tag ON txn(tag_id)");
            }
            if (fromVersion < 4) {
                st.execute("ALTER TABLE split ADD COLUMN transfer_txn_id INTEGER"
                        + " REFERENCES txn(id) ON DELETE SET NULL");
            }
            if (fromVersion < 5) {
                st.execute("ALTER TABLE txn ADD COLUMN pre_reconcile_status TEXT");
                st.execute("""
                    CREATE TABLE reconciliation (
                        id                      INTEGER PRIMARY KEY,
                        account_id              INTEGER NOT NULL REFERENCES account(id),
                        statement_date          TEXT    NOT NULL,         -- ISO: YYYY-MM-DD
                        ending_balance_cents    INTEGER NOT NULL,
                        beginning_balance_cents INTEGER,                  -- set when finished
                        completed_at            TEXT                      -- NULL = not finished yet
                    )
                    """);
                st.execute("""
                    CREATE TABLE attachment (
                        id                INTEGER PRIMARY KEY,
                        account_id        INTEGER NOT NULL REFERENCES account(id),
                        txn_id            INTEGER REFERENCES txn(id) ON DELETE CASCADE,
                        reconciliation_id INTEGER REFERENCES reconciliation(id) ON DELETE CASCADE,
                        file_name         TEXT    NOT NULL,
                        path              TEXT    NOT NULL,   -- relative to the data file's folder, '/' separated
                        added_at          TEXT    NOT NULL
                    )
                    """);
                st.execute("CREATE INDEX idx_reconciliation_account ON reconciliation(account_id)");
                st.execute("CREATE INDEX idx_attachment_txn ON attachment(txn_id)");
                st.execute("CREATE INDEX idx_attachment_reconciliation ON attachment(reconciliation_id)");
            }
            if (fromVersion < 6) {
                st.execute("ALTER TABLE txn ADD COLUMN download_status TEXT");
                st.execute("ALTER TABLE txn ADD COLUMN download_id TEXT");
                st.execute("CREATE INDEX idx_txn_download ON txn(account_id, download_id)");
            }
            if (fromVersion < 7) {
                for (String col : new String[] {"bank_name TEXT", "start_date TEXT",
                        "balance_adjustment_cents INTEGER NOT NULL DEFAULT 0", "account_number TEXT",
                        "routing_number TEXT", "default_category TEXT", "comments TEXT", "website TEXT"}) {
                    st.execute("ALTER TABLE account ADD COLUMN " + col);
                }
            }
            if (fromVersion < 8) {
                st.execute("""
                    CREATE TABLE payee_rename (
                        id       INTEGER PRIMARY KEY,
                        bank_key TEXT    NOT NULL UNIQUE,     -- the bank's payee name, simplified (see PayeeRenameRepository.bankKey)
                        payee    TEXT    NOT NULL             -- the payee name the user chose for it
                    )
                    """);
            }
            if (fromVersion < 9) {
                st.execute("""
                    CREATE TABLE schedule (
                        id                INTEGER PRIMARY KEY,
                        account_id        INTEGER NOT NULL REFERENCES account(id),
                        payee             TEXT,
                        category          TEXT,                    -- category path, or "[Account]" for a transfer
                        tag               TEXT,
                        description       TEXT,
                        memo              TEXT,
                        amount_cents      INTEGER NOT NULL,        -- negative = payment, positive = deposit
                        unit              TEXT    NOT NULL,        -- DAY, WEEK, MONTH, YEAR or SEMIMONTH
                        interval          INTEGER NOT NULL DEFAULT 1,
                        second_day        INTEGER,                 -- SEMIMONTH: the second day of the month (31 = last)
                        start_date        TEXT    NOT NULL,        -- the first occurrence (ISO)
                        end_date          TEXT,                    -- no occurrences after this; NULL = no end date
                        max_count         INTEGER,                 -- no more than this many; NULL = no limit
                        generated_through TEXT    NOT NULL         -- occurrences up to this date have been created
                    )
                    """);
                st.execute("ALTER TABLE txn ADD COLUMN schedule_id INTEGER REFERENCES schedule(id) ON DELETE SET NULL");
                st.execute("ALTER TABLE txn ADD COLUMN schedule_date TEXT");
                st.execute("CREATE INDEX idx_txn_schedule ON txn(schedule_id, schedule_date)");
            }
            if (fromVersion < 10) {
                for (String col : new String[] {"interest_rate_milli INTEGER NOT NULL DEFAULT 0",
                        "original_amount_cents INTEGER NOT NULL DEFAULT 0", "term_months INTEGER NOT NULL DEFAULT 0",
                        "interest_category TEXT"}) {
                    st.execute("ALTER TABLE account ADD COLUMN " + col);
                }
            }
            if (fromVersion < 11) {
                for (String col : new String[] {"credit_limit_cents INTEGER NOT NULL DEFAULT 0",
                        "statement_day INTEGER NOT NULL DEFAULT 0", "due_day INTEGER NOT NULL DEFAULT 0",
                        "min_payment_percent_milli INTEGER NOT NULL DEFAULT 0",
                        "min_payment_floor_cents INTEGER NOT NULL DEFAULT 0"}) {
                    st.execute("ALTER TABLE account ADD COLUMN " + col);
                }
            }
            if (fromVersion < 12) {
                st.execute("ALTER TABLE account ADD COLUMN loan_payment_cents INTEGER NOT NULL DEFAULT 0");
                st.execute("ALTER TABLE account ADD COLUMN first_payment_date TEXT");
            }
            if (fromVersion < 13) {
                st.execute("ALTER TABLE account ADD COLUMN interest_method TEXT NOT NULL DEFAULT 'M'");
            }
            if (fromVersion < 14) {
                // A loan payment's interest is now a split of the payment itself, not a transaction
                // of its own (as a test build of version 13 kept it): those go, and are recalculated.
                boolean hasInterestFor = false;
                try (ResultSet rs = st.executeQuery("SELECT 1 FROM pragma_table_info('txn')"
                        + " WHERE name = 'interest_for'")) {
                    hasInterestFor = rs.next();
                }
                if (hasInterestFor) {
                    st.execute("DELETE FROM txn WHERE interest_for IS NOT NULL");
                    st.execute("ALTER TABLE txn DROP COLUMN interest_for");
                }
                st.execute("ALTER TABLE split ADD COLUMN loan_interest INTEGER NOT NULL DEFAULT 0");
            }
            if (fromVersion < 15) {
                st.execute("ALTER TABLE txn ADD COLUMN loan_interest_cents INTEGER NOT NULL DEFAULT 0");
            }
            if (fromVersion < 16) {
                fixLoanPaymentSigns(st);
            }
            if (fromVersion < 17) {
                st.execute("ALTER TABLE txn ADD COLUMN txn_type TEXT");
            }
            if (fromVersion < 18) {
                st.execute("ALTER TABLE txn ADD COLUMN interest_override INTEGER");
            }
            if (fromVersion < 19) {
                st.execute("ALTER TABLE attachment ADD COLUMN statement_date TEXT");
            }
            if (fromVersion < 20) {
                st.execute("ALTER TABLE txn ADD COLUMN bank_date TEXT");
                // What's known of it already: a download added as new is on the bank's date, and a
                // QIF download's id starts with its date ("QIF:2026-10-01|..."). Matched QFX ones
                // get theirs when their file is imported again.
                st.execute("UPDATE txn SET bank_date = txn_date WHERE download_status = 'N'");
                st.execute("UPDATE txn SET bank_date = substr(download_id, 5, 10)"
                        + " WHERE download_status = 'M' AND download_id GLOB 'QIF:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]*'");
            }
            if (fromVersion < 21) {
                addSyncSchema(st);
            }
            if (fromVersion < 22) {
                addScheduleWeekday(st);
            }
            if (fromVersion < 23) {
                addPaycheckRank(st);
            }
            // Future: if (fromVersion < 24) { ... }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE meta SET value = ? WHERE key = 'schema_version'")) {
                ps.setString(1, String.valueOf(SCHEMA_VERSION));
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void createSchema() throws SQLException {
        String[] ddl = {
            """
            CREATE TABLE meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE account (
                id                    INTEGER PRIMARY KEY,
                name                  TEXT    NOT NULL UNIQUE,
                type                  TEXT    NOT NULL,          -- CHECKING, SAVINGS, CREDIT_CARD, ENVELOPE, ...
                opening_balance_cents INTEGER NOT NULL DEFAULT 0,
                currency              TEXT    NOT NULL DEFAULT 'USD',
                closed                INTEGER NOT NULL DEFAULT 0,
                parent_id             INTEGER REFERENCES account(id),  -- set for envelopes / child accounts
                bank_name             TEXT,
                start_date            TEXT,                      -- ISO: YYYY-MM-DD
                balance_adjustment_cents INTEGER NOT NULL DEFAULT 0,   -- added to the opening balance
                account_number        TEXT,
                routing_number        TEXT,
                default_category      TEXT,                      -- category path for new transactions
                comments              TEXT,
                website               TEXT,
                interest_rate_milli   INTEGER NOT NULL DEFAULT 0,   -- loans: annual rate, thousandths of a percent
                original_amount_cents INTEGER NOT NULL DEFAULT 0,   -- loans: the amount borrowed
                term_months           INTEGER NOT NULL DEFAULT 0,   -- loans: number of monthly payments
                interest_category     TEXT,                         -- loans: where the interest part of payments goes
                credit_limit_cents    INTEGER NOT NULL DEFAULT 0,   -- cards (interest_rate_milli is the APR)
                statement_day         INTEGER NOT NULL DEFAULT 0,   -- cards: day the statement closes (0 = not set)
                due_day               INTEGER NOT NULL DEFAULT 0,   -- cards: day the payment is due (0 = not set)
                min_payment_percent_milli INTEGER NOT NULL DEFAULT 0,  -- cards: minimum payment, % of balance (thousandths)
                min_payment_floor_cents   INTEGER NOT NULL DEFAULT 0,  -- cards: smallest minimum payment
                loan_payment_cents    INTEGER NOT NULL DEFAULT 0,   -- loans: the lender's payment (0 = calculated)
                first_payment_date    TEXT,                         -- loans: first payment due (NULL = a month in)
                interest_method       TEXT NOT NULL DEFAULT 'M'     -- loans: M = monthly (rate / 12), D = daily
            )
            """,
            """
            CREATE TABLE category (
                id        INTEGER PRIMARY KEY,
                name      TEXT    NOT NULL,
                parent_id INTEGER REFERENCES category(id),
                is_income INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE payee (
                id   INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
            """,
            """
            CREATE TABLE tag (
                id    INTEGER PRIMARY KEY,
                name  TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                color TEXT    NOT NULL                         -- #RRGGBB
            )
            """,
            """
            CREATE TABLE txn (
                id           INTEGER PRIMARY KEY,
                account_id   INTEGER NOT NULL REFERENCES account(id),
                txn_date     TEXT    NOT NULL,                   -- ISO format: YYYY-MM-DD
                payee_id     INTEGER REFERENCES payee(id),
                check_number TEXT,
                memo         TEXT,
                status       TEXT    NOT NULL DEFAULT 'U',       -- R=reconciled, C=cleared, I=reconciling, P=pending, D=paid, S=scheduled, U=none
                description  TEXT,
                tag_id       INTEGER REFERENCES tag(id),         -- one tag per transaction
                pre_reconcile_status TEXT,                       -- while status is I: the status to go back to
                download_status TEXT,                            -- from a bank download: M = matched, N = new
                download_id     TEXT,                            -- the download's id (OFX FITID, or made up for QIF)
                schedule_id     INTEGER REFERENCES schedule(id) ON DELETE SET NULL,  -- created by this schedule
                schedule_date   TEXT,                            -- the scheduled date it was created for
                loan_interest_cents INTEGER NOT NULL DEFAULT 0,  -- loans charged on due dates: this payment's interest (shown only)
                txn_type        TEXT,                            -- its type, like POS or DIRECTDEBIT (from a bank download's TRNTYPE)
                interest_override INTEGER,                       -- loans: a payment's interest as entered by hand (NULL = calculated)
                bank_date       TEXT                             -- ISO: the date the bank posted it (from a bank download), or NULL
            )
            """,
            """
            CREATE TABLE reconciliation (
                id                      INTEGER PRIMARY KEY,
                account_id              INTEGER NOT NULL REFERENCES account(id),
                statement_date          TEXT    NOT NULL,         -- ISO: YYYY-MM-DD
                ending_balance_cents    INTEGER NOT NULL,
                beginning_balance_cents INTEGER,                  -- set when finished
                completed_at            TEXT                      -- NULL = not finished yet
            )
            """,
            """
            CREATE TABLE attachment (
                id                INTEGER PRIMARY KEY,
                account_id        INTEGER NOT NULL REFERENCES account(id),
                txn_id            INTEGER REFERENCES txn(id) ON DELETE CASCADE,
                reconciliation_id INTEGER REFERENCES reconciliation(id) ON DELETE CASCADE,
                file_name         TEXT    NOT NULL,
                path              TEXT    NOT NULL,   -- relative to the data file's folder, '/' separated
                added_at          TEXT    NOT NULL,
                statement_date    TEXT                -- an account statement added on its own: its date
            )
            """,
            """
            CREATE TABLE split (
                id                  INTEGER PRIMARY KEY,
                txn_id              INTEGER NOT NULL REFERENCES txn(id) ON DELETE CASCADE,
                category_id         INTEGER REFERENCES category(id),
                transfer_account_id INTEGER REFERENCES account(id),
                amount_cents        INTEGER NOT NULL,            -- negative = payment, positive = deposit
                memo                TEXT,
                transfer_txn_id     INTEGER REFERENCES txn(id) ON DELETE SET NULL,  -- other side of a transfer
                loan_interest       INTEGER NOT NULL DEFAULT 0   -- loans: 1 = a payment's calculated interest,
                                                                 --   2 = all the scheduled interest (charged on due dates)
            )
            """,
            "CREATE INDEX idx_account_parent ON account(parent_id)",
            "CREATE INDEX idx_txn_account_date ON txn(account_id, txn_date)",
            "CREATE INDEX idx_split_txn ON split(txn_id)",
            "CREATE INDEX idx_txn_tag ON txn(tag_id)",
            "CREATE INDEX idx_reconciliation_account ON reconciliation(account_id)",
            "CREATE INDEX idx_attachment_txn ON attachment(txn_id)",
            "CREATE INDEX idx_attachment_reconciliation ON attachment(reconciliation_id)",
            "CREATE INDEX idx_txn_download ON txn(account_id, download_id)",
            "CREATE INDEX idx_txn_schedule ON txn(schedule_id, schedule_date)",
            """
            CREATE TABLE schedule (
                id                INTEGER PRIMARY KEY,
                account_id        INTEGER NOT NULL REFERENCES account(id),
                payee             TEXT,
                category          TEXT,                    -- category path, or "[Account]" for a transfer
                tag               TEXT,
                description       TEXT,
                memo              TEXT,
                amount_cents      INTEGER NOT NULL,        -- negative = payment, positive = deposit
                unit              TEXT    NOT NULL,        -- DAY, WEEK, MONTH, YEAR or SEMIMONTH
                interval          INTEGER NOT NULL DEFAULT 1,
                second_day        INTEGER,                 -- SEMIMONTH: the second day of the month (31 = last)
                start_date        TEXT    NOT NULL,        -- the first occurrence (ISO)
                end_date          TEXT,                    -- no occurrences after this; NULL = no end date
                max_count         INTEGER,                 -- no more than this many; NULL = no limit
                generated_through TEXT    NOT NULL         -- occurrences up to this date have been created
            )
            """,
            """
            CREATE TABLE payee_rename (
                id       INTEGER PRIMARY KEY,
                bank_key TEXT    NOT NULL UNIQUE,     -- the bank's payee name, simplified (see PayeeRenameRepository.bankKey)
                payee    TEXT    NOT NULL             -- the payee name the user chose for it
            )
            """
        };

        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
            addSyncSchema(st);
            addScheduleWeekday(st);
            addPaycheckRank(st);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO meta (key, value) VALUES (?, ?)")) {
                ps.setString(1, "schema_version");
                ps.setString(2, String.valueOf(SCHEMA_VERSION));
                ps.executeUpdate();
                ps.setString(1, "created_at");
                ps.setString(2, Instant.now().toString());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }
}