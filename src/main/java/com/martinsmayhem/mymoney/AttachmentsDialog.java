package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;

/** A transaction's attached files, on their own (from the ledger). */
final class AttachmentsDialog {

    private AttachmentsDialog() {
        // static helper only
    }

    /**
     * Shows a saved transaction's attachments, to add, open or remove.
     * @return true if anything was added or removed
     */
    static boolean show(Window owner, Account account, long txnId, LocalDate txnDate,
                        AttachmentRepository attachments) {
        JDialog dlg = new JDialog(owner, "Attachments", JDialog.ModalityType.APPLICATION_MODAL);
        boolean[] changed = {false};
        AttachmentsPanel panel = new AttachmentsPanel("Files attached to this transaction",
                new AttachmentsPanel.Target() {
                    @Override
                    public List<AttachmentRepository.Attachment> list() throws SQLException {
                        return attachments.forTransaction(txnId);
                    }

                    @Override
                    public void add(Path file) throws SQLException, IOException {
                        attachments.addToTransaction(account, txnId, txnDate, file);
                    }

                    @Override
                    public void remove(AttachmentRepository.Attachment a) throws SQLException, IOException {
                        attachments.remove(a, account);
                    }

                    @Override
                    public Path file(AttachmentRepository.Attachment a) {
                        return attachments.file(a, account);
                    }
                }, () -> changed[0] = true);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dlg.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(close);
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        content.add(panel, BorderLayout.CENTER);
        dlg.getContentPane().add(content, BorderLayout.CENTER);
        dlg.getContentPane().add(buttons, BorderLayout.SOUTH);
        dlg.getRootPane().setDefaultButton(close);
        dlg.setSize(460, 300);
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        return changed[0];
    }
}
