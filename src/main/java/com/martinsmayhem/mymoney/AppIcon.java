package com.martinsmayhem.mymoney;

import java.awt.Image;
import java.awt.Taskbar;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;

/**
 * The MyMoney icon, at the sizes window managers ask for (each drawn from
 * the 1024-pixel original, so small ones stay sharp).
 */
final class AppIcon {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static List<Image> images;

    private AppIcon() {
    }

    /** Every size, smallest first (empty if they can't be read). */
    static synchronized List<Image> images() {
        if (images == null) {
            List<Image> list = new ArrayList<>();
            for (int size : SIZES) {
                try (InputStream in = AppIcon.class.getResourceAsStream("icon-" + size + ".png")) {
                    if (in != null) {
                        list.add(ImageIO.read(in));
                    }
                } catch (IOException ignored) {
                    // that size is left out
                }
            }
            images = List.copyOf(list);
        }
        return images;
    }

    /** The 64-pixel icon, for a message box (null if it can't be read). */
    static javax.swing.Icon large() {
        for (Image i : images()) {
            if (i.getWidth(null) == 64) {
                return new javax.swing.ImageIcon(i);
            }
        }
        return null;
    }

    /**
     * Uses the icon everywhere: the taskbar or dock (where Java can set it)
     * and every window without a parent of its own (the startup dialogs and
     * message boxes take theirs from the shared owner frame).
     */
    static void install() {
        List<Image> list = images();
        if (list.isEmpty()) {
            return;
        }
        JOptionPane.getRootFrame().setIconImages(list);
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(list.get(list.size() - 1));
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // not on this desktop
        }
    }
}
