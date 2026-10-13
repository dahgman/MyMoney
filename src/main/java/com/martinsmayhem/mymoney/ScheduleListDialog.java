package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Account > Scheduled Transactions: every schedule, with New, Edit and Delete.
 * Each row shows the account, payee, amount, how often, the next date and
 * when it ends. Import, Export and Template read and write schedules as CSV
 * files for a spreadsheet (see ScheduleCsv).
 */
final class ScheduleListDialog extends JDialog {

    private final ScheduleRepository schedules;
    private final AccountRepository accounts;
    private final Function<ScheduleRepository.Schedule, Long> openForm;  // the form; returns the saved id or null
    private final java.nio.file.Path folder;                               // where files are offered first
    private final List<ScheduleRepository.Schedule> rows = new ArrayList<>();
    private final Map<Long, String> accountNames = new HashMap<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JButton editButton = new JButton("Edit...");
    private final JButton deleteButton = new JButton("Delete...");
    private final JButton linkButton = new JButton("Link to Debt...");
    private boolean changed;

    /**
     * @param openForm opens the schedule form for a schedule (id 0 = new); returns the saved id, or null
     */
    ScheduleListDialog(Window owner, ScheduleRepository schedules, AccountRepository accounts,
                       Function<ScheduleRepository.Schedule, Long> openForm, java.nio.file.Path folder) {
        super(owner, "Scheduled Transactions", ModalityType.APPLICATION_MODAL);
        this.schedules = schedules;
        this.accounts = accounts;
        this.openForm = openForm;
        this.folder = folder;

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selectedRow() >= 0) {
                    edit();
                }
            }
        });
        int[] widths = {130, 170, 90, 170, 95, 130};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        // Values are sortable (cents, dates, ...); the renderers show them as text.
        DefaultTableCellRenderer money = new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(value instanceof Long cents ? Money.format(cents) : "");
            }
        };
        money.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(money);
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(value instanceof LocalDate d
                        ? (d.equals(LocalDate.MAX) ? "(ended)" : d.format(LedgerTableModel.DISPLAY_DATE)) : "");
            }
        });
        // Click a header to sort by it (again to reverse); one column at a time.
        javax.swing.table.TableRowSorter<Model> sorter = new javax.swing.table.TableRowSorter<>(model) {
            @Override
            public void toggleSortOrder(int column) {
                if (!ColumnFit.pressedOnBorder(table.getTableHeader())) {
                    super.toggleSortOrder(column);      // a click on a border resizes, not sorts
                }
            }
        };
        sorter.setMaxSortKeys(1);
        sorter.setComparator(0, String.CASE_INSENSITIVE_ORDER);
        sorter.setComparator(1, String.CASE_INSENSITIVE_ORDER);
        table.setRowSorter(sorter);
        ColumnFit.install(table);

        JButton newButton = new JButton("New...");
        newButton.addActionListener(e -> add());
        editButton.addActionListener(e -> edit());
        deleteButton.addActionListener(e -> delete());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(newButton);
        left.add(editButton);
        left.add(deleteButton);
        left.add(linkButton);
        linkButton.setToolTipText("Tie this payment to the loan or credit card it pays, so payments lower its balance");
        linkButton.addActionListener(e -> linkToDebt());
        JButton importButton = new JButton("Import...");
        importButton.setToolTipText("Add schedules from a CSV file (like the template)");
        importButton.addActionListener(e -> importFile());
        JButton exportButton = new JButton("Export...");
        exportButton.setToolTipText("Save every schedule to a CSV file, for a spreadsheet or to import elsewhere");
        exportButton.addActionListener(e -> exportFile());
        JButton templateButton = new JButton("Template...");
        templateButton.setToolTipText("Save a CSV template with instructions and examples to fill in and import");
        templateButton.addActionListener(e -> saveTemplate());
        JPanel right2 = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right2.add(importButton);
        right2.add(exportButton);
        right2.add(templateButton);
        right2.add(javax.swing.Box.createHorizontalStrut(12));
        right2.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right2, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8),
                scroll.getBorder()));
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        load(null);
        setSize(940, 420);
        setLocationRelativeTo(owner);
    }

    /** True if a schedule was added, changed or deleted. */
    boolean wasChanged() {
        return changed;
    }

    private void load(Long selectId) {
        rows.clear();
        accountNames.clear();
        try {
            for (Account a : accounts.findAll()) {
                accountNames.put(a.id(), a.name());
            }
            rows.addAll(schedules.findAll());
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not read the schedules:\n" + e.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
        rows.sort((a, b) -> {
            LocalDate x = next(a);
            LocalDate y = next(b);
            if (x == null || y == null) {
                return x == null ? (y == null ? 0 : 1) : -1;   // ended ones last
            }
            return x.compareTo(y);
        });
        model.fireTableDataChanged();
        for (int i = 0; i < rows.size(); i++) {
            if (selectId != null && rows.get(i).id() == selectId) {
                int v = table.convertRowIndexToView(i);
                table.setRowSelectionInterval(v, v);
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean any = selectedRow() >= 0;
        editButton.setEnabled(any);
        deleteButton.setEnabled(any);
        linkButton.setEnabled(any);
    }

    private void add() {
        Long id = openForm.apply(null);
        if (id != null) {
            changed = true;
            load(id);
        }
    }

    private void edit() {
        int row = selectedRow();
        if (row < 0) {
            return;
        }
        Long id = openForm.apply(rows.get(row));
        if (id != null) {
            changed = true;
            load(id);
        }
    }

    private void delete() {
        int row = selectedRow();
        if (row < 0) {
            return;
        }
        ScheduleRepository.Schedule s = rows.get(row);
        int upcoming;
        try {
            upcoming = schedules.countUntouchedUpcoming(s.id());
        } catch (SQLException e) {
            upcoming = 0;
        }
        String what = "\"" + (s.payee() == null ? "(no payee)" : s.payee()) + "\" (" + Money.format(s.amountCents())
                + ", " + s.recurrence().describe().toLowerCase() + ")";
        boolean removeUpcoming = false;
        if (upcoming > 0) {
            Object[] options = {"Delete Them Too", "Keep Them", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this, "Delete the schedule " + what + "?\n\n"
                    + "It has " + upcoming + (upcoming == 1 ? " upcoming transaction" : " upcoming transactions")
                    + " in the ledger, still Scheduled. Delete " + (upcoming == 1 ? "it" : "them") + " too?",
                    getTitle(), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice != 0 && choice != 1) {
                return;
            }
            removeUpcoming = choice == 0;
        } else if (JOptionPane.showConfirmDialog(this, "Delete the schedule " + what + "?", getTitle(),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            schedules.delete(s.id(), removeUpcoming);
            changed = true;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not delete the schedule:\n" + e.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
        load(null);
    }

    /** Ties the selected schedule to the debt (loan or card account) it pays. */
    private void linkToDebt() {
        int row = selectedRow();
        if (row < 0) {
            return;
        }
        ScheduleRepository.Schedule s = rows.get(row);
        if (s.amountCents() >= 0) {
            error("Only payments (money going out) can be linked to a debt.");
            return;
        }
        try {
            LinkDebtDialog dlg = new LinkDebtDialog(this, s, accountNames.getOrDefault(s.accountId(), "?"),
                    accounts, schedules);
            dlg.setVisible(true);
            if (dlg.linkedAccountId() != null) {
                changed = true;
                load(s.id());
            }
        } catch (SQLException e) {
            error("Could not read the accounts:\n" + e.getMessage());
        }
    }

    // ----- Files ---------------------------------------------------------------

    private void importFile() {
        javax.swing.JFileChooser fc = chooser("Import Schedules");
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path file = fc.getSelectedFile().toPath();
        List<ScheduleCsv.Row> fileRows;
        try {
            fileRows = ScheduleCsv.parse(QifParser.readText(file), accounts.findAll());
        } catch (java.io.IOException | SQLException e) {
            error("Could not read the file:\n" + e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        if (fileRows.isEmpty()) {
            error("The file has no schedules in it (only the header, comments or blank lines).");
            return;
        }
        try {
            ScheduleImportDialog dlg = new ScheduleImportDialog(this, file.getFileName().toString(), fileRows, schedules);
            dlg.setVisible(true);
            if (dlg.importedCount() > 0) {
                changed = true;
                load(null);
            }
        } catch (SQLException e) {
            error("Could not read the schedules:\n" + e.getMessage());
        }
    }

    private void exportFile() {
        List<ScheduleRepository.Schedule> all;
        try {
            all = schedules.findAll();
        } catch (SQLException e) {
            error("Could not read the schedules:\n" + e.getMessage());
            return;
        }
        if (all.isEmpty()) {
            error("There are no schedules to export yet.");
            return;
        }
        save("Export Schedules", "MyMoney Schedules.csv", ScheduleCsv.export(all, accountNames),
                all.size() + (all.size() == 1 ? " schedule" : " schedules") + " exported");
    }

    private void saveTemplate() {
        List<String> names = new ArrayList<>();
        try {
            for (Account a : accounts.findAll()) {
                names.add(a.name());
            }
        } catch (SQLException e) {
            // the template's examples use "Checking", then
        }
        save("Save Schedule Template", "MyMoney Schedule Template.csv", ScheduleCsv.template(names),
                "Template saved. Open it in a spreadsheet, replace the example rows with your schedules,"
                        + " save it as CSV, and use Import... to add them");
    }

    /** Asks where to save, then writes the CSV (UTF-8 with a byte-order mark, so Excel reads it right). */
    private void save(String title, String suggested, String csv, String done) {
        javax.swing.JFileChooser fc = chooser(title);
        fc.setSelectedFile(folder.resolve(suggested).toFile());
        if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.nio.file.Path file = fc.getSelectedFile().toPath();
        if (!file.getFileName().toString().toLowerCase().endsWith(".csv")) {
            file = file.resolveSibling(file.getFileName() + ".csv");
        }
        if (java.nio.file.Files.exists(file) && JOptionPane.showConfirmDialog(this, "\"" + file.getFileName()
                + "\" already exists. Replace it?", title, JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            java.nio.file.Files.writeString(file, "\uFEFF" + csv, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            error("Could not save the file:\n" + e.getMessage());
            return;
        }
        JOptionPane.showMessageDialog(this, done + ":\n" + file, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private javax.swing.JFileChooser chooser(String title) {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(folder.toFile());
        fc.setDialogTitle(title);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        return fc;
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
    }

    /** The selected schedule's place in rows (not the sorted view), or -1. */
    private int selectedRow() {
        int v = table.getSelectedRow();
        return v < 0 ? -1 : table.convertRowIndexToModel(v);
    }

    /** "Repeats", sorted from most to least often (by roughly how many days apart). */
    private record RepeatsValue(String label, double daysApart) implements Comparable<RepeatsValue> {
        static RepeatsValue of(Recurrence r) {
            double unit = switch (r.unit()) {
                case DAY -> 1;
                case WEEK -> 7;
                case SEMIMONTH -> 365.25 / 24;
                case MONTH -> 365.25 / 12;
                case YEAR -> 365.25;
            };
            return new RepeatsValue(r.describe(), unit * r.interval());
        }

        @Override
        public int compareTo(RepeatsValue o) {
            int c = Double.compare(daysApart, o.daysApart);
            return c != 0 ? c : label.compareToIgnoreCase(o.label);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** "Ends": end dates (soonest first), then after N times (fewest first), then never. */
    private record EndsValue(int kind, LocalDate date, int times) implements Comparable<EndsValue> {
        static EndsValue of(Recurrence r) {
            return r.endDate() != null ? new EndsValue(0, r.endDate(), 0)
                    : r.maxCount() != null ? new EndsValue(1, null, r.maxCount()) : new EndsValue(2, null, 0);
        }

        @Override
        public int compareTo(EndsValue o) {
            if (kind != o.kind) {
                return Integer.compare(kind, o.kind);
            }
            return kind == 0 ? date.compareTo(o.date) : Integer.compare(times, o.times);
        }

        @Override
        public String toString() {
            return kind == 0 ? date.format(LedgerTableModel.DISPLAY_DATE)
                    : kind == 1 ? "after " + times + " times" : "never";
        }
    }

    /** The schedule's next date from today, or null if it has ended. */
    private static LocalDate next(ScheduleRepository.Schedule s) {
        return s.recurrence().nextOnOrAfter(LocalDate.now());
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Account", "Payee", "Amount", "Repeats", "Next", "Ends"};

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
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 2 -> Long.class;
                case 3 -> RepeatsValue.class;
                case 4 -> LocalDate.class;
                case 5 -> EndsValue.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            ScheduleRepository.Schedule s = rows.get(row);
            Recurrence r = s.recurrence();
            return switch (column) {
                case 0 -> accountNames.getOrDefault(s.accountId(), "?");
                case 1 -> s.payee() == null ? "" : s.payee();
                case 2 -> s.amountCents();
                case 3 -> RepeatsValue.of(r);
                case 4 -> {
                    LocalDate n = next(s);
                    yield n == null ? LocalDate.MAX : n;           // ended ones sort last
                }
                case 5 -> EndsValue.of(r);
                default -> "";
            };
        }
    }
}
