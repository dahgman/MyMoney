package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Telling whether the desktop is in dark mode (Theme: Match the system). */
class ThemeTest {

    @TempDir
    Path dir;

    /** A desktop that answers these commands (by their last word) with these outputs. */
    private static Function<String[], String> answers(Map<String, String> byLastWord) {
        return command -> byLastWord.get(command[command.length - 1]);
    }

    @Test
    void gnomesColorScheme() {
        assertTrue(Theme.systemPrefersDark("Linux", answers(Map.of("color-scheme", "'prefer-dark'\n")), null, null));
        assertFalse(Theme.systemPrefersDark("Linux", answers(Map.of("color-scheme", "'prefer-light'\n",
                "gtk-theme", "'Adwaita-dark'")), null, null), "the color scheme wins");
    }

    @Test
    void anOlderDesktopsDarkGtkTheme() {
        assertTrue(Theme.systemPrefersDark("Linux", answers(Map.of("color-scheme", "'default'",
                "gtk-theme", "'Yaru-dark'")), null, null));
        assertFalse(Theme.systemPrefersDark("Linux", answers(Map.of("color-scheme", "'default'",
                "gtk-theme", "'Yaru'")), null, null));
        assertTrue(Theme.systemPrefersDark("Linux", answers(Map.of()), null, "Adwaita:dark"));
    }

    @Test
    void kde() throws Exception {
        Path globals = Files.writeString(dir.resolve("kdeglobals"), "[General]\nColorScheme=BreezeDark\n");
        assertTrue(Theme.systemPrefersDark("Linux", answers(Map.of()), globals, null));
        Files.writeString(globals, "[General]\nColorScheme=BreezeLight\n");
        assertFalse(Theme.systemPrefersDark("Linux", answers(Map.of()), globals, null));
        assertNull(Theme.systemPrefersDark("Linux", answers(Map.of()), dir.resolve("none"), null), "can't tell");
    }

    @Test
    void windows() {
        String dark = "\nHKEY_CURRENT_USER\\...\\Personalize\n    AppsUseLightTheme    REG_DWORD    0x0\n\n";
        String light = "\nHKEY_CURRENT_USER\\...\\Personalize\n    AppsUseLightTheme    REG_DWORD    0x1\n\n";
        assertTrue(Theme.systemPrefersDark("Windows 11", answers(Map.of("AppsUseLightTheme", dark)), null, null));
        assertFalse(Theme.systemPrefersDark("Windows 11", answers(Map.of("AppsUseLightTheme", light)), null, null));
        assertNull(Theme.systemPrefersDark("Windows 10", answers(Map.of()), null, null));
    }

    @Test
    void mac() {
        assertTrue(Theme.systemPrefersDark("Mac OS X", answers(Map.of("AppleInterfaceStyle", "Dark\n")), null, null));
        assertFalse(Theme.systemPrefersDark("Mac OS X", answers(Map.of()), null, null));
    }

    @Test
    void fontsAreWrittenAsFlatLafReadsThem() {
        assertNull(Theme.fontSpec("", 0), "the look's own font");
        assertEquals("16", Theme.fontSpec("", 16));
        assertEquals("+0 \"Liberation Serif\"", Theme.fontSpec("Liberation Serif", 0));
        assertEquals("18 \"DejaVu Sans\"", Theme.fontSpec("DejaVu Sans", 18));
    }

    @Test
    void theChosenFontIsUsed() {
        try {
            Theme.setFont("", 18);
            Theme.apply(Theme.Choice.LIGHT);
            assertEquals(18, javax.swing.UIManager.getFont("Label.font").getSize());
            assertEquals(18, javax.swing.UIManager.getFont("Table.font").getSize());
            Theme.setFont("", 0);
            Theme.apply(Theme.Choice.LIGHT);
            assertEquals(Theme.lookFont().getSize(), javax.swing.UIManager.getFont("Label.font").getSize(), "back to its own");
        } finally {
            Theme.setFont("", 0);
        }
    }

    @Test
    void theWindowsSizeIsReadBack() {
        assertEquals(new java.awt.Rectangle(100, 50, 1400, 900), MainFrame.parseBounds("100,50,1400,900"));
        assertEquals(new java.awt.Rectangle(-1800, 0, 1400, 900), MainFrame.parseBounds("-1800, 0, 1400, 900"),
                "a monitor to the left");
        assertNull(MainFrame.parseBounds(""), "never saved");
        assertNull(MainFrame.parseBounds("1,2,3"));
        assertNull(MainFrame.parseBounds("a,b,c,d"));
        assertNull(MainFrame.parseBounds("0,0,50,40"), "too small to use");
    }

    @Test
    void choicesAreStoredByKey() {
        assertEquals(Theme.Choice.DARK, Theme.Choice.of("dark"));
        assertEquals(Theme.Choice.LIGHT, Theme.Choice.of("light"));
        assertEquals(Theme.Choice.SYSTEM, Theme.Choice.of(""), "unset: match the system");
        assertEquals("Match the system", Theme.Choice.SYSTEM.toString());
    }
}
