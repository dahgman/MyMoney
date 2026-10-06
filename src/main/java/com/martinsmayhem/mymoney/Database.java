package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public static final String EXTENSION = "mymoney";

    /** Increase this whenever the schema changes, and add an upgrade step in open(). */
    public static final int SCHEMA_VERSION = 1;

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

    /** Creates a brand-new encrypted file and builds the base schema in it. */
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

    /** Opens an existing encrypted file. Throws SQLException if the password is wrong. */
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
            // Future: if (version < SCHEMA_VERSION) upgrade the schema here.
            return db;
        } catch (SQLException e) {
            db.close();
            throw e;
        }
    }

    public Path getFile() {
        return file;
    }

    /** For data-access classes added later (accounts, transactions, ...). */
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
                throw new SQLException("Not a MyMoney database.");
            }
            return Integer.parseInt(rs.getString(1));
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
                type                  TEXT    NOT NULL,          -- CHECKING, SAVINGS, CREDIT_CARD, ...
                opening_balance_cents INTEGER NOT NULL DEFAULT 0,
                currency              TEXT    NOT NULL DEFAULT 'USD',
                closed                INTEGER NOT NULL DEFAULT 0
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
            CREATE TABLE txn (
                id           INTEGER PRIMARY KEY,
                account_id   INTEGER NOT NULL REFERENCES account(id),
                txn_date     TEXT    NOT NULL,                   -- ISO format: YYYY-MM-DD
                payee_id     INTEGER REFERENCES payee(id),
                check_number TEXT,
                memo         TEXT,
                status       TEXT    NOT NULL DEFAULT 'U'        -- U=uncleared, C=cleared, R=reconciled
            )
            """,
            """
            CREATE TABLE split (
                id                  INTEGER PRIMARY KEY,
                txn_id              INTEGER NOT NULL REFERENCES txn(id) ON DELETE CASCADE,
                category_id         INTEGER REFERENCES category(id),
                transfer_account_id INTEGER REFERENCES account(id),
                amount_cents        INTEGER NOT NULL,
                memo                TEXT
            )
            """,
            "CREATE INDEX idx_txn_account_date ON txn(account_id, txn_date)",
            "CREATE INDEX idx_split_txn ON split(txn_id)"
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