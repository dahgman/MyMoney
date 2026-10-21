package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Application settings, stored in an UNENCRYPTED SQLite file in the user's
 * settings folder. It holds the list of recently used databases and which
 * one (if any) is the default. Never store financial data here.
 */
public class AppConfig implements AutoCloseable {

    private static final String DEFAULT_DB_KEY = "default_database";

    private final Connection conn;

    public AppConfig() throws IOException, SQLException {
        this(configDirectory().resolve("config.db"));
    }

    /** The settings kept in this file instead of the usual place (for tests). */
    AppConfig(Path configFile) throws IOException, SQLException {
        Files.createDirectories(configFile.toAbsolutePath().getParent());
        conn = DriverManager.getConnection("jdbc:sqlite:" + configFile.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS recent_database (
                    path        TEXT PRIMARY KEY,
                    last_opened TEXT NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS setting (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """);
        }
    }

    /** %APPDATA%\MyMoney on Windows; ~/.config/MyMoney on Linux. */
    public static Path configDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, "MyMoney");
            }
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Paths.get(xdg)
                : Paths.get(System.getProperty("user.home"), ".config");
        return base.resolve("MyMoney");
    }

    // ----- Recent databases ---------------------------------------------

    /** Full paths of recently used databases, most recent first. */
    public List<String> getRecentDatabases() throws SQLException {
        List<String> paths = new ArrayList<>();
        String sql = "SELECT path FROM recent_database ORDER BY last_opened DESC";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                paths.add(rs.getString(1));
            }
        }
        return paths;
    }

    /** Adds a database to the list, or moves it to the top if already there. */
    public void addRecent(Path file) throws SQLException {
        String sql = """
            INSERT INTO recent_database (path, last_opened) VALUES (?, ?)
            ON CONFLICT(path) DO UPDATE SET last_opened = excluded.last_opened
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, file.toAbsolutePath().toString());
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /**
     * Removes an entry from the list (and clears it as the default if it was).
     * Does NOT delete the file itself.
     */
    public void removeRecent(String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM recent_database WHERE path = ?")) {
            ps.setString(1, path);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM setting WHERE key = ? AND value = ?")) {
            ps.setString(1, DEFAULT_DB_KEY);
            ps.setString(2, path);
            ps.executeUpdate();
        }
    }

    /** After a data file is renamed: the recent list and the default follow it. */
    public void renamed(Path from, Path to) throws SQLException {
        String old = from.toAbsolutePath().toString();
        boolean wasDefault = old.equals(getDefaultDatabase());
        removeRecent(old);
        addRecent(to);
        if (wasDefault) {
            setDefaultDatabase(to);
        }
    }

    // ----- Default database ---------------------------------------------

    /** Full path of the default database, or null if none is set. */
    public String getDefaultDatabase() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM setting WHERE key = ?")) {
            ps.setString(1, DEFAULT_DB_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Marks a database as the one to open automatically at startup. */
    public void setDefaultDatabase(Path file) throws SQLException {
        String sql = """
            INSERT INTO setting (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DEFAULT_DB_KEY);
            ps.setString(2, file.toAbsolutePath().toString());
            ps.executeUpdate();
        }
    }

    /** No default: the database selector will open at startup. */
    public void clearDefaultDatabase() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM setting WHERE key = ?")) {
            ps.setString(1, DEFAULT_DB_KEY);
            ps.executeUpdate();
        }
    }

    // ----- Preferences ----------------------------------------------------

    /** Show each account's cleared balance in the account tree. */
    public static final String SHOW_TREE_BALANCES = "show_tree_balances";
    /** With tree balances shown: each account type heading shows its accounts' total (on unless turned off). */
    public static final String SHOW_TREE_GROUP_TOTALS = "show_tree_group_totals";

    /** Bank imports: match downloaded transactions with existing ones. */
    public static final String MATCH_DOWNLOADS = "match_downloads";
    /** Bank imports: how many days apart a download and an existing transaction may be to match. */
    public static final String MATCH_DAYS = "match_days";
    public static final int DEFAULT_MATCH_DAYS = 5;
    /** Scheduled transactions: how many days ahead their occurrences are put in the ledger. */
    public static final String SCHEDULE_DAYS = "schedule_days";
    public static final int DEFAULT_SCHEDULE_DAYS = 14;
    /** The ledger columns hidden from view: their numbers (LedgerTableModel), comma-separated. */
    public static final String HIDDEN_LEDGER_COLUMNS = "hidden_ledger_columns";
    /** The same for loans' and credit cards' ledgers (chosen separately). */
    public static final String HIDDEN_DEBT_COLUMNS = "hidden_debt_columns";
    /** Editing the ledger: Enter moves to the next field, like Tab (false: down to the next transaction). */
    public static final String ENTER_NEXT_FIELD = "enter_next_field";
    /** Typed amounts: digits with no decimal point are cents (12345 = $123.45). */
    public static final String AUTO_DECIMAL = "auto_decimal";
    /** How dates are shown and typed: one of LedgerTableModel.DATE_FORMATS, like "MM/dd/yyyy". */
    public static final String DATE_FORMAT = "date_format";
    /** The look: "system" (match the desktop's light or dark mode), "light" or "dark"; see Theme. */
    public static final String THEME = "theme";
    /** The font everything is drawn in: a family name ("" = the look's own) and a size in points (0 = its own). */
    public static final String FONT_FAMILY = "font_family";
    public static final String FONT_SIZE = "font_size";
    /** The main window when it was last closed: "x,y,width,height" (its normal size), and whether maximized. */
    public static final String WINDOW_BOUNDS = "window_bounds";
    public static final String WINDOW_MAXIMIZED = "window_maximized";
    /** Open on the Summary screen instead of the first account's ledger. */
    public static final String OPEN_ON_SUMMARY = "open_on_summary";
    /** Where database backups go: a folder (blank: asked the first time, then kept here). */
    public static final String BACKUP_FOLDER = "backup_folder";
    /** Remind me to back up a file after it's been used (opened) this many times since its last backup; 0 = never. */
    public static final String BACKUP_REMIND_EVERY = "backup_remind_every";
    public static final int DEFAULT_BACKUP_REMIND_EVERY = 10;
    /** Keep at most this many backups a day (the latest); 0 = all of them. */
    public static final String BACKUP_KEEP_PER_DAY = "backup_keep_per_day";
    /** Backups older than this many days: only the last of each day is kept; 0 = keep them all. */
    public static final String BACKUP_THIN_AFTER_DAYS = "backup_thin_after_days";
    /** What's done with a bank download file once it's imported: "leave", "delete" or "move". */
    public static final String IMPORT_FILE_ACTION = "import_file_action";
    /** A data file's uses (openings) since its last backup: this key + the file's path. */
    public static final String BACKUP_USES_PREFIX = "backup_uses:";

    /** A text preference, or def if it was never set (or can't be read). */
    public String getString(String key, String def) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : def;
            }
        } catch (SQLException e) {
            return def;
        }
    }

    public void setString(String key, String value) throws SQLException {
        setValue(key, value);
    }

    /** A whole-number preference, or def if it was never set (or can't be read). */
    public int getInt(String key, int def) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.parseInt(rs.getString(1)) : def;
            }
        } catch (SQLException | NumberFormatException e) {
            return def;
        }
    }

    public void setInt(String key, int value) throws SQLException {
        setValue(key, String.valueOf(value));
    }

    /** A yes/no preference, or def if it was never set (or can't be read). */
    public boolean getBoolean(String key, boolean def) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Boolean.parseBoolean(rs.getString(1)) : def;
            }
        } catch (SQLException e) {
            return def;
        }
    }

    public void setBoolean(String key, boolean value) throws SQLException {
        setValue(key, String.valueOf(value));
    }

    private void setValue(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO setting (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}