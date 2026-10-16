package com.martinsmayhem.mymoney;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * What to do with a bank download file once it's imported: leave it where it
 * is, delete it, or move it into MyMoney's folder
 * (<data folder>/<Account>/imports/<date>.<ext>). Radio buttons, used in the
 * import dialog and in Preferences > Imports. Choices are "leave", "delete"
 * and "move".
 */
final class ImportFileChoice extends JPanel {

    static final String LEAVE = "leave";
    static final String DELETE = "delete";
    static final String MOVE = "move";

    private final JRadioButton leave = new JRadioButton("Left where it is");
    private final JRadioButton delete = new JRadioButton("Deleted");
    private final JRadioButton move = new JRadioButton("Moved into MyMoney's folder (<Account>/imports)");

    ImportFileChoice(String choice) {
        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
        setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        ButtonGroup g = new ButtonGroup();
        for (JRadioButton b : new JRadioButton[] {leave, delete, move}) {
            g.add(b);
            add(b);
        }
        delete.setToolTipText("Bank downloads hold your account number in plain text; deleting it leaves no copy behind");
        move.setToolTipText("Kept beside your data, named for the day it was imported");
        set(choice);
    }

    /** "leave", "delete" or "move". */
    String choice() {
        return delete.isSelected() ? DELETE : move.isSelected() ? MOVE : LEAVE;
    }

    void set(String choice) {
        (DELETE.equals(choice) ? delete : MOVE.equals(choice) ? move : leave).setSelected(true);
    }

    /** Without the Move choice (when moving was just declined). */
    void withoutMove() {
        move.setVisible(false);
        if (move.isSelected()) {
            leave.setSelected(true);
        }
    }
}
