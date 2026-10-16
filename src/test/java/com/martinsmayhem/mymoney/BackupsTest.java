package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Database backup names and retention. */
class BackupsTest {

    @TempDir
    Path dir;
    private final Path dataFile = Path.of("/data/Household/Household.MyMoney");

    private Path backup(String stamp) throws Exception {
        return Files.writeString(dir.resolve("Household_" + stamp + ".MyMoney"), "db");
    }

    private List<String> names() throws Exception {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void namedForTheFileAndTheTime() throws Exception {
        LocalDateTime when = LocalDateTime.of(2026, 10, 16, 8, 45, 12);
        Path first = Backups.target(dataFile, dir, when);
        assertEquals("Household_20261016_0845.MyMoney", first.getFileName().toString());
        Files.writeString(first, "db");
        assertEquals("Household_20261016_0845_2.MyMoney", Backups.target(dataFile, dir, when).getFileName().toString(),
                "the same minute again: not overwritten");
    }

    @Test
    void onlyThisFilesBackupsAreListed() throws Exception {
        backup("20261016_0845");
        backup("20261016_0845_2");
        Files.writeString(dir.resolve("Household.MyMoney"), "not a backup");
        Files.writeString(dir.resolve("Budget_20261016_0845.MyMoney"), "another file's");
        Files.writeString(dir.resolve("Household_20261016_0845.txt"), "not ours");
        Files.writeString(dir.resolve("Household_notadate_0845.MyMoney"), "not ours");
        List<Backups.Backup> list = Backups.list(dataFile, dir);
        assertEquals(2, list.size());
        assertTrue(list.get(0).made().isBefore(list.get(1).made()), "oldest first; _2 after the first");
    }

    @Test
    void keepOnlySoManyADay() throws Exception {
        for (String t : new String[] {"0800", "0900", "1000", "1100"}) {
            backup("20261016_" + t);
        }
        backup("20261015_2300");
        assertEquals(2, Backups.prune(dataFile, dir, 2, 0, LocalDate.of(2026, 10, 16)));
        assertEquals(List.of("Household_20261015_2300.MyMoney", "Household_20261016_1000.MyMoney",
                "Household_20261016_1100.MyMoney"), names(), "each day's latest two");
    }

    @Test
    void olderDaysKeepOnlyTheirLast() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 16);
        backup("20261016_0800");
        backup("20261016_1700");          // today: both kept
        backup("20261010_0800");
        backup("20261010_1700");          // 6 days ago: within 7, both kept
        backup("20260901_0800");
        backup("20260901_1200");
        backup("20260901_1700");          // long ago: only the last
        assertEquals(2, Backups.prune(dataFile, dir, 0, 7, today));
        assertEquals(List.of("Household_20260901_1700.MyMoney", "Household_20261010_0800.MyMoney",
                "Household_20261010_1700.MyMoney", "Household_20261016_0800.MyMoney",
                "Household_20261016_1700.MyMoney"), names());
    }

    @Test
    void bothTogether() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 16);
        for (String t : new String[] {"0800", "0900", "1000"}) {
            backup("20261016_" + t);
            backup("20260801_" + t);
        }
        Backups.prune(dataFile, dir, 2, 30, today);
        assertEquals(List.of("Household_20260801_1000.MyMoney", "Household_20261016_0900.MyMoney",
                "Household_20261016_1000.MyMoney"), names());
    }

    @Test
    void offKeepsEverything() throws Exception {
        for (String t : new String[] {"0800", "0900", "1000"}) {
            backup("20200101_" + t);
        }
        assertEquals(0, Backups.prune(dataFile, dir, 0, 0, LocalDate.of(2026, 10, 16)));
        assertEquals(3, names().size());
    }

    @Test
    void aMissingFolderIsJustEmpty() throws Exception {
        assertTrue(Backups.list(dataFile, dir.resolve("nope")).isEmpty());
        assertFalse(Files.exists(dir.resolve("nope")));
    }
}
