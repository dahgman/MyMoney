package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * A text cell editor for the ledger.
 *
 * Typing into a cell replaces its old value. Enter runs the onEnter action
 * (the ledger uses it to save and move down).
 * When given a list of suggestions, a drop-down of matching entries appears
 * under the cell while typing: Up/Down highlight an entry, Enter or a click
 * picks it, and Escape closes the list.
 */
public class AutoCompleteEditor extends DefaultCellEditor {

    private static final int MAX_VISIBLE_ROWS = 8;
    private static final int MIN_POPUP_WIDTH = 220;

    private final JTextField field;
    private final Supplier<List<String>> source;      // null = plain text editor
    private final DefaultListModel<String> matches = new DefaultListModel<>();
    private final JList<String> list = new JList<>(matches);
    private List<String> suggestions = List.of();
    private JWindow popup;
    private boolean adjusting;                         // true while we set the text ourselves

    public AutoCompleteEditor(Supplier<List<String>> source, Runnable onEnter) {
        super(new JTextField());
        this.field = (JTextField) getComponent();
        this.source = source;
        field.setBorder(new LineBorder(Color.BLACK));

        bind(KeyEvent.VK_ENTER, "enter", () -> {
            pickHighlighted();
            onEnter.run();
        });

        if (source == null) {
            return;
        }
        bind(KeyEvent.VK_DOWN, "down", () -> move(1));
        bind(KeyEvent.VK_UP, "up", () -> move(-1));
        bind(KeyEvent.VK_ESCAPE, "escape", () -> {
            if (popupShowing()) {
                hidePopup();
            } else {
                cancelCellEditing();
            }
        });

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                textChanged();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                textChanged();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    setText(matches.get(i));
                    stopCellEditing();                // a click picks and saves
                }
            }
        });
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value,
            boolean isSelected, int row, int column) {
        if (source != null) {
            suggestions = source.get();              // fresh list each time editing starts
        }
        hidePopup();
        adjusting = true;
        try {
            Component c = super.getTableCellEditorComponent(table, value, isSelected, row, column);
            field.selectAll();      // typing replaces the old value instead of adding to it
            return c;
        } finally {
            adjusting = false;
        }
    }

    @Override
    public boolean stopCellEditing() {
        hidePopup();
        return super.stopCellEditing();
    }

    @Override
    public void cancelCellEditing() {
        hidePopup();
        super.cancelCellEditing();
    }

    // ---------------------------------------------------------------------

    private void bind(int keyCode, String name, Runnable action) {
        field.getInputMap().put(KeyStroke.getKeyStroke(keyCode, 0), name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void textChanged() {
        if (!adjusting) {
            SwingUtilities.invokeLater(() -> showMatches(field.getText(), false));
        }
    }

    /** Fills the drop-down with entries that start with, then contain, the text. */
    private void showMatches(String text, boolean showAllIfBlank) {
        if (!field.isShowing()) {
            hidePopup();
            return;
        }
        String typed = text.trim().toLowerCase();
        matches.clear();
        if (typed.isEmpty()) {
            if (showAllIfBlank) {
                suggestions.forEach(matches::addElement);
            }
        } else {
            for (String s : suggestions) {
                if (s.toLowerCase().startsWith(typed)) {
                    matches.addElement(s);
                }
            }
            for (String s : suggestions) {
                String lower = s.toLowerCase();
                if (!lower.startsWith(typed) && lower.contains(typed)) {
                    matches.addElement(s);
                }
            }
        }
        boolean onlyExactMatch = matches.size() == 1 && matches.get(0).equalsIgnoreCase(text.trim());
        if (matches.isEmpty() || onlyExactMatch) {
            hidePopup();
            return;
        }
        showPopup();
    }

    private void showPopup() {
        if (popup == null) {
            Window owner = SwingUtilities.getWindowAncestor(field);
            popup = new JWindow(owner);
            popup.setFocusableWindowState(false);     // keep the keyboard in the cell
            popup.add(new JScrollPane(list));
        }
        list.clearSelection();
        list.setVisibleRowCount(Math.min(MAX_VISIBLE_ROWS, matches.size()));
        popup.pack();
        popup.setSize(Math.max(Math.max(field.getWidth(), MIN_POPUP_WIDTH), popup.getWidth()),
                popup.getHeight());
        Point p = field.getLocationOnScreen();
        popup.setLocation(p.x, p.y + field.getHeight());
        popup.setVisible(true);
    }

    private void hidePopup() {
        if (popup != null) {
            popup.setVisible(false);
        }
    }

    private boolean popupShowing() {
        return popup != null && popup.isVisible();
    }

    /** Up/Down: move the highlight; Down with no list open shows everything. */
    private void move(int step) {
        if (!popupShowing()) {
            if (step > 0) {
                showMatches(field.getText(), true);
            }
            return;
        }
        int i = Math.max(-1, Math.min(matches.size() - 1, list.getSelectedIndex() + step));
        if (i < 0) {
            list.clearSelection();
        } else {
            list.setSelectedIndex(i);
            list.ensureIndexIsVisible(i);
        }
    }

    /** If an entry is highlighted, puts it in the cell. */
    private void pickHighlighted() {
        if (popupShowing() && list.getSelectedValue() != null) {
            setText(list.getSelectedValue());
        }
        hidePopup();
    }

    private void setText(String text) {
        adjusting = true;
        try {
            field.setText(text);
        } finally {
            adjusting = false;
        }
    }
}
