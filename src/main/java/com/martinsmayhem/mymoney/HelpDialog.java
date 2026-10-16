package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;

/**
 * Help > MyMoney Help (F1): how MyMoney works, from help.html. The contents
 * at the top jump to each section; links to web pages open in the browser.
 * Not modal, so it can stay open beside the ledger.
 */
final class HelpDialog extends JDialog {

    private static HelpDialog open;                     // only one at a time

    private HelpDialog(Window owner) {
        super(owner, "MyMoney Help", ModalityType.MODELESS);
        JEditorPane page = new JEditorPane();
        page.setEditable(false);
        page.setContentType("text/html");
        page.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);   // the theme's font
        page.setText(html());
        // Links readable on the theme's background: light blue on dark, the usual blue on light.
        java.awt.Color bg = page.getBackground();
        boolean dark = bg != null && (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000 < 128;
        ((javax.swing.text.html.HTMLDocument) page.getDocument()).getStyleSheet()
                .addRule("a { color: " + (dark ? "#8AB4F8" : "#1A5FB4") + "; }");
        page.setCaretPosition(0);
        page.addHyperlinkListener(e -> {
            if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }
            String target = e.getDescription();
            if (target != null && target.startsWith("#")) {
                page.scrollToReference(target.substring(1));          // a section of this page
            } else if (e.getURL() != null) {
                openInBrowser(e.getURL().toString());
            }
        });
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JButton top = new JButton("Contents");
        top.addActionListener(e -> page.scrollToReference("contents"));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(top);
        buttons.add(close);

        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(780, 640);
        setLocationRelativeTo(owner);
    }

    /** Shows the help (or brings the open one to the front). */
    static void show(Window owner) {
        if (open == null || !open.isDisplayable()) {
            open = new HelpDialog(owner);
        }
        open.setVisible(true);
        open.toFront();
    }

    private static String html() {
        try (InputStream in = HelpDialog.class.getResourceAsStream("help.html")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("{version}", MyMoney.VERSION)
                        .replace("{dateFormat}", LedgerTableModel.dateFormatExample());
            }
        } catch (IOException ignored) {
            // shown below
        }
        return "<html><body><p>The help couldn't be read.</p></body></html>";
    }

    private void openInBrowser(String url) {
        new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(this,
                        "Could not open " + url + ":\n" + e.getMessage(), getTitle(),
                        javax.swing.JOptionPane.ERROR_MESSAGE));
            }
        }, "open-link").start();
    }
}
