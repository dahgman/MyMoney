package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.time.LocalDate;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;

/**
 * A date field for dialogs: type a date (any format the ledger accepts) or
 * pick one from the calendar button.
 */
final class DatePicker extends JPanel {

    private final JTextField field = new JTextField(10);
    private final JButton button = new JButton("▾");     // ▾

    DatePicker(LocalDate date) {
        super(new BorderLayout(2, 0));
        setDate(date);
        button.setMargin(new java.awt.Insets(0, 4, 0, 4));
        button.setFocusable(false);
        button.setToolTipText("Pick from a calendar");
        button.addActionListener(e -> showCalendar());
        add(field, BorderLayout.CENTER);
        add(button, BorderLayout.EAST);
    }

    /** The date typed or picked. @throws IllegalArgumentException with a message fit to show */
    LocalDate getDate() {
        return LedgerTableModel.parseDate(field.getText().trim());
    }

    void setDate(LocalDate date) {
        field.setText(date == null ? "" : date.format(LedgerTableModel.DISPLAY_DATE));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        field.setEnabled(enabled);
        button.setEnabled(enabled);
    }

    private void showCalendar() {
        JPopupMenu popup = new JPopupMenu();
        CalendarPanel calendar = new CalendarPanel(d -> {
            setDate(d);
            popup.setVisible(false);
        });
        LocalDate current;
        try {
            current = getDate();
        } catch (IllegalArgumentException e) {
            current = LocalDate.now();
        }
        calendar.setSelected(current);
        popup.add(calendar);
        popup.show(this, 0, getHeight());
    }
}
