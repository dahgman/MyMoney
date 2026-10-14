package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.DefaultCellEditor;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.border.LineBorder;

/**
 * A text cell editor for the ledger.
 *
 * Typing into a cell replaces its old value. Enter runs the onEnter action
 * (the ledger uses it to save and move down).
 * When given a list of suggestions, a drop-down of matching entries appears
 * under the cell while typing (see AutoComplete): Up/Down or a click highlight
 * an entry, Enter or Space takes it (a double-click too), and Escape closes
 * the list.
 */
public class AutoCompleteEditor extends DefaultCellEditor {

    private final JTextField field;
    private final AutoComplete complete;               // null = plain text editor

    public AutoCompleteEditor(Supplier<List<String>> source, Runnable onEnter) {
        super(new JTextField());
        this.field = (JTextField) getComponent();
        field.setBorder(new LineBorder(Color.BLACK));
        editingColors(field);
        complete = source == null ? null : new AutoComplete(field, source);

        bind(KeyEvent.VK_ENTER, "enter", () -> {
            if (complete != null) {
                complete.pickHighlighted();
            }
            onEnter.run();
        });
        if (complete == null) {
            return;
        }
        bind(KeyEvent.VK_ESCAPE, "escape", () -> {
            if (complete.popupShowing()) {
                complete.hidePopup();
            } else {
                cancelCellEditing();
            }
        });
        complete.setOnClickPick(this::stopCellEditing);   // a double-click picks and saves
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value,
            boolean isSelected, int row, int column) {
        if (complete == null) {
            Component c = super.getTableCellEditorComponent(table, value, isSelected, row, column);
            field.selectAll();
            return c;
        }
        complete.reload();                              // fresh list each time editing starts
        complete.hidePopup();
        Component[] c = new Component[1];
        complete.quietly(() -> c[0] = AutoCompleteEditor.super.getTableCellEditorComponent(
                table, value, isSelected, row, column));
        field.selectAll();      // typing replaces the old value instead of adding to it
        return c[0];
    }

    @Override
    public boolean stopCellEditing() {
        if (complete != null) {
            complete.hidePopup();
        }
        return super.stopCellEditing();
    }

    @Override
    public void cancelCellEditing() {
        if (complete != null) {
            complete.hidePopup();
        }
        super.cancelCellEditing();
    }

    /**
     * A cell being edited is white with dark text, like the rest of its row
     * (see MainFrame.muted). Some look-and-feels (GTK) paint text fields
     * themselves and ignore the background color, so the field gets Swing's
     * plain text-field painter, which uses it.
     */
    static void editingColors(JTextField f) {
        f.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
        f.setOpaque(true);
        f.setBackground(Color.WHITE);
        f.setForeground(Color.BLACK);
        f.setCaretColor(Color.BLACK);
        f.setSelectionColor(new Color(0xB3D7FF));
        f.setSelectedTextColor(Color.BLACK);
    }

    private void bind(int keyCode, String name, Runnable action) {
        field.getInputMap().put(KeyStroke.getKeyStroke(keyCode, 0), name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
