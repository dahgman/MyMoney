package com.martinsmayhem.mymoney;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JWindow;

/**
 * The startup screen: the MyMoney artwork, with a progress bar and a line
 * saying what's being done, as it's actually done (reading settings,
 * unlocking the file, working out loan interest, loading accounts, ...).
 *
 * Everything here is static and runs on the event thread. Startup does its
 * work on that thread too, so each step paints the screen there and then
 * rather than waiting for a repaint. Every call does nothing when no splash
 * is showing (after startup, or if the artwork can't be read), so the steps
 * can stay in code that also runs later, like opening another file.
 */
final class Splash extends JWindow {

    // Where the artwork leaves room for the progress (splash.png is 900 x 520).
    private static final int BAR_X = 60;
    private static final int BAR_Y = 439;
    private static final int BAR_WIDTH = 780;
    private static final int BAR_HEIGHT = 6;
    private static final int TEXT_BASELINE = 475;
    private static final Color TRACK = new Color(0x1E, 0x3A, 0x52);
    private static final Color GOLD = new Color(0xE0, 0xA9, 0x3A);
    private static final Color TEXT = new Color(0x8E, 0xA2, 0xB2);

    private static Splash current;

    private final BufferedImage art;
    private String status = "Starting…";
    private int percent;

    private Splash(BufferedImage art) {
        this.art = art;
        setContentPane(new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                paintSplash((Graphics2D) g);
            }
        });
        setSize(art.getWidth(), art.getHeight());
        setLocationRelativeTo(null);
    }

    /** Shows the startup screen (does nothing if the artwork can't be read). */
    static void start() {
        BufferedImage art = art();
        if (art == null) {
            return;                                     // no splash, then; the program still starts
        }
        current = new Splash(art);
        current.setVisible(true);
        current.paintNow();
    }

    /** The MyMoney artwork (splash.png, 900 x 520), or null if it can't be read. */
    static BufferedImage art() {
        try (InputStream in = Splash.class.getResourceAsStream("splash.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Says what's being done now, and how far along startup is (0 to 100;
     * the bar never goes backwards). Brings the screen back if it was put
     * aside for a dialog.
     */
    static void step(String status, int percent) {
        Splash s = current;
        if (s == null) {
            return;
        }
        s.status = status;
        s.percent = Math.max(s.percent, Math.min(100, percent));
        if (!s.isVisible()) {
            s.setVisible(true);
        }
        s.paintNow();
    }

    /** Puts the screen aside while a dialog (like the password prompt) is up. */
    static void pause() {
        if (current != null) {
            current.setVisible(false);
        }
    }

    /** Startup is done: the screen goes. */
    static void finish() {
        if (current != null) {
            current.dispose();
            current = null;
        }
    }

    /** Paints straight away (the event thread is busy starting up, so a repaint() would wait). */
    private void paintNow() {
        JComponent c = (JComponent) getContentPane();
        c.paintImmediately(0, 0, c.getWidth(), c.getHeight());
        Toolkit.getDefaultToolkit().sync();
    }

    private void paintSplash(Graphics2D g) {
        paintArt(g, art, status, "Version " + MyMoney.VERSION, percent);
    }

    /**
     * The artwork, with a line of text at the bottom on each side (where the
     * progress goes), and the progress bar above it unless percent is negative.
     */
    static void paintArt(Graphics2D g, BufferedImage art, String left, String right, int percent) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(art, 0, 0, null);

        if (percent >= 0) {
            g.setColor(TRACK);
            g.fillRoundRect(BAR_X, BAR_Y, BAR_WIDTH, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
            int filled = BAR_WIDTH * percent / 100;
            if (filled > 0) {
                g.setColor(GOLD);
                g.fillRoundRect(BAR_X, BAR_Y, Math.max(filled, BAR_HEIGHT), BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
            }
        }

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.setColor(TEXT);
        FontMetrics fm = g.getFontMetrics();
        int rightX = BAR_X + BAR_WIDTH - fm.stringWidth(right);
        g.drawString(right, rightX, TEXT_BASELINE);
        // The left text, cut short with "…" if it would run into the right.
        String text = left;
        int room = rightX - BAR_X - 24;
        while (fm.stringWidth(text) > room && text.length() > 2) {
            text = text.substring(0, text.length() - 2) + "…";
        }
        g.drawString(text, BAR_X, TEXT_BASELINE);
    }
}
