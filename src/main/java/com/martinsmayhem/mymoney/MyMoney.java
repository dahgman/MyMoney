package com.martinsmayhem.mymoney;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Application entry point.
 * Startup flow (behind the splash screen, which steps aside for the dialogs):
 *   1. No databases yet          -> New Database dialog (new file becomes the default)
 *   2. A default database is set -> ask for its password and open it
 *   3. Otherwise, or if cancelled -> database selector (StartupDialog)
 */
public class MyMoney {

    /** The version and release date (shown on the startup screen and in Help > About): pom.xml's, via version.properties. */
    static final String VERSION;
    /** The release date, or null when run without a Maven build. */
    static final java.time.LocalDate RELEASE_DATE;

    static {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = MyMoney.class.getResourceAsStream("version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (java.io.IOException ignored) {
            // shown as "dev", then
        }
        VERSION = filled(p.getProperty("version"), "dev");
        String date = filled(p.getProperty("releaseDate"), null);
        java.time.LocalDate released = null;
        try {
            released = date == null ? null : java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException ignored) {
            // not a date: left out
        }
        RELEASE_DATE = released;
    }

    /** A value Maven filled in, or def (unfiltered, "${...}": run without a Maven build). */
    private static String filled(String value, String def) {
        return value == null || value.isBlank() || value.startsWith("${") ? def : value.trim();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MyMoney::start);
    }

    private static void start() {
        // Settings first: they say which theme everything, the splash screen included, is drawn in.
        AppConfig config;
        try {
            config = new AppConfig();
        } catch (Exception e) {
            Theme.apply(Theme.Choice.SYSTEM);
            JOptionPane.showMessageDialog(null,
                    "Could not open application settings:\n" + e.getMessage(),
                    "MyMoney", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        Theme.setFont(config.getString(AppConfig.FONT_FAMILY, ""), config.getInt(AppConfig.FONT_SIZE, 0));
        Theme.apply(Theme.Choice.of(config.getString(AppConfig.THEME, "system")));
        Splash.start();
        AppIcon.install();                    // the icon for every window, dialogs included
        Splash.step("Reading settings…", 8);

        Database db = null;

        List<String> recent;
        try {
            recent = config.getRecentDatabases();
        } catch (SQLException e) {
            recent = List.of();
        }

        if (recent.isEmpty()) {
            // 1. First run: create a new file or open an existing one. Either becomes the default.
            Splash.pause();
            db = StartupDialog.firstRun(config);
            if (db == null) {                 // Exit
                Splash.finish();
                config.close();
                System.exit(0);
                return;
            }
        } else {
            // 2. Open the default database, if one is set and the file still exists.
            String defaultPath = null;
            try {
                defaultPath = config.getDefaultDatabase();
            } catch (SQLException ignored) {
            }
            if (defaultPath != null) {
                Path file = Paths.get(defaultPath);
                if (Files.isRegularFile(file)) {
                    db = PasswordPrompt.open(null, file);
                    if (db != null) {
                        try {
                            config.addRecent(file);
                        } catch (SQLException ignored) {
                        }
                    }
                }
            }
        }

        // 3. No default, file missing, or the user cancelled: show the selector.
        if (db == null) {
            Splash.pause();
            StartupDialog sd = new StartupDialog(null, true, config);
            sd.setLocationRelativeTo(null);
            sd.setVisible(true);
            db = sd.getDatabase();
        }

        if (db == null) {                     // user chose Exit
            Splash.finish();
            config.close();
            System.exit(0);
            return;
        }

        Splash.step("Opening " + db.getFile().getFileName() + "…", 35);
        MainFrame frame = new MainFrame(db, config);   // reports its own steps
        frame.restoreWindow();                // where and how big it was last time (or centered)
        frame.setVisible(true);
        Splash.finish();
    }
}