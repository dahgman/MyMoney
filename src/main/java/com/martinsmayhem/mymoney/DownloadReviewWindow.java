package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Reviews a bank download one transaction at a time. The downloaded
 * transactions are listed at the top. Selecting one lists, underneath, the
 * transactions already in the account it could be (same amount, within the
 * allowed days; likeliest first, by payee and date), with the likeliest
 * selected. Ones already cleared or reconciled are listed after them, greyed
 * out and never pre-selected: the download may be one entered and cleared by
 * hand. Accept Match uses the selected one (Link to This, for one already
 * cleared: its status stays as it is); Add as New enters the download as a
 * new transaction, under the payee typed beside it (with suggestions narrowing as you type, as in
 * the ledger's Category). It starts as
 * the payee chosen for that bank name last time, or else the bank's name,
 * and has the focus when nothing open matches. Either way the next one to review is
 * selected. Anything not reviewed when the window closes isn't imported (and
 * will be offered again the next time the file is imported).
 */
final class DownloadReviewWindow extends JDialog {

    /** What happened to a downloaded transaction in this window. */
    private enum Outcome {
        TO_REVIEW(""), MATCHED("Matched"), LINKED("Linked"), ADDED("Added as new");

        final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    private final Account account;
    private final DownloadImporter importer;
    private final boolean match;
    private final int days;
    private final List<DownloadImporter.Download> downloads;
    private final List<Outcome> outcomes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();        // "with Electric Co, 09/10/2026"
    private List<DownloadImporter.Candidate> candidates = List.of();

    private final DownloadModel downloadModel = new DownloadModel();
    private final CandidateModel candidateModel = new CandidateModel();
    private final JTable downloadTable = new JTable(downloadModel);
    private final JTable candidateTable = new JTable(candidateModel);
    private final JLabel candidateHeading = new JLabel(" ");
    private final JLabel progressLabel = new JLabel(" ");
    private static final java.awt.Color GREYED = new java.awt.Color(128, 128, 128);
    private final JButton acceptButton = new JButton("Accept Match");
    private final JButton addButton = new JButton("Add as New");
    private final JTextField payeeField = new JTextField(24);    // payee for Add as New
    private final List<String> payeeNames = new ArrayList<>();   // its suggestions
    private final AutoComplete payeeComplete = new AutoComplete(payeeField, () -> payeeNames);
    private final JLabel rememberedLabel = new JLabel("(remembered from before)");
    private boolean changed;

    /**
     * @param downloads the downloads not imported before
     * @param skipped   how many were left out as imported before
     * @param match     false to never look for matches (Add as New only)
     */
    DownloadReviewWindow(Window owner, String fileName, Account account, List<DownloadImporter.Download> downloads,
                         int skipped, DownloadImporter importer, boolean match, int days) {
        super(owner, "Review Downloaded Transactions: " + fileName, ModalityType.APPLICATION_MODAL);
        this.account = account;
        this.importer = importer;
        this.match = match;
        this.days = days;
        this.downloads = downloads;
        for (int i = 0; i < downloads.size(); i++) {
            outcomes.add(Outcome.TO_REVIEW);
            notes.add("");
        }
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });

