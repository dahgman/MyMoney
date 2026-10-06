package com.martinsmayhem.mymoney;

import java.sql.SQLException;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Application entry point.
 * Flow: open settings -> (New Database dialog if no recent files) -> Startup dialog -> MainFrame.
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

        boolean noRecent;
        try {
            noRecent = config.getRecentDatabases().isEmpty();
        } catch (SQLException e) {
            noRecent = true;
        }

        // First run (or empty list): go straight to creating a database.
        if (noRecent) {
            NewDatabaseDialog nd = new NewDatabaseDialog(null, true, config);
            nd.setLocationRelativeTo(null);
            nd.setVisible(true);          // modal: waits here until closed
            db = nd.getDatabase();
        }

        // Otherwise, or if they cancelled: show the list of recent databases.
        if (db == null) {
            StartupDialog sd = new StartupDialog(null, true, config);
            sd.setLocationRelativeTo(null);
            sd.setVisible(true);
            db = sd.getDatabase();
        }

        if (db == null) {                 // user chose Exit
            config.close();
            System.exit(0);
            return;
        }

        MainFrame frame = new MainFrame(db, config);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}