package com.martinsmayhem.mymoney;

import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/**
 * File > Sync Settings...: where the mailbox is, which accounts go to the
 * phone, and the paired phones (docs/SYNC-DESIGN.md). Pairing and removing a
 * phone happen at once; everything else is saved with OK.
 */
final class SyncDialog extends JDialog {

    private final Database db;
    private final SyncRepository sync;
    private final AccountRepository accounts;

    private final JRadioButton off = new JRadioButton("Off");
    private final JRadioButton folder = new JRadioButton("A folder:");
    private final JRadioButton webdav = new JRadioButton("WebDAV:");
    private final JTextField folderField = new JTextField(30);
    private final JTextField urlField = new JTextField(22);
    private final JTextField pathField = new JTextField(12);
    private final JTextField userField = new JTextField(12);
    private final JPasswordField passwordField = new JPasswordField(12);
    private final JTextField phoneUrlField = new JTextField(22);
    private final JTextField phonePathField = new JTextField(12);
    private final JSpinner minutes = new JSpinner(new SpinnerNumberModel(5, 1, 240, 1));
    private final AccountsModel accountsModel = new AccountsModel();
    private final DevicesModel devicesModel = new DevicesModel();
    private final JTable devicesTable = new JTable(devicesModel);
    private boolean saved;

