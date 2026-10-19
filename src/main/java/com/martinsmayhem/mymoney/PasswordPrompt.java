package com.martinsmayhem.mymoney;

import java.awt.Component;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

/**
 * Asks for a database's password and opens it.
 * A wrong password shows an error and asks again; Cancel gives up.
 */
public final class PasswordPrompt {

    private PasswordPrompt() {
        // static helper only
    }

    /**
     * @param parent the window to center the prompt over (may be null)
     * @param file   the encrypted database to open
     * @return the open Database, or null if the user cancelled
     */
    /**
     * The file is locked. A lock left behind by a MyMoney that stopped
     * mid-save (on a network share) can be cleared, then the file opened;
     * otherwise it's in use, and that's said. Returns the file opened, or null.
     */
    private static Database whenLocked(Component parent, Path file, String password) {
        Path leftover = Database.leftoverLock(file);
        if (leftover == null) {
            JOptionPane.showMessageDialog(parent, "\"" + file.getFileName() + "\" is locked: it's in use, by"
                    + " MyMoney on another computer or another program.\n\nClose it there, then try again.",
                    "File in Use", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        int r = JOptionPane.showConfirmDialog(parent, "\"" + file.getFileName() + "\" is locked, by a lock left"
                + " behind:\n    " + leftover + "\n\nThat happens if MyMoney stopped while saving. If MyMoney"
                + " isn't open on another computer, the lock can be cleared and the file opened.\n\nClear it?",
                "File Locked", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) {
            return null;
        }
        try {
            Database.clearLock(leftover);
            return Database.open(file, password);
        } catch (java.io.IOException | SQLException e) {
            JOptionPane.showMessageDialog(parent, "Could not open the file:\n" + e.getMessage(), "Open Failed",
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    public static Database open(Component parent, Path file) {
        while (true) {
            JPasswordField pf = new JPasswordField(20);

            // Put the cursor in the password field as soon as the prompt appears.
            pf.addAncestorListener(new AncestorListener() {
                @Override
                public void ancestorAdded(AncestorEvent e) {
                    SwingUtilities.invokeLater(pf::requestFocusInWindow);
                }
                @Override
                public void ancestorRemoved(AncestorEvent e) { }
                @Override
                public void ancestorMoved(AncestorEvent e) { }
            });

            Splash.pause();                             // (at startup) the prompt goes on top
            int r = JOptionPane.showConfirmDialog(parent, pf,
                    "Password for " + file.getFileName(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }

            char[] pw = pf.getPassword();
            try {
                Splash.step("Unlocking " + file.getFileName() + "…", 20);   // decrypting takes a moment
                return Database.open(file, new String(pw));
            } catch (SQLException e) {
                Splash.pause();
                if (Database.isLocked(e)) {
                    Database opened = whenLocked(parent, file, new String(pw));
                    if (opened != null) {
                        return opened;
                    }
                    return null;                        // in use: nothing more to try now
                }
                JOptionPane.showMessageDialog(parent,
                        "Could not open the file. The password may be wrong,\n"
                        + "or it may not be a MyMoney file.\n\nPlease try again.",
                        "Open Failed", JOptionPane.ERROR_MESSAGE);
            } finally {
                Arrays.fill(pw, '\0');
            }
        }
    }
}