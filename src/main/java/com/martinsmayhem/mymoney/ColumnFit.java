package com.martinsmayhem.mymoney;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

/**
 * Double-clicking the border between two column headers sizes the column on
 * its left to fit its widest entry (or its header, if that is wider), as in
 * a spreadsheet.
 */
final class ColumnFit {

    /** How close (in pixels) to a border counts as on it. Matches Swing's resize zone. */
    private static final int ZONE = 3;
    private static final int PADDING = 8;

    private ColumnFit() {
        // static helper only
    }

    private static final String PRESSED_ON_BORDER = "ColumnFit.pressedOnBorder";

    static void install(JTable table) {
        JTableHeader header = table.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                header.putClientProperty(PRESSED_ON_BORDER, onBorder(header, e));
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int column = columnLeftOfBorder(header, e.getX());
                if (column >= 0) {
                    fit(table, column);
                }
            }
        });
    }

    /**
     * True if the mouse is on a border between headers (where Swing shows the
     * resize cursor), so a header click there shouldn't also sort.
     */
    static boolean onBorder(JTableHeader header, MouseEvent e) {
        return header.getCursor().getType() == Cursor.E_RESIZE_CURSOR
                || columnLeftOfBorder(header, e.getX()) >= 0;
    }

    /** True if the last mouse press on this header was on a border (for a RowSorter to ignore the click). */
    static boolean pressedOnBorder(JTableHeader header) {
        return Boolean.TRUE.equals(header.getClientProperty(PRESSED_ON_BORDER));
    }

    /** The view index of the column whose right edge is at x, or -1. */
    private static int columnLeftOfBorder(JTableHeader header, int x) {
        var cols = header.getColumnModel();
        int right = 0;
        for (int i = 0; i < cols.getColumnCount(); i++) {
            right += cols.getColumn(i).getWidth();
            if (Math.abs(x - right) <= ZONE) {
                return cols.getColumn(i).getResizable() ? i : -1;
            }
        }
        return -1;
    }

    /** Sizes one column (view index) to its widest cell or header. */
    static void fit(JTable table, int viewColumn) {
        TableColumn col = table.getColumnModel().getColumn(viewColumn);
        TableCellRenderer hr = col.getHeaderRenderer() != null ? col.getHeaderRenderer()
                : table.getTableHeader().getDefaultRenderer();
        Component h = hr.getTableCellRendererComponent(table, col.getHeaderValue(), false, false, -1, viewColumn);
        int width = h.getPreferredSize().width;
        for (int row = 0; row < table.getRowCount(); row++) {
            Component c = table.prepareRenderer(table.getCellRenderer(row, viewColumn), row, viewColumn);
            width = Math.max(width, c.getPreferredSize().width);
        }
        width += PADDING + table.getIntercellSpacing().width;
        width = Math.max(col.getMinWidth(), Math.min(col.getMaxWidth(), width));
        // Setting it as the header's "resizing column" makes the table give the
        // extra space to (or take it from) the columns on the right, like a drag.
        JTableHeader header = table.getTableHeader();
        header.setResizingColumn(col);
        col.setPreferredWidth(width);
        col.setWidth(width);
        table.doLayout();
        header.setResizingColumn(null);
        header.repaint();
    }
}
