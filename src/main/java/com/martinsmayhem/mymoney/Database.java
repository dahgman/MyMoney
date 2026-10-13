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
     * When changing the schema: bump this, update createSchema(), and add a step to upgrade().
     */
    public static final int SCHEMA_VERSION = 9;

    private final Path file;
    private final Connection conn;

    private Database(Path file, String password) throws SQLException {
        this.file = file.toAbsolutePath();
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
        return "jdbc:sqlite:" + file.toUri() + "?cipher=sqlcipher&legacy=4&key=" + key;
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
            // Future: if (fromVersion < 10) { ... }

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
                website               TEXT
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
                schedule_date   TEXT                             -- the scheduled date it was created for
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
                added_at          TEXT    NOT NULL
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
                transfer_txn_id     INTEGER REFERENCES txn(id) ON DELETE SET NULL   -- other side of a transfer
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