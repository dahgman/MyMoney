package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Shows what a schedules CSV file holds before importing it: every line, and
 * for each whether it's ready, already scheduled (the same schedule exists,
 * so it's skipped), or what's wrong with it. Import adds the ready ones.
 */
final class ScheduleImportDialog extends JDialog {

    private static final Color PROBLEM = new Color(0xE0, 0x3E, 0x2D);

    private final ScheduleRepository schedules;
    private final List<ScheduleCsv.Row> rows;
    private final List<String> statuses = new ArrayList<>();
    private final List<ScheduleRepository.Schedule> ready = new ArrayList<>();
    private int imported;

    ScheduleImportDialog(Window owner, String fileName, List<ScheduleCsv.Row> rows,
                         ScheduleRepository schedules) throws SQLException {
        super(owner, "Import Schedules: " + fileName, ModalityType.APPLICATION_MODAL);
        this.schedules = schedules;
        this.rows = rows;

        List<ScheduleRepository.Schedule> existing = new ArrayList<>();
        for (ScheduleRepository.Schedule s : schedules.findAll()) {
            existing.add(withoutId(s));
        }
        int problems = 0;
        int duplicates = 0;
        for (ScheduleCsv.Row r : rows) {
            if (r.schedule() == null) {
                statuses.add(r.problem());
                problems++;
            } else if (existing.contains(r.schedule()) || ready.contains(r.schedule())) {
                statuses.add("Already scheduled: skipped");
                duplicates++;
            } else {
                statuses.add("Ready");
                ready.add(r.schedule());
            }
        }

        JTable table = new JTable(new Model());
        table.setFillsViewportHeight(true);
        int[] widths = {45, 120, 150, 85, 110, 90, 330};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(6).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                setToolTipText(value == null ? null : value.toString());
                if (!isSelected) {
                    setForeground(rows.get(row).schedule() == null ? PROBLEM : t.getForeground());
                }
                return this;
            }
        });
        ColumnFit.install(table);

        JLabel summary = new JLabel(ready.size() + (ready.size() == 1 ? " schedule" : " schedules") + " ready to import"
                + (duplicates > 0 ? ", " + duplicates + " already scheduled" : "")
                + (problems > 0 ? ", " + problems + (problems == 1 ? " line has a problem" : " lines have problems")
                        + " (shown in red; fix the file and import again)" : "") + ".");
        summary.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));

        JButton importButton = new JButton("Import " + ready.size() + (ready.size() == 1 ? " Schedule" : " Schedules"));
        importButton.setEnabled(!ready.isEmpty());
        importButton.addActionListener(e -> importReady());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(importButton);
        buttons.add(cancel);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8),
                scroll.getBorder()));
        getContentPane().add(summary, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(importButton.isEnabled() ? importButton : cancel);
        setSize(980, 420);
        setLocationRelativeTo(owner);
    }

    /** How many schedules were imported. */
    int importedCount() {
        return imported;
    }

    private void importReady() {
        try {
            for (ScheduleRepository.Schedule s : ready) {
                schedules.save(s);
                imported++;
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not import every schedule (" + imported + " of " + ready.size()
                    + " were):\n" + e.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
        }
        dispose();
    }

    /** The same schedule with id 0, for comparing with what a file holds. */
    private static ScheduleRepository.Schedule withoutId(ScheduleRepository.Schedule s) {
        Recurrence r = s.recurrence();
        Recurrence norm = new Recurrence(r.unit(), r.interval(),
                r.unit() == Recurrence.Unit.SEMIMONTH ? r.secondDay() : 0, r.start(), r.endDate(), r.maxCount());
        return new ScheduleRepository.Schedule(0, s.accountId(), s.payee(), s.category(), s.tag(), s.description(),
                s.memo(), s.amountCents(), norm);
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Line", "Account", "Payee", "Amount", "Repeats", "First Date", "Status"};

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
            ScheduleCsv.Row r = rows.get(row);
            return switch (column) {
                case 0 -> String.valueOf(r.line());
                case 1 -> r.account();
                case 2 -> r.payee();
                case 3 -> r.amount();
                case 4 -> r.repeats();
                case 5 -> r.firstDate();
                case 6 -> statuses.get(row);
                default -> "";
            };
        }
    }
}
