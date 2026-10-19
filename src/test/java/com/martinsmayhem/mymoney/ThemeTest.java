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
    void choicesAreStoredByKey() {
        assertEquals(Theme.Choice.DARK, Theme.Choice.of("dark"));
        assertEquals(Theme.Choice.LIGHT, Theme.Choice.of("light"));
        assertEquals(Theme.Choice.SYSTEM, Theme.Choice.of(""), "unset: match the system");
        assertEquals("Match the system", Theme.Choice.SYSTEM.toString());
    }
}
