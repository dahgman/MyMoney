package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;

/**
 * Manage Tags: add, rename, recolor and delete tags.
 * Double-click a tag to change its color.
 */
public class TagDialog extends JDialog {

    private final TagRepository tags;
    private final DefaultListModel<TagRepository.Tag> model = new DefaultListModel<>();
    private final JList<TagRepository.Tag> list = new JList<>(model);
    private boolean changed;

    public TagDialog(Frame owner, TagRepository tags) {
        super(owner, "Manage Tags", true);
        this.tags = tags;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                TagRepository.Tag t = (TagRepository.Tag) value;
                setIcon(new Swatch(t.color()));
                setText("<html>" + escape(t.name()) + "&nbsp;&nbsp;<font color='gray'>("
                        + t.usage() + (t.usage() == 1 ? " transaction" : " transactions") + ")</font></html>");
                setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    changeColor();
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(320, 300));

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(button("New...", this::addTag));
        buttons.add(button("Rename...", this::renameTag));
        buttons.add(button("Color...", this::changeColor));
        buttons.add(button("Delete", this::deleteTag));
        JPanel east = new JPanel(new BorderLayout());
        east.add(buttons, BorderLayout.NORTH);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton close = button("Close", this::dispose);
        south.add(close);
        getRootPane().setDefaultButton(close);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(scroll, BorderLayout.CENTER);
        content.add(east, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);

        reload(null);
        pack();
    }

    /** True if anything was added, renamed, recolored or deleted. */
    public boolean wasChanged() {
        return changed;
    }

    // ---------------------------------------------------------------------

    private void addTag() {
        String name = askName("New tag name:", "");
        if (name == null) {
            return;
        }
        try {
            if (tags.nameExists(name, 0)) {
                showError("A tag named \"" + name + "\" already exists.");
                return;
            }
            long id = tags.create(name, tags.unusedColor());
            changed = true;
            reload(id);
        } catch (SQLException e) {
            showError("Could not add the tag:\n" + e.getMessage());
        }
    }

    private void renameTag() {
        TagRepository.Tag t = selected();
        if (t == null) {
            return;
        }
        String name = askName("New name for \"" + t.name() + "\":", t.name());
        if (name == null || name.equals(t.name())) {
            return;
        }
        try {
            if (tags.nameExists(name, t.id())) {
                showError("A tag named \"" + name + "\" already exists.");
                return;
            }
            tags.rename(t.id(), name);
            changed = true;
            reload(t.id());
        } catch (SQLException e) {
            showError("Could not rename the tag:\n" + e.getMessage());
        }
    }

    private void changeColor() {
        TagRepository.Tag t = selected();
        if (t == null) {
            return;
        }
        Color c = JColorChooser.showDialog(this, "Color for \"" + t.name() + "\"", t.color());
        if (c == null) {
            return;
        }
        try {
            tags.setColor(t.id(), c);
            changed = true;
            reload(t.id());
        } catch (SQLException e) {
            showError("Could not change the color:\n" + e.getMessage());
        }
    }

    private void deleteTag() {
        TagRepository.Tag t = selected();
        if (t == null) {
            return;
        }
        String message = "Delete the tag \"" + t.name() + "\"?"
                + (t.usage() > 0 ? "\nIt will be removed from " + t.usage()
                        + (t.usage() == 1 ? " transaction." : " transactions.") : "");
        if (JOptionPane.showConfirmDialog(this, message, "Delete Tag", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            tags.delete(t.id());
            changed = true;
            reload(null);
        } catch (SQLException e) {
            showError("Could not delete the tag:\n" + e.getMessage());
        }
    }

    /** Asks for a tag name; null if cancelled. Repeats until the name is valid. */
    private String askName(String prompt, String initial) {
        String value = initial;
        while (true) {
            value = (String) JOptionPane.showInputDialog(this, prompt, "Tag",
                    JOptionPane.PLAIN_MESSAGE, null, null, value);
            if (value == null) {
                return null;
            }
            try {
                return TagRepository.normalize(value);
            } catch (IllegalArgumentException e) {
                showError(e.getMessage());
            }
        }
    }

    private TagRepository.Tag selected() {
        TagRepository.Tag t = list.getSelectedValue();
        if (t == null) {
            showError("Select a tag first.");
        }
        return t;
    }

    private void reload(Long selectId) {
        model.clear();
        try {
            for (TagRepository.Tag t : tags.findAll()) {
                model.addElement(t);
                if (selectId != null && t.id() == selectId) {
                    list.setSelectedIndex(model.size() - 1);
                }
            }
        } catch (SQLException e) {
            showError("Could not load tags:\n" + e.getMessage());
        }
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Manage Tags", JOptionPane.ERROR_MESSAGE);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A small rounded color square. */
    private record Swatch(Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x, y, 14, 14, 5, 5);
            g2.setColor(color.darker());
            g2.drawRoundRect(x, y, 13, 13, 5, 5);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }
}
