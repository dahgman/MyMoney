package com.martinsmayhem.mymoney;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.swing.UIManager;

/**
 * The app's light or dark look (Edit > Preferences > General > Theme):
 * Light, Dark, or Match the system, which goes by the desktop's own
 * dark-mode setting (GNOME, KDE, Windows or macOS) when the app starts.
 * Also the font everything is drawn in, and its size (Preferences >
 * General > Font): FlatLaf sizes rows, padding and the like to match.
 */
final class Theme {

    /** The choices, as stored in AppConfig.THEME. */
    enum Choice {
        SYSTEM("system", "Match the system"),
        LIGHT("light", "Light"),
        DARK("dark", "Dark");

        final String key;
        final String label;

        Choice(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /** The choice stored as key; anything else: Match the system. */
        static Choice of(String key) {
            for (Choice c : values()) {
                if (c.key.equals(key)) {
                    return c;
                }
            }
            return SYSTEM;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private Theme() {
    }

    /** The font chosen: a family ("" = the look's own) and a size in points (0 = the look's own). */
    private static String fontFamily = "";
    private static int fontSize;

    /** The look's own font (before any chosen one): what Default goes back to. */
    static java.awt.Font lookFont() {
        if (lookFont == null) {
            java.awt.Font f = new FlatLightLaf().getDefaults().getFont("defaultFont");
            lookFont = f != null ? f : new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);
        }
        return lookFont;
    }

    private static java.awt.Font lookFont;

    /** The font to use from the next apply/switchTo on: family "" and size 0 for the look's own. */
    static void setFont(String family, int size) {
        fontFamily = family == null ? "" : family.trim();
        fontSize = Math.max(0, size);
    }

    /** Sets the look for windows made from now on. */
    static void apply(Choice choice) {
        boolean dark = choice == Choice.DARK || (choice == Choice.SYSTEM && systemPrefersDark());
        try {
            FlatLaf laf = dark ? new FlatDarkLaf() : new FlatLightLaf();
            java.util.Map<String, String> extra = new java.util.HashMap<>();
            if (dark) {
                extra.putAll(DARK_COLORS);
            }
            String font = fontSpec(fontFamily, fontSize);
            if (font != null) {
                extra.put("defaultFont", font);
            }
            laf.setExtraDefaults(extra);
            UIManager.setLookAndFeel(laf);
        } catch (Exception e) {
            // keep whatever look is set
        }
    }

    /**
     * FlatLaf's way of writing a font (its "defaultFont" value): the size,
     * then the family in quotes, like 16 "Liberation Serif"; null for the
     * look's own font.
     */
    static String fontSpec(String family, int size) {
        if ((family == null || family.isBlank()) && size <= 0) {
            return null;
        }
        String s = size > 0 ? String.valueOf(size) : "+0";
        return family == null || family.isBlank() ? s : s + " \"" + family.replace("\"", "") + "\"";
    }

    /** FlatLaf color variables for the dark look (the rest are worked out from these). */
    static final java.util.Map<String, String> DARK_COLORS = java.util.Map.of(
            "@background", "#1E1F22",
            "@foreground", "#E4E6EB",
            "@accentColor", "#3D7EF5",
            "@selectionBackground", "#2E5BB8",
            "@disabledForeground", "#8C8F94");

    /** Changes the look of every open window now. */
    static void switchTo(Choice choice) {
        apply(choice);
        FlatLaf.updateUI();
    }

    /** True while the look is a dark one. */
    static boolean isDark() {
        return UIManager.getLookAndFeel() instanceof FlatLaf f && f.isDark();
    }

    /** True if the desktop is set to dark mode (false if it can't be told). */
    static boolean systemPrefersDark() {
        Boolean dark = systemPrefersDark(System.getProperty("os.name", ""), Theme::run,
                Path.of(System.getProperty("user.home"), ".config", "kdeglobals"), System.getenv("GTK_THEME"));
        return dark != null && dark;
    }

    /**
     * Whether the desktop is in dark mode, asking it with run (a command's
     * output, or null if it couldn't run): null if it can't be told.
     */
    static Boolean systemPrefersDark(String os, Function<String[], String> run, Path kdeGlobals, String gtkThemeEnv) {
        String name = os.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            // "AppsUseLightTheme    REG_DWORD    0x0" is dark.
            String out = run.apply(new String[] {"reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"});
            return out == null || !out.contains("AppsUseLightTheme") ? null : out.trim().endsWith("0x0");
        }
        if (name.startsWith("mac")) {
            // Prints "Dark" in dark mode; nothing (an error) in light mode.
            String out = run.apply(new String[] {"defaults", "read", "-g", "AppleInterfaceStyle"});
            return out != null && out.trim().equalsIgnoreCase("dark");
        }
        // Linux and the like. GNOME (and most others) say it here:
        String scheme = run.apply(new String[] {"gsettings", "get", "org.gnome.desktop.interface", "color-scheme"});
        if (scheme != null && scheme.contains("prefer-dark")) {
            return true;
        }
        if (scheme != null && scheme.contains("prefer-light")) {
            return false;
        }
        // An older desktop: a dark GTK theme, like Adwaita-dark.
        String gtk = run.apply(new String[] {"gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"});
        if (gtk != null && gtk.toLowerCase(Locale.ROOT).contains("dark")) {
            return true;
        }
        if (gtkThemeEnv != null && gtkThemeEnv.toLowerCase(Locale.ROOT).contains("dark")) {
            return true;
        }
        // KDE: its color scheme, like "BreezeDark".
        try {
            if (kdeGlobals != null && Files.isRegularFile(kdeGlobals)) {
                for (String line : Files.readAllLines(kdeGlobals, StandardCharsets.UTF_8)) {
                    if (line.startsWith("ColorScheme=")) {
                        return line.toLowerCase(Locale.ROOT).contains("dark");
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // can't be read: not known
        }
        return scheme != null || gtk != null ? false : null;
    }

    /** A command's output, or null if it couldn't run, failed or took over 2 seconds. */
    private static String run(String[] command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