    private SyncDialog(Window owner, Database db) throws SQLException {
        super(owner, "Sync with Phones", ModalityType.APPLICATION_MODAL);
        this.db = db;
        this.sync = new SyncRepository(db);
        this.accounts = new AccountRepository(db);

        SyncRepository.Settings s = sync.settings();
        ButtonGroup kinds = new ButtonGroup();
        kinds.add(off);
        kinds.add(folder);
        kinds.add(webdav);
        (s.kind().equals("folder") ? folder : s.kind().equals("webdav") ? webdav : off).setSelected(true);
        folderField.setText(s.folder());
        urlField.setText(s.url());
        pathField.setText(s.path());
        userField.setText(s.user());
        passwordField.setText(s.password());
        phoneUrlField.setText(s.phoneUrl());
        phonePathField.setText(s.phonePath());
        minutes.setValue(s.minutes());
        urlField.setToolTipText("Like https://thehive:5006 (the Synology's WebDAV HTTPS port)");
        pathField.setToolTipText("The mailbox's folder on the server, like /MyMoney-Sync");
        phoneUrlField.setToolTipText("How the phone reaches the NAS, from anywhere: like https://thehive.tailnet.ts.net:5006");

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.PAGE_AXIS));
        top.add(mailboxPanel());
        top.add(phonePanel());
        content.add(top, BorderLayout.NORTH);

        JPanel lists = new JPanel(new java.awt.GridLayout(1, 2, 10, 0));
        lists.add(accountsPanel());
        lists.add(devicesPanel());
        content.add(lists, BorderLayout.CENTER);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> save());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        getContentPane().add(content, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);

        accountsModel.load();
        devicesModel.load();
        java.awt.event.ActionListener enable = e -> enableFields();
        off.addActionListener(enable);
        folder.addActionListener(enable);
        webdav.addActionListener(enable);
        enableFields();
        pack();
        setSize(Math.max(getWidth(), 900), Math.max(getHeight(), 640));
        setLocationRelativeTo(owner);
    }

    /** Shows the window. @return true if OK saved changes (the caller then syncs). */
    static boolean show(Window owner, Database db) {
        try {
            SyncDialog d = new SyncDialog(owner, db);
            d.setVisible(true);
            return d.saved;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(owner, "Could not read the sync settings:\n" + e.getMessage(),
                    "Sync with Phones", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    // ----- Layout ---------------------------------------------------------------

    private JPanel mailboxPanel() {
        JPanel p = titled("Mailbox: where the computer and phones leave each other changes");
        GridBagConstraints c = cell(0, 0);
        p.add(off, c);
        c = cell(1, 0);
        c.gridwidth = 4;
        p.add(muted("Nothing is synced"), c);

        p.add(folder, cell(0, 1));
        c = cell(1, 1);
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        p.add(folderField, c);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> browse());
        p.add(browse, cell(4, 1));
        c = cell(1, 2);
        c.gridwidth = 4;
        p.add(muted("The NAS share as this computer mounts it (the phone reaches the same folder by WebDAV),"
                + " or a folder Synology Drive, Dropbox or Nextcloud keeps in step."), c);

        p.add(webdav, cell(0, 3));
        JPanel dav = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        dav.add(new JLabel("Address"));
        dav.add(urlField);
        dav.add(new JLabel("Folder"));
        dav.add(pathField);
        dav.add(new JLabel("User"));
        dav.add(userField);
        dav.add(new JLabel("Password"));
        dav.add(passwordField);
        c = cell(1, 3);
        c.gridwidth = 4;
        p.add(dav, c);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton test = new JButton("Test");
        test.addActionListener(e -> test(test));
        row.add(test);
        row.add(new JLabel("   Sync every"));
        row.add(minutes);
        row.add(new JLabel("minutes while MyMoney is open (and with File > Sync Now)"));
        c = cell(0, 4);
        c.gridwidth = 5;
        p.add(row, c);
        return p;
    }

    private JPanel phonePanel() {
        JPanel p = titled("How phones reach the mailbox (WebDAV, from anywhere: see Help)");
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(new JLabel("Address"));
        row.add(phoneUrlField);
        row.add(new JLabel("Folder"));
        row.add(phonePathField);
        JButton same = new JButton("Same as above");
        same.setToolTipText("Copy the WebDAV address and folder above");
        same.addActionListener(e -> {
            phoneUrlField.setText(urlField.getText());
            phonePathField.setText(pathField.getText());
        });
        row.add(same);
        GridBagConstraints r = cell(0, 0);
        r.weightx = 1;
        p.add(row, r);
        GridBagConstraints c = cell(0, 1);
        c.weightx = 1;
        p.add(muted("The phone asks for its own NAS user name and password when it's paired. Best: a NAS user"
                + " allowed only into this folder."), c);
        return p;
    }

    private JPanel accountsPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Accounts on the phone"));
        JTable t = new JTable(accountsModel);
        t.getColumnModel().getColumn(0).setMaxWidth(50);
        t.setFillsViewportHeight(true);
        p.add(new JScrollPane(t), BorderLayout.CENTER);
        p.add(muted("Envelopes go with the account they're under. Every account's name goes, for transfers."),
                BorderLayout.SOUTH);
        return p;
    }

    private JPanel devicesPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Paired phones"));
        devicesTable.setFillsViewportHeight(true);
        devicesTable.getColumnModel().getColumn(1).setMaxWidth(80);
        p.add(new JScrollPane(devicesTable), BorderLayout.CENTER);
        JButton pair = new JButton("Pair a Phone...");
        pair.addActionListener(e -> pair());
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> removeDevice());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(pair);
        buttons.add(remove);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void enableFields() {
        folderField.setEnabled(folder.isSelected());
        for (JComponent f : new JComponent[] {urlField, pathField, userField, passwordField}) {
            f.setEnabled(webdav.isSelected());
        }
    }

    private static JPanel titled(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(2, 6, 6, 6)));
        return p;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1));
        l.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        return l;
    }

    private static GridBagConstraints cell(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 0, 2, 6);
        return c;
    }

    // ----- Actions --------------------------------------------------------------

    private SyncRepository.Settings settingsFromFields() {
        String kind = folder.isSelected() ? "folder" : webdav.isSelected() ? "webdav" : "";
        return new SyncRepository.Settings(kind, folderField.getText().trim(), urlField.getText().trim(),
                pathField.getText().trim(), userField.getText().trim(), new String(passwordField.getPassword()),
                phoneUrlField.getText().trim(), phonePathField.getText().trim(), (Integer) minutes.getValue());
    }

    private void browse() {
        JFileChooser fc = new JFileChooser(folderField.getText().isBlank() ? null : folderField.getText());
        fc.setDialogTitle("The mailbox folder");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            folderField.setText(fc.getSelectedFile().getPath());
        }
    }

    /** Writes, reads back and deletes a small file in the mailbox, in the background. */
    private void test(JButton button) {
        Mailbox m = SyncRepository.mailbox(settingsFromFields());
        if (m == null) {
            JOptionPane.showMessageDialog(this, "Choose a folder or fill in the WebDAV address first.", getTitle(),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        button.setEnabled(false);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                String path = "test-" + java.util.UUID.randomUUID() + ".txt";
                try {
                    byte[] data = "MyMoney".getBytes(StandardCharsets.UTF_8);
                    m.write(path, data);
                    return java.util.Arrays.equals(data, m.read(path)) ? null
                            : "What was written didn't read back the same.";
                } catch (Exception e) {
                    return e.getMessage();
                } finally {
                    cleanUpTests(m);
                }
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                setCursor(null);
                String problem;
                try {
                    problem = get();
                } catch (Exception e) {
                    problem = e.getMessage();
                }
                if (problem == null) {
                    JOptionPane.showMessageDialog(SyncDialog.this, "It works: MyMoney can use " + m.describe() + ".",
                            getTitle(), JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(SyncDialog.this, "MyMoney couldn't use " + m.describe() + ":\n"
                            + problem, getTitle(), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Deletes the Test button's files from the top of the mailbox: this
     * test's, and any an earlier one left (a write that failed half-way
     * leaves its "*.tmp"). Nothing else there is touched.
     */
    static void cleanUpTests(Mailbox m) {
        try {
            for (String name : m.list("", true)) {
                if (name.matches("test-[0-9a-f-]{36}\\.txt(\\.tmp)?")) {
                    m.delete(name);
                }
            }
        } catch (java.io.IOException ignored) {
            // they're tiny; next Test tries again
        }
    }

    private void pair() {
        SyncRepository.Settings s = settingsFromFields();
        if (!s.isSetUp()) {
            JOptionPane.showMessageDialog(this, "Set up the mailbox first.", getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (s.phoneUrl().isBlank()) {
            JOptionPane.showMessageDialog(this, "Fill in how phones reach the mailbox (its WebDAV address) first.",
                    getTitle(), JOptionPane.INFORMATION_MESSAGE);
            phoneUrlField.requestFocusInWindow();
            return;
        }
        String name = (String) JOptionPane.showInputDialog(this, "A name for this phone:", getTitle(),
                JOptionPane.QUESTION_MESSAGE, null, null, "Phone");
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            sync.saveSettings(s);                       // the QR code below uses them: keep them
            SyncRepository.Device d = sync.addDevice(name.trim());
            devicesModel.load();
            showPairingCode(d, s);
        } catch (SQLException | WriterException e) {
            JOptionPane.showMessageDialog(this, "Could not pair the phone:\n" + e.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** What the phone's QR code holds (docs/SYNC-DESIGN.md, "Pairing a phone"). */
    static String pairingText(String fileId, String fileName, SyncRepository.Device d, SyncRepository.Settings s) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("fileId", fileId);
        o.addProperty("fileName", fileName);
        o.addProperty("deviceId", d.uid());
        o.addProperty("key", SyncCrypto.toBase64(d.key()));
        JsonObject m = new JsonObject();
        m.addProperty("type", "webdav");
        m.addProperty("url", s.phoneUrl());
        m.addProperty("path", s.phonePath());
        o.add("mailbox", m);
        return o.toString();
    }

    private void showPairingCode(SyncRepository.Device d, SyncRepository.Settings s)
            throws SQLException, WriterException {
        BufferedImage img = qrImage(pairingText(sync.fileId(), db.baseName(), d, s));
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.add(new JLabel(new ImageIcon(img)), BorderLayout.CENTER);
        p.add(new JLabel("<html><div style='width:360px'>In the MyMoney app on <b>" + escape(d.name())
                + "</b>, choose <b>Pair with a computer</b> and scan this code. It then asks for the NAS user"
                + " name and password.<br><br><b>This code holds the phone's key.</b> Don't photograph or share"
                + " it; anyone with it could read this phone's sync.</div></html>"), BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, p, "Pair " + d.name(), JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * A QR code of text, about 360 pixels square: black on white, each square
     * a whole number of pixels (so every one is the same size), with the
     * standard quiet border.
     */
    static BufferedImage qrImage(String text) throws WriterException {
        BitMatrix bits = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
        int scale = Math.max(1, 360 / bits.getWidth());
        BufferedImage img = new BufferedImage(bits.getWidth() * scale, bits.getHeight() * scale,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, bits.get(x / scale, y / scale) ? 0x000000 : 0xFFFFFF);
            }
        }
        return img;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private void removeDevice() {
        int row = devicesTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        SyncRepository.Device d = devicesModel.devices.get(row);
        if (JOptionPane.showConfirmDialog(this, "Remove " + d.name() + "? It will stop syncing, and anything it"
                + " hasn't sent yet won't arrive.\nTo sync it again, pair it again.", getTitle(),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            SyncEngine.Plan plan = new SyncEngine(db, null).plan();
            sync.removeDevice(d.id());
            devicesModel.load();
            if (plan != null) {                         // its folder in the mailbox, in the background
                new Thread(() -> {
                    try {
                        SyncEngine.removeDeviceFolder(plan, d);
                    } catch (java.io.IOException ignored) {
                        // the folder stays; nothing can read it without the key, which is gone
                    }
                }, "remove phone folder").start();
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not remove the phone:\n" + e.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        if (devicesTable.isEditing()) {
            devicesTable.getCellEditor().stopCellEditing();
        }
        SyncRepository.Settings s = settingsFromFields();
        if (!s.kind().isEmpty() && !s.isSetUp()) {
            JOptionPane.showMessageDialog(this, folder.isSelected() ? "Choose the mailbox folder."
                    : "Fill in the WebDAV address.", getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            sync.saveSettings(s);
            for (int i = 0; i < accountsModel.rows.size(); i++) {
                sync.setSynced(accountsModel.rows.get(i).id(), accountsModel.ticked.get(i));
            }
            for (SyncRepository.Device d : devicesModel.devices) {
                sync.setDeviceOptions(d.id(), d.name(), d.includeScheduled());
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not save the sync settings:\n" + e.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        saved = true;
        dispose();
    }

    // ----- Tables ------------------------------------------------------------------

    /** The accounts, ticked to sync (envelopes aren't listed: they go with their parent). */
    private final class AccountsModel extends AbstractTableModel {
        final List<Account> rows = new ArrayList<>();
        final List<Boolean> ticked = new ArrayList<>();

        void load() throws SQLException {
            Set<Long> synced = sync.syncedAccounts();
            rows.clear();
            ticked.clear();
            for (Account a : accounts.findAll()) {
                if (a.type() != AccountType.ENVELOPE && !a.closed()) {
                    rows.add(a);
                    ticked.add(synced.contains(a.id()));
                }
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Sync", "Account", "Type"}[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Account a = rows.get(r);
            return switch (c) {
                case 0 -> ticked.get(r);
                case 1 -> a.name();
                default -> a.type().toString();
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            ticked.set(r, Boolean.TRUE.equals(v));
        }
    }

    /** The paired phones: name and "send scheduled transactions" can be changed in place. */
    private final class DevicesModel extends AbstractTableModel {
        final List<SyncRepository.Device> devices = new ArrayList<>();

        void load() throws SQLException {
            devices.clear();
            devices.addAll(sync.devices());
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return devices.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"Phone", "Scheduled", "Last change from it"}[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 1 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c < 2;
        }

        @Override
        public Object getValueAt(int r, int c) {
            SyncRepository.Device d = devices.get(r);
            return switch (c) {
                case 0 -> d.name();
                case 1 -> d.includeScheduled();
                default -> d.lastSeen() == null ? "never" : DateTimeFormatter.ofPattern(
                        LedgerTableModel.datePattern() + " h:mm a").withZone(ZoneId.systemDefault())
                        .format(Instant.parse(d.lastSeen()));
            };
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            SyncRepository.Device d = devices.get(r);
            devices.set(r, new SyncRepository.Device(d.id(), d.uid(),
                    c == 0 && v != null && !v.toString().isBlank() ? v.toString().trim() : d.name(), d.key(),
                    c == 1 ? Boolean.TRUE.equals(v) : d.includeScheduled(), d.appliedThrough(), d.lastSeen()));
        }
    }
}
