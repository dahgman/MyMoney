package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/**
 * An account's statements (Account > Statements...), for any kind of
 * account: add one any time (a PDF from the bank, a scan, ...), open,
 * re-date or remove them. Statements saved while reconciling are listed
 * too. The files are copied into the account's folder next to the data
 * file, with the transaction attachments:
 *
 *   <Account>/Attachments/Statements/YYYY/MM/DD/
 */
final class StatementsDialog extends JDialog {

    private final Account account;
    private final AttachmentRepository attachments;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JButton openButton = new JButton("Open");
    private final JButton redateButton = new JButton("Change Date...");
    private final JButton removeButton = new JButton("Remove");
    private List<AttachmentRepository.Statement> rows = List.of();
    private boolean changed;

    private StatementsDialog(Window owner, Account account, AttachmentRepository attachments) {
        super(owner, "Statements: " + account.name(), ModalityType.APPLICATION_MODAL);
        this.account = account;
        this.attachments = attachments;

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(360);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelected();
                }
            }
        });
        ColumnFit.install(table);

        JButton add = new JButton("Add...");
        add.addActionListener(e -> addStatements());
        openButton.addActionListener(e -> openSelected());
        redateButton.addActionListener(e -> redateSelected());
        removeButton.addActionListener(e -> removeSelected());
        JButton folder = new JButton("Open Folder");
        folder.setToolTipText("Show the folder the statements are filed in");
        folder.addActionListener(e -> openFolder());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.add(add);
        left.add(openButton);
        left.add(redateButton);
        left.add(removeButton);
        left.add(folder);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(6, 4, 8, 4));
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JLabel help = new JLabel("<html><div style='width:560px'>Statements are copied into <b>"
                + AttachmentRepository.folderName(account.name())
                + "/Attachments/Statements</b>, next to the data file, filed by date."
                + " Double-click one to open it.</div></html>");
        help.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8),
                scroll.getBorder()));
        getContentPane().add(help, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        reload();
        setSize(680, 420);
        setLocationRelativeTo(owner);
    }

    /**
     * Shows an account's statements.
     * @return true if any were added, re-dated or removed
     */
    static boolean show(Window owner, Account account, AttachmentRepository attachments) {
        StatementsDialog d = new StatementsDialog(owner, account, attachments);
        d.setVisible(true);
        return d.changed;
    }

    private void reload() {
        try {
            rows = attachments.statements(account.id());
        } catch (SQLException e) {
            rows = List.of();
            error("Could not read the statements:\n" + e.getMessage());
        }
        model.fireTableDataChanged();
        updateButtons();
    }

    private void updateButtons() {
        List<AttachmentRepository.Statement> chosen = selected();
        openButton.setEnabled(!chosen.isEmpty());
        removeButton.setEnabled(!chosen.isEmpty());
        redateButton.setEnabled(chosen.size() == 1 && !chosen.get(0).fromReconcile());
        redateButton.setToolTipText(chosen.size() == 1 && chosen.get(0).fromReconcile()
                ? "Saved while reconciling: dated by the reconciliation" : null);
    }

    private List<AttachmentRepository.Statement> selected() {
        List<AttachmentRepository.Statement> list = new ArrayList<>();
        for (int r : table.getSelectedRows()) {
            list.add(rows.get(table.convertRowIndexToModel(r)));
        }
        return list;
    }

    private void addStatements() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Add Statements: " + account.name());
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || fc.getSelectedFiles().length == 0) {
            return;
        }
        for (java.io.File f : fc.getSelectedFiles()) {
            LocalDate date = askDate("Statement date for \"" + f.getName() + "\":", guessDate(f.getName()));
            if (date == null) {
                continue;                               // skipped this one
            }
            try {
                attachments.addStatement(account, date, f.toPath());
                changed = true;
            } catch (SQLException | IOException e) {
                error("Could not add \"" + f.getName() + "\":\n" + e.getMessage());
            }
        }
        reload();
    }

    private void openSelected() {
        for (AttachmentRepository.Statement s : selected()) {
            AttachmentsPanel.open(this, attachments.file(s.attachment(), account));
        }
    }

    private void redateSelected() {
        List<AttachmentRepository.Statement> chosen = selected();
        if (chosen.size() != 1 || chosen.get(0).fromReconcile()) {
            return;
        }
        AttachmentRepository.Statement s = chosen.get(0);
        LocalDate date = askDate("New statement date for \"" + s.attachment().fileName() + "\":", s.date());
        if (date == null || date.equals(s.date())) {
            return;
        }
        try {
            attachments.redateStatement(s, account, date);
            changed = true;
        } catch (SQLException | IOException e) {
            error("Could not change the date:\n" + e.getMessage());
        }
        reload();
    }

    private void removeSelected() {
        List<AttachmentRepository.Statement> chosen = selected();
        if (chosen.isEmpty()) {
            return;
        }
        String what = chosen.size() == 1 ? "\"" + chosen.get(0).attachment().fileName() + "\""
                : chosen.size() + " statements";
        if (JOptionPane.showConfirmDialog(this, "Remove " + what + "?\n\nThe copy filed with the account is"
                + " deleted; the original file you added is not touched.", "Remove Statement",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        for (AttachmentRepository.Statement s : chosen) {
            try {
                attachments.remove(s.attachment(), account);
                changed = true;
            } catch (SQLException | IOException e) {
                error("Could not remove \"" + s.attachment().fileName() + "\":\n" + e.getMessage());
            }
        }
        reload();
    }

    private void openFolder() {
        Path folder = attachments.statementsFolder(account);
        if (!Files.isDirectory(folder)) {
            JOptionPane.showMessageDialog(this, "There's no statements folder yet: it's made when the first"
                    + " statement is added.\n\n" + folder, getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        AttachmentsPanel.open(this, folder);
    }

    /** Asks for a date, starting at suggested. Null if cancelled. */
    private LocalDate askDate(String question, LocalDate suggested) {
        DatePicker picker = new DatePicker(suggested);
        while (true) {
            int r = JOptionPane.showConfirmDialog(this, new Object[] {question, picker}, getTitle(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                return null;
            }
            try {
                return picker.getDate();
            } catch (IllegalArgumentException e) {
                error(e.getMessage());
            }
        }
    }

    // A date in a file name: 2026-09-30, 2026_09_30, 20260930; or 09-30-2026, 09302026 (in the date
    // format's order: month or day first).
    private static final Pattern YEAR_FIRST = Pattern.compile("(?<!\\d)(20\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})(?!\\d)");
    private static final Pattern YEAR_LAST = Pattern.compile("(?<!\\d)(\\d{2})[-_.]?(\\d{2})[-_.]?(20\\d{2})(?!\\d)");

    /**
     * The statement date a file's name suggests (banks usually put it
     * there), or else the last day of last month, when most statements end.
     */
    static LocalDate guessDate(String fileName) {
        Matcher m = YEAR_FIRST.matcher(fileName);
        while (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
            } catch (java.time.DateTimeException ignored) {
                // not a date after all
            }
        }
        m = YEAR_LAST.matcher(fileName);
        boolean dayFirst = LedgerTableModel.dateFormatExample().startsWith("DD");
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            int y = Integer.parseInt(m.group(3));
            try {
                return dayFirst ? LocalDate.of(y, b, a) : LocalDate.of(y, a, b);
            } catch (java.time.DateTimeException ignored) {
                // not a date after all
            }
        }
        return YearMonth.now().minusMonths(1).atEndOfMonth();
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Statement Date", "File", "Added"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            AttachmentRepository.Statement s = rows.get(row);
            return switch (column) {
                case 0 -> s.date().format(LedgerTableModel.DISPLAY_DATE);
                case 1 -> s.attachment().fileName();
                case 2 -> s.fromReconcile() ? "While reconciling" : "Added";
                default -> "";
            };
        }
    }
}
