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

            int r = JOptionPane.showConfirmDialog(parent, pf,
                    "Password for " + file.getFileName(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }

            char[] pw = pf.getPassword();
            try {
                return Database.open(file, new String(pw));
            } catch (SQLException e) {
                JOptionPane.showMessageDialog(parent,
                        "Could not open the database. The password may be wrong,\n"
                        + "or the file may not be a MyMoney database.\n\nPlease try again.",
                        "Open Failed", JOptionPane.ERROR_MESSAGE);
            } finally {
                Arrays.fill(pw, '\0');
            }
        }
    }
}