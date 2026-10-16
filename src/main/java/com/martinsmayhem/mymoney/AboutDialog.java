package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/**
 * Help > About MyMoney: the startup artwork, with the version and release
 * date where the startup progress goes, and below it, where the open file
 * and the settings are kept.
 */
final class AboutDialog extends JDialog {

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private AboutDialog(Window owner, Path dataFile) {
        super(owner, "About MyMoney", ModalityType.APPLICATION_MODAL);
        BufferedImage art = Splash.art();
        String released = MyMoney.RELEASE_DATE == null ? "Development build"
                : "Released " + MyMoney.RELEASE_DATE.format(LONG_DATE);
        String version = "Version " + MyMoney.VERSION;

        JComponent picture;
        if (art != null) {
            picture = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Splash.paintArt((Graphics2D) g, art, released, version, -1);   // no progress bar
                }
            };
            picture.setPreferredSize(new Dimension(art.getWidth(), art.getHeight()));
            picture.setToolTipText("Click to close");
            picture.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    dispose();
                }
            });
        } else {
            picture = new JLabel("<html><h1>MyMoney</h1>" + version + "<br>" + released + "</html>");
            picture.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        }

        JLabel details = new JLabel("<html>© " + (MyMoney.RELEASE_DATE == null ? java.time.Year.now()
                : MyMoney.RELEASE_DATE.getYear()) + " Mike Martin &nbsp;·&nbsp; Java " + System.getProperty("java.version")
                + (dataFile == null ? "" : "<br>File: " + escape(dataFile.toString()))
                + "<br>Settings: " + escape(AppConfig.configDirectory().toString()) + "</html>");
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        bottom.add(details, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(close);
        bottom.add(buttons, BorderLayout.EAST);

        getContentPane().add(picture, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setResizable(false);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Shows the About window (dataFile: the open file, or null). */
    static void show(Window owner, Path dataFile) {
        new AboutDialog(owner, dataFile).setVisible(true);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
