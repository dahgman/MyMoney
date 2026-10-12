package com.martinsmayhem.mymoney;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.Icon;

/**
 * The account tree's icon for each account type: a cheque for Checking, a
 * piggy bank for Savings, a card, a banknote, a loan document, a house for
 * Other Asset, and an envelope. A sub-account gets a stacked pair of its
 * type's icon.
 *
 * Drawn as shapes rather than images, so they stay sharp at any display
 * scaling and look the same on every system.
 */
final class AccountIcon implements Icon {

    private static final int SIZE = 18;      // every icon, plain or stacked, so names line up
    private static final int SHAPE = 14;     // one drawing is 14 x 14
    private static final int STACK = 4;      // how far the back copy is offset

    private static final Map<AccountType, AccountIcon> PLAIN = new EnumMap<>(AccountType.class);
    private static final Map<AccountType, AccountIcon> STACKED = new EnumMap<>(AccountType.class);

    private final AccountType type;
    private final boolean stacked;

    private AccountIcon(AccountType type, boolean stacked) {
        this.type = type;
        this.stacked = stacked;
    }

    /** The icon for an account: its type's, stacked if it is a sub-account. */
    static Icon of(Account a) {
        return (a.parentId() != null ? STACKED : PLAIN)
                .computeIfAbsent(a.type(), t -> new AccountIcon(t, a.parentId() != null));
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.translate(x, y);
        if (stacked) {
            Graphics2D back = (Graphics2D) g2.create();
            back.translate(STACK, 0);
            back.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            draw(back, type);
            back.dispose();
            g2.translate(0, STACK);                       // front copy, down and to the left
        } else {
            g2.translate((SIZE - SHAPE) / 2, (SIZE - SHAPE) / 2);
        }
        draw(g2, type);
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }

    // ----- The drawings, each in a 14 x 14 box ------------------------------

    private static void draw(Graphics2D g, AccountType type) {
        switch (type) {
            case CHECKING -> checking(g);
            case SAVINGS -> savings(g);
            case CREDIT_CARD -> creditCard(g);
            case CASH -> cash(g);
            case LOAN -> loan(g);
            case OTHER_ASSET -> otherAsset(g);
            case ENVELOPE -> envelope(g);
        }
    }

    /** A cheque: a slip with a box for the amount and a signature line. */
    private static void checking(Graphics2D g) {
        Color line = new Color(0x2E86C1);
        filled(g, new Rectangle2D.Float(0.5f, 3.5f, 13, 8), new Color(0xAED6F1), line);
        g.setColor(line);
        g.draw(new Line2D.Float(2.5f, 6, 7, 6));
        g.draw(new Rectangle2D.Float(9.5f, 5, 2.5f, 2));
        g.draw(new Line2D.Float(2.5f, 9, 11.5f, 9));
    }

    /** A piggy bank. */
    private static void savings(Graphics2D g) {
        Color fill = new Color(0xF5B7B1);
        Color line = new Color(0xC0392B);
        filled(g, new Rectangle2D.Float(3, 10.5f, 2, 2.5f), fill, line);    // legs
        filled(g, new Rectangle2D.Float(8, 10.5f, 2, 2.5f), fill, line);
        Path2D ear = new Path2D.Float();
        ear.moveTo(4, 5);
        ear.lineTo(5, 2);
        ear.lineTo(7, 4.5f);
        ear.closePath();
        filled(g, ear, fill, line);
        filled(g, new Ellipse2D.Float(1, 4, 11, 7.5f), fill, line);         // body
        filled(g, new RoundRectangle2D.Float(11, 6, 2.5f, 3, 1, 1), fill, line);   // snout
        g.setColor(line);
        g.draw(new Line2D.Float(5, 5.5f, 8, 5.5f));                         // coin slot
        g.fill(new Ellipse2D.Float(9.3f, 6, 1.2f, 1.2f));                   // eye
    }

    /** A credit card: magnetic stripe and chip. */
    private static void creditCard(Graphics2D g) {
        Color line = new Color(0x9C640C);
        Shape card = new RoundRectangle2D.Float(0.5f, 2.5f, 13, 9, 2.5f, 2.5f);
        filled(g, card, new Color(0xF8C471), line);
        g.setColor(line);
        g.fill(new Rectangle2D.Float(0.5f, 4.5f, 13, 2));
        g.setColor(new Color(0xFDFEFE));
        g.fill(new Rectangle2D.Float(2.5f, 8, 3, 2));
        g.setColor(line);
        g.draw(new Rectangle2D.Float(2.5f, 8, 3, 2));
    }

    /** A banknote. */
    private static void cash(Graphics2D g) {
        Color line = new Color(0x1E8449);
        filled(g, new Rectangle2D.Float(0.5f, 3.5f, 13, 8), new Color(0xABEBC6), line);
        g.setColor(line);
        g.draw(new Ellipse2D.Float(5, 5.5f, 4, 4));
        g.fill(new Ellipse2D.Float(2, 7, 1.2f, 1.2f));
        g.fill(new Ellipse2D.Float(10.8f, 7, 1.2f, 1.2f));
    }

    /** A loan: a document marked with a percent sign. */
    private static void loan(Graphics2D g) {
        Color line = new Color(0xB03A2E);
        Path2D page = new Path2D.Float();          // folded top-right corner
        page.moveTo(2.5f, 0.5f);
        page.lineTo(9, 0.5f);
        page.lineTo(11.5f, 3);
        page.lineTo(11.5f, 13.5f);
        page.lineTo(2.5f, 13.5f);
        page.closePath();
        filled(g, page, new Color(0xFADBD8), line);
        g.setColor(line);
        g.draw(new Ellipse2D.Float(4, 4, 2, 2));
        g.draw(new Ellipse2D.Float(8, 9, 2, 2));
        g.draw(new Line2D.Float(9.5f, 4, 4.5f, 11));
    }

    /** Other assets: a house. */
    private static void otherAsset(Graphics2D g) {
        Color line = new Color(0x117A65);
        Color fill = new Color(0xA2D9CE);
        filled(g, new Rectangle2D.Float(2.5f, 6.5f, 9, 7), fill, line);
        Path2D roof = new Path2D.Float();
        roof.moveTo(0.5f, 7.5f);
        roof.lineTo(7, 1);
        roof.lineTo(13.5f, 7.5f);
        roof.closePath();
        filled(g, roof, fill, line);
        g.setColor(line);
        g.fill(new Rectangle2D.Float(6, 9.5f, 2, 4));
    }

    /** An envelope. */
    private static void envelope(Graphics2D g) {
        Color line = new Color(0xAF7A2B);
        filled(g, new Rectangle2D.Float(0.5f, 3, 13, 9), new Color(0xFAE5C3), line);
        Path2D flap = new Path2D.Float();
        flap.moveTo(0.5f, 3);
        flap.lineTo(7, 8);
        flap.lineTo(13.5f, 3);
        g.setColor(line);
        g.draw(flap);
    }

    /** Fills a shape and outlines it. The fill also hides whatever is behind (the stacked copy). */
    private static void filled(Graphics2D g, Shape s, Color fill, Color line) {
        g.setColor(fill);
        g.fill(s);
        g.setColor(line);
        g.draw(s);
    }
}
