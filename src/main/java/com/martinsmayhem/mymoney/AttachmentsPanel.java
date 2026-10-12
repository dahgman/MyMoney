package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A list of attached files with Add, Open and Remove buttons. What the files
 * are attached to (a transaction, a statement) is up to the Target.
 */
final class AttachmentsPanel extends JPanel {

    /** What the files are attached to. */
    interface Target {
        List<AttachmentRepository.Attachment> list() throws SQLException;

        void add(Path file) throws SQLException, IOException;

        void remove(AttachmentRepository.Attachment a) throws SQLException, IOException;

        /** Where an attachment's file is now. */
        Path file(AttachmentRepository.Attachment a);
    }

    private final Target target;
    private final Runnable onChanged;
    private final DefaultListModel<AttachmentRepository.Attachment> model = new DefaultListModel<>();
    private final JList<AttachmentRepository.Attachment> list = new JList<>(model);
    private final JButton openButton = new JButton("Open");
    private final JButton removeButton = new JButton("Remove");

    /** @param onChanged called after a file is added or removed */
    AttachmentsPanel(String title, Target target, Runnable onChanged) {
        super(new BorderLayout(0, 4));
        this.target = target;
        this.onChanged = onChanged;
        setBorder(BorderFactory.createTitledBorder(title));

        list.setVisibleRowCount(4);
        list.addListSelectionListener(e -> updateButtons());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelected();
                }
            }
        });
        add(new JScrollPane(list), BorderLayout.CENTER);

        JButton addButton = new JButton("Add...");
        addButton.addActionListener(e -> addFiles());
        openButton.addActionListener(e -> openSelected());
        removeButton.addActionListener(e -> removeSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(addButton);
        buttons.add(openButton);
        buttons.add(removeButton);
        add(buttons, BorderLayout.SOUTH);
        reload();
    }

    /** Re-reads the list of files. */
    void reload() {
        model.clear();
        try {
            for (AttachmentRepository.Attachment a : target.list()) {
                model.addElement(a);
            }
        } catch (SQLException e) {
            error("Could not read the attachments:\n" + e.getMessage());
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean any = !list.isSelectionEmpty();
        openButton.setEnabled(any);
        removeButton.setEnabled(any);
    }

    private void addFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Attach Files");
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        for (java.io.File f : fc.getSelectedFiles()) {
            try {
                target.add(f.toPath());
            } catch (SQLException | IOException e) {
                error("Could not attach \"" + f.getName() + "\":\n" + e.getMessage());
            }
        }
        reload();
        onChanged.run();
    }

    private void openSelected() {
        for (AttachmentRepository.Attachment a : list.getSelectedValuesList()) {
            open(this, target.file(a));
        }
    }

    private void removeSelected() {
        List<AttachmentRepository.Attachment> chosen = list.getSelectedValuesList();
        if (chosen.isEmpty()) {
            return;
        }
        String what = chosen.size() == 1 ? "\"" + chosen.get(0).fileName() + "\"" : chosen.size() + " files";
        if (JOptionPane.showConfirmDialog(this, "Remove " + what + "?\n\nThe attached copy is deleted;"
                + " the original file you attached is not touched.", "Remove Attachment",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        for (AttachmentRepository.Attachment a : chosen) {
            try {
                target.remove(a);
            } catch (SQLException | IOException e) {
                error("Could not remove \"" + a.fileName() + "\":\n" + e.getMessage());
            }
        }
        reload();
        onChanged.run();
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Attachments", JOptionPane.ERROR_MESSAGE);
    }

    /** Opens a file in the program the system uses for it (in the background). */
    static void open(Component parent, Path file) {
        if (!Files.exists(file)) {
            JOptionPane.showMessageDialog(parent, "The file is missing:\n" + file,
                    "Attachments", JOptionPane.ERROR_MESSAGE);
            return;
        }
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(file.toFile());
                } else {
                    String os = System.getProperty("os.name").toLowerCase();
                    String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
                    new ProcessBuilder(cmd, file.toString()).start();
                }
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(parent,
                        "Could not open the file:\n" + e.getMessage(), "Attachments", JOptionPane.ERROR_MESSAGE));
            }
        }, "open-attachment").start();
    }
}
