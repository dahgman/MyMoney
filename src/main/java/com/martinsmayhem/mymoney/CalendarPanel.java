package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;

/**
 * A one-month calendar for picking a date. Clicking a day (or Today) calls
 * onPick. The arrows change the month shown without changing the selection.
 */
public class CalendarPanel extends JPanel {

    private static final DateTimeFormatter TITLE = DateTimeFormatter.ofPattern("MMMM yyyy");
    private static final Border TODAY_BORDER = BorderFactory.createLineBorder(new Color(52, 152, 219));
    private static final Border PLAIN_BORDER = BorderFactory.createEmptyBorder(1, 1, 1, 1);

    private final JLabel title = new JLabel("", SwingConstants.CENTER);
    private final JLabel[] dayLabels = new JLabel[42];          // 6 weeks x 7 days
    private final LocalDate[] dayDates = new LocalDate[42];
    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();

    public CalendarPanel(Consumer<LocalDate> onPick) {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));

        // Header: < Month Year >
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(arrowButton("\u25C0", -1), BorderLayout.WEST);
        header.add(title, BorderLayout.CENTER);
        header.add(arrowButton("\u25B6", 1), BorderLayout.EAST);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));

        // Day grid, weeks starting on Sunday.
        JPanel grid = new JPanel(new GridLayout(7, 7, 2, 2));
        grid.setOpaque(false);
        DayOfWeek dow = DayOfWeek.SUNDAY;
        for (int i = 0; i < 7; i++) {
            String name = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault());
            JLabel l = new JLabel(name.substring(0, Math.min(2, name.length())), SwingConstants.CENTER);
            l.setForeground(Color.GRAY);
            grid.add(l);
            dow = dow.plus(1);
        }
        for (int i = 0; i < dayLabels.length; i++) {
            final int index = i;
            JLabel l = new JLabel("", SwingConstants.CENTER);
            l.setOpaque(true);
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            l.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    onPick.accept(dayDates[index]);
                }
            });
            dayLabels[i] = l;
            grid.add(l);
        }

        // Footer: Today
        JButton today = new JButton("Today");
        today.setFocusable(false);
        today.addActionListener(e -> onPick.accept(LocalDate.now()));
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        footer.setOpaque(false);
        footer.add(today);

        add(header, BorderLayout.NORTH);
        add(grid, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        showMonth(month);
    }

    /** Selects a date and shows its month. */
    public void setSelected(LocalDate date) {
        selected = date;
        showMonth(YearMonth.from(date));
    }

    public LocalDate getSelected() {
        return selected;
    }

    /** Moves the selection by a number of days (keyboard arrows). */
    public void moveDays(int days) {
        setSelected(selected.plusDays(days));
    }

    /** Moves the selection by a number of months (Page Up / Page Down). */
    public void moveMonths(int months) {
        setSelected(selected.plusMonths(months));
    }

    // ---------------------------------------------------------------------

    private JButton arrowButton(String text, int months) {
        JButton b = new JButton(text);
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(0, 4, 0, 4));
        b.addActionListener(e -> showMonth(month.plusMonths(months)));
        return b;
    }

    private void showMonth(YearMonth ym) {
        month = ym;
        title.setText(month.format(TITLE));

        Color normalBg = UIManager.getColor("Panel.background");
        Color normalFg = UIManager.getColor("Label.foreground");
        Color selBg = UIManager.getColor("List.selectionBackground");
        Color selFg = UIManager.getColor("List.selectionForeground");
        LocalDate today = LocalDate.now();

        LocalDate first = month.atDay(1);
        LocalDate start = first.minusDays(first.getDayOfWeek().getValue() % 7);   // back to Sunday
        for (int i = 0; i < dayLabels.length; i++) {
            LocalDate d = start.plusDays(i);
            JLabel l = dayLabels[i];
            dayDates[i] = d;
            l.setText(String.valueOf(d.getDayOfMonth()));
            boolean isSelected = d.equals(selected);
            l.setBackground(isSelected ? selBg : normalBg);
            if (isSelected) {
                l.setForeground(selFg);
            } else {
                l.setForeground(YearMonth.from(d).equals(month) ? normalFg : Color.GRAY);
            }
            l.setBorder(BorderFactory.createCompoundBorder(
                    d.equals(today) ? TODAY_BORDER : PLAIN_BORDER,
                    BorderFactory.createEmptyBorder(1, 3, 1, 3)));
        }
        repaint();
    }
}
