package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.LocalDate;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;

/**
 * The ledger's Date editor: type a date freely, or open a calendar with the
 * button at the right of the cell, F4, or Alt+Down.
 *
 * While the calendar is open: arrow keys move a day/week, Page Up/Down a
 * month, Enter picks the highlighted day, Escape closes it. Typing a date
 * moves the calendar to match. Clicking a day picks it and saves.
 */
public class DateCellEditor extends DefaultCellEditor {

    private static final int BUTTON_WIDTH = 20;

    private final JTextField field;
    private final CalendarPanel calendar;
    private JWindow popup;
    private boolean adjusting;                      // true while we set the text ourselves

    public DateCellEditor(Runnable onEnter) {
        super(new DateField());
        DateField dateField = (DateField) getComponent();
        this.field = dateField;
        this.calendar = new CalendarPanel(date -> {
            setText(date);
            stopCellEditing();                      // a click picks and saves
        });
        dateField.button.addActionListener(e -> toggleCalendar());

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter", true, () -> {
            if (calendarShowing()) {
                setText(calendar.getSelected());
            }
            onEnter.run();
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), "toggle", true, this::toggleCalendar);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "open", true,
                this::toggleCalendar);

        // These only act while the calendar is open; otherwise the key works as usual.
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "weekBack", false, () -> calendar.moveDays(-7));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "weekAhead", false, () -> calendar.moveDays(7));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "monthBack", false, () -> calendar.moveMonths(-1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "monthAhead", false, () -> calendar.moveMonths(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", false, this::hideCalendar);
        bindWithFallback(KeyEvent.VK_LEFT, "dayBack", -1, DefaultEditorKit.backwardAction);
        bindWithFallback(KeyEvent.VK_RIGHT, "dayAhead", 1, DefaultEditorKit.forwardAction);

        // Typing a valid date moves the open calendar to it.
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                syncCalendar();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                syncCalendar();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value,
            boolean isSelected, int row, int column) {
        hideCalendar();
        adjusting = true;
        try {
            Component c = super.getTableCellEditorComponent(table, value, isSelected, row, column);
            field.selectAll();          // typing replaces the old date
            return c;
        } finally {
            adjusting = false;
        }
    }

    @Override
    public boolean stopCellEditing() {
        hideCalendar();
        return super.stopCellEditing();
    }

    @Override
    public void cancelCellEditing() {
        hideCalendar();
        super.cancelCellEditing();
    }

    // ---------------------------------------------------------------------

    /**
     * Binds a key on the text field. If alwaysActive is false, the key does
     * nothing here while the calendar is closed, and passes on to the table.
     */
    private void bind(KeyStroke key, String name, boolean alwaysActive, Runnable action) {
        field.getInputMap().put(key, name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }

            @Override
            public boolean isEnabled() {
                return alwaysActive || calendarShowing();
            }
        });
    }

    /** Left/Right: move a day in the open calendar, otherwise move the text cursor. */
    private void bindWithFallback(int keyCode, String name, int days, String caretAction) {
        javax.swing.Action fallback = field.getActionMap().get(caretAction);
        field.getInputMap().put(KeyStroke.getKeyStroke(keyCode, 0), name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (calendarShowing()) {
                    calendar.moveDays(days);
                } else if (fallback != null) {
                    fallback.actionPerformed(e);
                }
            }
        });
    }

    private void toggleCalendar() {
        if (calendarShowing()) {
            hideCalendar();
        } else {
            showCalendar();
        }
    }

    private void showCalendar() {
        if (!field.isShowing()) {
            return;
        }
        LocalDate date;
        try {
            date = LedgerTableModel.parseDate(field.getText().trim());
        } catch (IllegalArgumentException e) {
            date = LocalDate.now();
        }
        calendar.setSelected(date);

        if (popup == null) {
            Window owner = SwingUtilities.getWindowAncestor(field);
            popup = new JWindow(owner);
            popup.setFocusableWindowState(false);     // keep the keyboard in the cell
            popup.add(calendar);
        }
        popup.pack();

        // Below the cell, or above it if there isn't room on the screen.
        Point p = field.getLocationOnScreen();
        GraphicsConfiguration gc = field.getGraphicsConfiguration();
        Rectangle screen = gc != null ? gc.getBounds() : new Rectangle(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        int y = p.y + field.getHeight();
        if (y + popup.getHeight() > screen.y + screen.height) {
            y = p.y - popup.getHeight();
        }
        popup.setLocation(p.x, y);
        popup.setVisible(true);
    }

    private void hideCalendar() {
        if (popup != null) {
            popup.setVisible(false);
        }
    }

    private boolean calendarShowing() {
        return popup != null && popup.isVisible();
    }

    private void syncCalendar() {
        if (adjusting || !calendarShowing()) {
            return;
        }
        try {
            calendar.setSelected(LedgerTableModel.parseDate(field.getText().trim()));
        } catch (IllegalArgumentException ignored) {
            // not a complete date yet
        }
    }

    private void setText(LocalDate date) {
        adjusting = true;
        try {
            field.setText(date.format(LedgerTableModel.DISPLAY_DATE));
        } finally {
            adjusting = false;
        }
    }

    // ---------------------------------------------------------------------

    /** A text field with a small calendar button inside its right edge. */
    private static final class DateField extends JTextField {

        final JButton button = new JButton(new CalendarIcon());

        DateField() {
            setLayout(null);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.BLACK),
                    BorderFactory.createEmptyBorder(0, 2, 0, BUTTON_WIDTH)));
            button.setFocusable(false);
            button.setMargin(new java.awt.Insets(0, 0, 0, 0));
            button.setCursor(Cursor.getDefaultCursor());
            button.setToolTipText("Choose a date (F4)");
            add(button);
        }

        @Override
        public void doLayout() {
            button.setBounds(getWidth() - 1 - BUTTON_WIDTH, 1, BUTTON_WIDTH, getHeight() - 2);
        }
    }

    /** A tiny calendar drawing for the button. */
    private static final class CalendarIcon implements Icon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(Color.DARK_GRAY);
            g2.drawRect(x, y + 2, 11, 9);         // page
            g2.fillRect(x, y + 2, 12, 3);         // header bar
            g2.drawLine(x + 3, y, x + 3, y + 2);  // rings
            g2.drawLine(x + 8, y, x + 8, y + 2);
            for (int r = 0; r < 2; r++) {         // days
                for (int col = 0; col < 3; col++) {
                    g2.fillRect(x + 2 + col * 3, y + 6 + r * 3, 2, 2);
                }
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 12;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }
}
