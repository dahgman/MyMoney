package com.martinsmayhem.mymoney;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Application entry point.
 * Startup flow:
 *   1. No databases yet          -> New Database dialog (new file becomes the default)
 *   2. A default database is set -> ask for its password and open it
 *   3. Otherwise, or if cancelled -> database selector (StartupDialog)
 */
public class MyMoney {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to the default look and feel
        }
        SwingUtilities.invokeLater(MyMoney::start);
    }

    private static void start() {
        AppConfig config;
        try {
            config = new AppConfig();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "Could not open application settings:\n" + e.getMessage(),
                    "MyMoney", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        Database db = null;

        List<String> recent;
        try {
            recent = config.getRecentDatabases();
        } catch (SQLException e) {
            recent = List.of();
        }

        if (recent.isEmpty()) {
            // 1. First run: create a database. It is marked as the default automatically.
            NewDatabaseDialog nd = new NewDatabaseDialog(null, true, config);
            nd.setLocationRelativeTo(null);
            nd.setVisible(true);              // modal: waits here until closed
            db = nd.getDatabase();
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
            StartupDialog sd = new StartupDialog(null, true, config);
            sd.setLocationRelativeTo(null);
            sd.setVisible(true);
            db = sd.getDatabase();
        }

        if (db == null) {                     // user chose Exit
            config.close();
            System.exit(0);
            return;
        }

        MainFrame frame = new MainFrame(db, config);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}