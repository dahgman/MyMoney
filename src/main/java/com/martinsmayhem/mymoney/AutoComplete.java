package com.martinsmayhem.mymoney;

import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Type-ahead and a suggestion drop-down for a text field: every payee and
 * category (and tag, and type) field. Typing at the end of the text fills in
 * the rest of the first entry that starts with it, selected ("wal" becomes
 * "Walmart" with "mart" selected): typing on replaces it, Backspace or
 * Delete takes it away, and Enter, Tab or a click elsewhere keeps it.
 * Meanwhile the list shrinks to the entries that start with what was
 * typed, then those that contain it.
 * Up/Down or a click highlight an entry (Down with the list closed shows
 * everything); Space puts the highlighted entry in the field, and so does
 * Enter (the field's owner decides what else Enter and Escape do, using
 * pickHighlighted() and hidePopup()). A double-click picks an entry at once.
 */
final class AutoComplete {

    private static final int MAX_VISIBLE_ROWS = 8;
    private static final int MIN_POPUP_WIDTH = 220;

    private final JTextField field;
    private final Supplier<List<String>> source;
    private final DefaultListModel<String> matches = new DefaultListModel<>();
    private final JList<String> list = new JList<>(matches);
    private List<String> suggestions = List.of();
    private JWindow popup;
    private boolean adjusting;                         // true while the text is set here, not typed
    private Runnable onClickPick = () -> { };

    AutoComplete(JTextField field, Supplier<List<String>> source) {
        this.field = field;
        this.source = source;
        bind(KeyEvent.VK_DOWN, "autocomplete-down", () -> move(1));
        bind(KeyEvent.VK_UP, "autocomplete-up", () -> move(-1));
        // Space: with an entry highlighted, take it; otherwise just type a space.
        field.getInputMap().put(KeyStroke.getKeyStroke(' '), "autocomplete-space");
        field.getActionMap().put("autocomplete-space", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (popupShowing() && list.getSelectedValue() != null) {
                    pickHighlighted();
                } else {
                    field.replaceSelection(" ");
                }
            }
        });

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                // Typed at the end (not pasted into the middle): fill in the rest.
                textChanged(e.getOffset() + e.getLength() == e.getDocument().getLength());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                textChanged(false);                       // deleting: never put it straight back
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });

        // The suggestions are read afresh whenever the field is entered (new payees, categories...).
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                reload();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                hidePopup();
            }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i < 0) {
                    return;
                }
                if (e.getClickCount() >= 2) {             // double-click: take it now
                    setTextQuietly(matches.get(i));
                    hidePopup();
                    onClickPick.run();
                } else {                                  // a click highlights; Enter or Space takes it
                    list.setSelectedIndex(i);
                    field.requestFocusInWindow();
                }
            }
        });
    }

    /** What to do after a double-click picks an entry (the ledger saves the cell). */
    void setOnClickPick(Runnable r) {
        onClickPick = r;
    }

    /** Re-reads the suggestions (each time editing starts). */
    void reload() {
        suggestions = source.get();
    }

    /** Sets the text without opening the drop-down. */
    void setTextQuietly(String text) {
        quietly(() -> field.setText(text));
    }

    /** Runs something that changes the text (like a cell editor starting) without opening the drop-down. */
    void quietly(Runnable r) {
        adjusting = true;
        try {
            r.run();
        } finally {
            adjusting = false;
        }
    }

    /** If an entry is highlighted, puts it in the field (true), and closes the list. */
    boolean pickHighlighted() {
        boolean picked = popupShowing() && list.getSelectedValue() != null;
        if (picked) {
            setTextQuietly(list.getSelectedValue());
        }
        hidePopup();
        return picked;
    }

    boolean popupShowing() {
        return popup != null && popup.isVisible();
    }

    void hidePopup() {
        if (popup != null) {
            popup.setVisible(false);
        }
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

    private void textChanged(boolean typedAtEnd) {
        if (!adjusting) {
            SwingUtilities.invokeLater(() -> {
                String typed = field.getText();
                if (typedAtEnd) {
                    completeInline(typed);
                }
                showMatches(typed, false);
            });
        }
    }

    /**
     * Fills in the rest of the first entry starting with typed (ignoring
     * case), selected so that typing on replaces it. The whole entry is put
     * in, so it reads as saved ("Walmart", not "walmart").
     */
    private void completeInline(String typed) {
        if (typed.isEmpty() || !field.isFocusOwner() || field.getCaretPosition() != typed.length()
                || field.getSelectionStart() != field.getSelectionEnd()) {
            return;
        }
        String lower = typed.toLowerCase();
        for (String s : suggestions) {
            if (s.length() > typed.length() && s.toLowerCase().startsWith(lower)) {
                quietly(() -> {
                    field.setText(s);
                    field.setCaretPosition(s.length());
                    field.moveCaretPosition(typed.length());       // the filled-in part, selected
                });
                return;
            }
        }
    }

    /**
     * Type-ahead and suggestions for an editable drop-down's text (as in the
     * account form's categories). Returns null if it doesn't edit as text.
     */
    static AutoComplete attach(javax.swing.JComboBox<?> combo, Supplier<List<String>> source) {
        if (combo.isEditable() && combo.getEditor().getEditorComponent() instanceof JTextField f) {
            AutoComplete ac = new AutoComplete(f, source);
            ac.reload();
            return ac;
        }
        return null;
    }

    /** Fills the drop-down with entries that start with, then contain, the text. */
    private void showMatches(String text, boolean showAllIfBlank) {
        if (!field.isShowing() || !field.isFocusOwner()) {   // only while typing in this field
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
            popup.setFocusableWindowState(false);     // keep the keyboard in the field
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
}