        // Top: the downloads.
        downloadTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        downloadTable.setFillsViewportHeight(true);
        downloadTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                downloadSelected();
            }
        });
        setWidths(downloadTable, 95, 220, 180, 70, 100, 230);
        rightAlign(downloadTable, 4);
        ColumnFit.install(downloadTable);
        JLabel heading = new JLabel("Downloaded transactions for " + account.name()
                + (skipped > 0 ? "   (" + skipped + " imported before, not shown)" : ""));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(heading, BorderLayout.NORTH);
        top.add(new JScrollPane(downloadTable), BorderLayout.CENTER);

        // Bottom: possible matches, and what to do.
        candidateTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        candidateTable.setFillsViewportHeight(true);
        // Already-cleared candidates are greyed out.
        candidateTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(column == 4 ? SwingConstants.RIGHT : SwingConstants.LEADING);
                if (!isSelected) {
                    setForeground(candidates.get(row).alreadyCleared() ? GREYED : table.getForeground());
                }
                return this;
            }
        });
        candidateTable.getSelectionModel().addListSelectionListener(e -> updateButtons());
        candidateTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && candidateTable.getSelectedRow() >= 0) {
                    acceptMatch();                       // double-click = Accept Match
                }
            }
        });
        setWidths(candidateTable, 140, 190, 170, 70, 100, 140);
        ColumnFit.install(candidateTable);
        candidateHeading.setFont(candidateHeading.getFont().deriveFont(Font.BOLD));
        acceptButton.addActionListener(e -> acceptMatch());
        addButton.addActionListener(e -> addAsNew());
        payeeField.setToolTipText("The payee to use if it's added as new: keep the suggested name, or type"
                + " over it (the list narrows to payees you already use); Down shows them all");
        try {
            payeeNames.addAll(importer.payeeNames());
        } catch (SQLException e) {
            // type it, then
        }
        payeeComplete.reload();
        // Enter: pick the highlighted suggestion if there is one, otherwise Add as New.
        // Escape: close the suggestions.
        payeeField.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "payee-enter");
        payeeField.getActionMap().put("payee-enter", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!payeeComplete.pickHighlighted() && addButton.isEnabled()) {
                    addAsNew();
                }
            }
        });
        payeeField.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "payee-escape");
        payeeField.getActionMap().put("payee-escape", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                payeeComplete.hidePopup();
            }
        });
        payeeField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                payeeField.selectAll();                  // typing replaces the suggestion
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                payeeComplete.hidePopup();
            }
        });
        JPanel actions = new JPanel(new BorderLayout());
        JPanel payeeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        payeeRow.add(new JLabel("Payee if added as new:"));
        payeeRow.add(payeeField);
        payeeRow.add(rememberedLabel);
        rememberedLabel.setForeground(GREYED);
        rememberedLabel.setVisible(false);
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttonRow.add(acceptButton);
        buttonRow.add(addButton);
        actions.add(payeeRow, BorderLayout.WEST);
        actions.add(buttonRow, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bottom.add(candidateHeading, BorderLayout.NORTH);
        bottom.add(new JScrollPane(candidateTable), BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.55);

        JButton done = new JButton("Done");
        done.addActionListener(e -> close());
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        footer.add(progressLabel, BorderLayout.WEST);
        footer.add(done, BorderLayout.EAST);

        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(footer, BorderLayout.SOUTH);

        updateProgress();
        selectNextToReview(0);
        setSize(950, 650);
        split.setDividerLocation(0.55);
        setLocationRelativeTo(owner);
    }

    /** True if anything was matched or added. */
    boolean wasChanged() {
        return changed;
    }

    // ----- Reviewing ---------------------------------------------------------

    private void downloadSelected() {
        int row = downloadTable.getSelectedRow();
        candidates = List.of();
        if (row < 0) {
            candidateHeading.setText(" ");
        } else if (outcomes.get(row) != Outcome.TO_REVIEW) {
            candidateHeading.setText(outcomes.get(row).label + " " + notes.get(row) + ".");
        } else if (!match) {
            candidateHeading.setText("Matching is turned off for this import: add it as new.");
        } else {
            DownloadImporter.Download d = downloads.get(row);
            try {
                candidates = importer.candidates(account.id(), d, days);
            } catch (SQLException e) {
                error("Could not look for matches:\n" + e.getMessage());
            }
            boolean anyOpen = candidates.stream().anyMatch(c -> !c.alreadyCleared());
            String range = " (same amount, within " + days + " days";
            candidateHeading.setText(candidates.isEmpty()
                    ? "No transaction in " + account.name() + " matches" + range + "). Add it as new."
                    : anyOpen
                    ? "Possible matches in " + account.name() + range + "; likeliest first):"
                    : "No open match" + range + "). Greyed = already cleared: link one if it's this"
                            + " download, or add it as new.");
        }
        candidateModel.fireTableDataChanged();
        if (!candidates.isEmpty() && !candidates.get(0).alreadyCleared()) {
            candidateTable.setRowSelectionInterval(0, 0);   // the likeliest (never an already-cleared one)
        }
        boolean toReview = row >= 0 && outcomes.get(row) == Outcome.TO_REVIEW;
        payeeField.setEnabled(toReview);
        payeeComplete.hidePopup();
        String remembered = null;
        if (toReview) {
            try {
                remembered = importer.rememberedPayee(downloads.get(row));
            } catch (SQLException e) {
                // the bank's name, then
            }
        }
        payeeComplete.setTextQuietly(!toReview ? "" : remembered != null ? remembered
                : text(downloads.get(row).payee()));
        rememberedLabel.setVisible(remembered != null);
        if (toReview && candidates.stream().allMatch(DownloadImporter.Candidate::alreadyCleared)) {
            // Nothing open matches, so it will most likely be added: start on its payee.
            javax.swing.SwingUtilities.invokeLater(() -> {
                payeeField.requestFocusInWindow();
                payeeField.selectAll();
            });
        }
        updateButtons();
    }

    private void updateButtons() {
        int row = downloadTable.getSelectedRow();
        boolean toReview = row >= 0 && outcomes.get(row) == Outcome.TO_REVIEW;
        int c = candidateTable.getSelectedRow();
        acceptButton.setEnabled(toReview && c >= 0);
        acceptButton.setText(c >= 0 && candidates.get(c).alreadyCleared() ? "Link to This" : "Accept Match");
        acceptButton.setToolTipText(c >= 0 && candidates.get(c).alreadyCleared()
                ? "Record this download against it, leaving its status as it is" : null);
        addButton.setEnabled(toReview);
        getRootPane().setDefaultButton(acceptButton.isEnabled() ? acceptButton : addButton);
    }

    private void acceptMatch() {
        int row = downloadTable.getSelectedRow();
        int c = candidateTable.getSelectedRow();
        if (row < 0 || c < 0 || outcomes.get(row) != Outcome.TO_REVIEW) {
            return;
        }
        DownloadImporter.Candidate match = candidates.get(c);
        try {
            importer.accept(downloads.get(row), match);
        } catch (SQLException e) {
            error("Could not match the transaction:\n" + e.getMessage());
            return;
        }
        String with = (match.payee() == null ? "a transaction" : match.payee())
                + " on " + match.date().format(LedgerTableModel.DISPLAY_DATE);
        if (match.alreadyCleared()) {
            done(row, Outcome.LINKED, "to " + with + " (already " + match.status().label().toLowerCase() + ")");
        } else {
            done(row, Outcome.MATCHED, "with " + with);
        }
    }

    private void addAsNew() {
        int row = downloadTable.getSelectedRow();
        if (row < 0 || outcomes.get(row) != Outcome.TO_REVIEW) {
            return;
        }
        payeeComplete.hidePopup();
        String payee = payeeField.getText().trim();
        try {
            importer.addNew(account, downloads.get(row), payee);
        } catch (SQLException e) {
            error("Could not add the transaction:\n" + e.getMessage());
            return;
        }
        if (!payee.isEmpty() && payeeNames.stream().noneMatch(payee::equalsIgnoreCase)) {
            payeeNames.add(payee);                       // a new payee: offer it for the rest too
            payeeNames.sort(String.CASE_INSENSITIVE_ORDER);
            payeeComplete.reload();
        }
        done(row, Outcome.ADDED, payee.isEmpty() ? "(no payee)" : "(payee: " + payee + ")");
    }

    /** Records what happened to a row and moves on to the next one to review. */
    private void done(int row, Outcome outcome, String note) {
        outcomes.set(row, outcome);
        notes.set(row, note);
        changed = true;
        downloadModel.fireTableRowsUpdated(row, row);
        updateProgress();
        selectNextToReview(row + 1);
    }

    /** Selects the first row still to review at or after from (wrapping around); the same row if none. */
    private void selectNextToReview(int from) {
        int n = downloads.size();
        for (int i = 0; i < n; i++) {
            int r = (from + i) % n;
            if (outcomes.get(r) == Outcome.TO_REVIEW) {
                downloadTable.setRowSelectionInterval(r, r);
                downloadTable.scrollRectToVisible(downloadTable.getCellRect(r, 0, true));
                return;
            }
        }
        downloadSelected();                              // all reviewed: show the current row's result
    }

    private int toReview() {
        return (int) outcomes.stream().filter(o -> o == Outcome.TO_REVIEW).count();
    }

    private void updateProgress() {
        long matched = outcomes.stream().filter(o -> o == Outcome.MATCHED).count();
        long linked = outcomes.stream().filter(o -> o == Outcome.LINKED).count();
        long added = outcomes.stream().filter(o -> o == Outcome.ADDED).count();
        progressLabel.setText(matched + " matched, " + (linked > 0 ? linked + " linked, " : "")
                + added + " added, " + toReview() + " still to review");
    }

    private void close() {
        int left = toReview();
        if (left > 0) {
            Object[] options = {"Close", "Keep Reviewing"};
            int choice = JOptionPane.showOptionDialog(this, left + (left == 1 ? " transaction hasn't" : " transactions haven't")
                    + " been reviewed, so " + (left == 1 ? "it won't" : "they won't") + " be imported.\n"
                    + "Importing this file again will offer " + (left == 1 ? "it" : "them") + " again.",
                    getTitle(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
            if (choice != 0) {
                return;
            }
        }
        dispose();
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
    }

    // ----- Tables ------------------------------------------------------------

    private static String date(LocalDate d) {
        return d.format(LedgerTableModel.DISPLAY_DATE);
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    /** The downloads: date, the bank's name and memo, check number, amount, and what happened. */
    private final class DownloadModel extends AbstractTableModel {
        private final String[] columns = {"Date", "Name", "Memo", "Check #", "Amount", "Result"};

        @Override
        public int getRowCount() {
            return downloads.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            DownloadImporter.Download d = downloads.get(row);
            return switch (column) {
                case 0 -> date(d.date());
                case 1 -> text(d.payee());
                case 2 -> text(d.memo());
                case 3 -> text(d.checkNumber());
                case 4 -> Money.format(d.amountCents());
                case 5 -> outcomes.get(row) == Outcome.TO_REVIEW ? "" : outcomes.get(row).label + " " + notes.get(row);
                default -> "";
            };
        }
    }

    /** Possible matches: date, payee, memo, check number, amount, status. */
    private final class CandidateModel extends AbstractTableModel {
        private final String[] columns = {"Date", "Payee", "Memo", "Check #", "Amount", "Status"};

        @Override
        public int getRowCount() {
            return candidates.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            DownloadImporter.Candidate c = candidates.get(row);
            return switch (column) {
                case 0 -> date(c.date()) + (c.daysApart() == 0 ? "" : "  (" + c.daysApart()
                        + (c.daysApart() == 1 ? " day)" : " days)"));
                case 1 -> text(c.payee());
                case 2 -> text(c.memo());
                case 3 -> text(c.checkNumber());
                case 4 -> Money.format(c.amountCents());
                case 5 -> c.status() == TxnStatus.RECONCILED ? "Already reconciled"
                        : c.status() == TxnStatus.CLEARED ? "Already cleared"
                        : c.status() == TxnStatus.RECONCILING ? "Being reconciled"
                        : c.status() == TxnStatus.NONE ? "" : c.status().label();
                default -> "";
            };
        }
    }

    private static void setWidths(JTable table, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    private static void rightAlign(JTable table, int column) {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(column).setCellRenderer(r);
    }
}
