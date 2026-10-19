package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/**
 * Edit > Edit Transactions...: one window for changing several transactions
 * at once. Each field has a tick box; only ticked fields change (typing in a
 * field ticks it). A field the selected transactions all share starts with
 * that value. A ticked field left blank is cleared.
 */
final class BulkEditDialog extends JDialog {

    /** One field: its ledger column, tick box and editor. */
    private record Field(int column, JCheckBox box, JComponent editor) {
    }

    private final List<Field> fields = new java.util.ArrayList<>();
    private Map<Integer, Object> result;

    private BulkEditDialog(Window owner, List<LedgerEntry> selected, List<String> payees, List<String> categories,
                           List<String> tags, List<String> types, boolean anyReconciled) {
        super(owner, "Edit " + selected.size() + (selected.size() == 1 ? " Transaction" : " Transactions"),
                ModalityType.APPLICATION_MODAL);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        int row = 0;

        LocalDate sharedDate = shared(selected, e -> e.date);
        DatePicker date = new DatePicker(sharedDate);
        row = add(form, row, LedgerTableModel.DATE, "Date", date, selected, e -> e.date);
        row = add(form, row, LedgerTableModel.NUM, "Num", text(shared(selected, e -> e.checkNumber), 10), selected,
                e -> e.checkNumber);
        row = add(form, row, LedgerTableModel.TYPE, "Type", combo(types, shared(selected, e -> e.type)), selected,
                e -> e.type);
        row = add(form, row, LedgerTableModel.PAYEE, "Payee", combo(payees, shared(selected, e -> e.payee)), selected,
                e -> e.payee);
        row = add(form, row, LedgerTableModel.CATEGORY, "Category",
                combo(categories, shared(selected, e -> e.category)), selected, e -> e.category);
        row = add(form, row, LedgerTableModel.TAG, "Tag", combo(tags, shared(selected, e -> e.tag)), selected,
                e -> e.tag);
        row = add(form, row, LedgerTableModel.DESCRIPTION, "Description",
                text(shared(selected, e -> e.description), 28), selected, e -> e.description);
        row = add(form, row, LedgerTableModel.MEMO, "Memo", text(shared(selected, e -> e.memo), 28), selected,
                e -> e.memo);
        JComboBox<TxnStatus> status = new JComboBox<>(TxnStatus.userChoices());
        TxnStatus sharedStatus = shared(selected, e -> e.status);
        status.setSelectedItem(sharedStatus != null ? sharedStatus : TxnStatus.CLEARED);
        row = add(form, row, LedgerTableModel.STATUS, "Status", status, selected, e -> e.status);

        JLabel help = new JLabel("<html><div style='width:420px'>Tick each field to change; only those change,"
                + " on all " + selected.size() + " (typing in a field ticks it). A ticked field left blank is cleared."
                + " Use Parent:Child for a child category, [Account] for a transfer."
                + (anyReconciled ? " Reconciled transactions change only their status, tag, payee and (unless a"
                        + " transfer) category." : "") + "</div></html>");
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.insets = new Insets(10, 0, 0, 0);
        form.add(help, c);

        JButton ok = new JButton("Change");
        ok.addActionListener(e -> accept());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Asks what to change on the selected transactions.
     * @return each ticked field's column and value (a LocalDate for Date, a
     *         TxnStatus for Status, text otherwise), or null if cancelled
     */
    static Map<Integer, Object> ask(Window owner, List<LedgerEntry> selected, List<String> payees,
                                    List<String> categories, List<String> tags, List<String> types) {
        boolean anyReconciled = selected.stream().anyMatch(e -> e.status == TxnStatus.RECONCILED);
        BulkEditDialog d = new BulkEditDialog(owner, selected, payees, categories, tags, types, anyReconciled);
        d.setVisible(true);
        return d.result;
    }

    private int add(JPanel form, int row, int column, String label, JComponent editor, List<LedgerEntry> selected,
                    Function<LedgerEntry, Object> value) {
        JCheckBox box = new JCheckBox(label + ":");
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 0, 2, 8);
        form.add(box, c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        form.add(editor, c);
        // How the selected transactions differ in it, if they do.
        long distinct = selected.stream().map(value).map(v -> v == null || "".equals(v) ? "" : v).distinct().count();
        JLabel note = new JLabel(distinct > 1 ? "  (varies)" : "");
        note.setEnabled(false);
        c = new GridBagConstraints();
        c.gridx = 2;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        form.add(note, c);
        tickWhenEdited(box, editor);
        fields.add(new Field(column, box, editor));
        return row + 1;
    }

    /** Ticks box as soon as the field is typed in or picked from. */
    private static void tickWhenEdited(JCheckBox box, JComponent editor) {
        JTextComponent text = editor instanceof JTextComponent t ? t
                : editor instanceof JComboBox<?> cb && cb.isEditable() ? (JTextComponent) cb.getEditor().getEditorComponent()
                : editor instanceof DatePicker dp ? findText(dp) : null;
        if (text != null) {
            text.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    tick();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    tick();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }

                private void tick() {
                    if (text.isFocusOwner()) {
                        box.setSelected(true);
                    }
                }
            });
        }
        if (editor instanceof JComboBox<?> cb) {
            cb.addActionListener(e -> {
                if (cb.isPopupVisible() || cb.hasFocus() || !cb.isEditable()) {
                    box.setSelected(true);
                }
            });
        }
    }

    private static JTextComponent findText(java.awt.Container c) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof JTextComponent t) {
                return t;
            }
        }
        return null;
    }

    private void accept() {
        Map<Integer, Object> values = new LinkedHashMap<>();
        for (Field f : fields) {
            if (!f.box().isSelected()) {
                continue;
            }
            Object v;
            if (f.editor() instanceof DatePicker dp) {
                try {
                    v = dp.getDate();
                } catch (IllegalArgumentException e) {
                    JOptionPane.showMessageDialog(this, e.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } else if (f.column() == LedgerTableModel.STATUS) {
                v = ((JComboBox<?>) f.editor()).getSelectedItem();
            } else {
                v = ((JTextField) f.editor()).getText().trim();
            }
            values.put(f.column(), v);
        }
        if (values.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Tick the fields to change.", getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        result = values;
        dispose();
    }

    /** The value every one of them has, or null if they differ. */
    private static <T> T shared(List<LedgerEntry> list, Function<LedgerEntry, T> value) {
        T first = list.isEmpty() ? null : value.apply(list.get(0));
        for (LedgerEntry e : list) {
            if (!Objects.equals(value.apply(e), first)) {
                return null;
            }
        }
        return first;
    }

    private static JTextField text(String value, int columns) {
        JTextField f = new JTextField(value == null ? "" : value, columns);
        return f;
    }

    /** A text field that suggests from choices as you type, like the ledger's. */
    private static JTextField combo(List<String> choices, String value) {
        JTextField f = text(value, 24);
        new AutoComplete(f, () -> choices);
        return f;
    }
}
