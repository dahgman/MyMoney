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
 * settings folder. It holds the list of recently used databases.
 * Never store financial data here.
 */
public class AppConfig implements AutoCloseable {

    private final Connection conn;

    public AppConfig() throws IOException, SQLException {
        Path dir = configDirectory();
        Files.createDirectories(dir);
        conn = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("config.db").toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS recent_database (
                    path        TEXT PRIMARY KEY,
                    last_opened TEXT NOT NULL
                )
                """);
        }
    }

    /** %APPDATA%\MyMoney on Windows; ~/.config/mymoney on Linux. */
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
        return base.resolve("mymoney");
    }

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

    /** Removes an entry from the list. Does NOT delete the file itself. */
    public void removeRecent(String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM recent_database WHERE path = ?")) {
            ps.setString(1, path);
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