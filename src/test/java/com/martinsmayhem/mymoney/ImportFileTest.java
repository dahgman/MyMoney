package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where a kept bank download goes: imports/<Account>_import_<date>.<ext>, beside attachments. */
class ImportFileTest {

    @TempDir
    Path data;

    private final LocalDate oct20 = LocalDate.of(2026, 10, 20);

    @Test
    void besideAttachmentsNamedForTheAccountAndDate() {
        assertEquals(data.resolve("imports/Checking_import_2026-10-20.qfx"),
                ImportFileChoice.keptName(data, "Checking", oct20, "Export_123456789.QFX"));
        assertEquals(data.resolve("imports/Visa_ Platinum_import_2026-10-20.ofx"),
                ImportFileChoice.keptName(data, "Visa/ Platinum", oct20, "download.ofx"), "made safe for a file name");
        assertEquals(data.resolve("imports/Checking_import_2026-10-20"),
                ImportFileChoice.keptName(data, "Checking", oct20, "download"), "no extension: none added");
    }

    @Test
    void aSecondOneTheSameDayIsNumbered() throws Exception {
        Path first = ImportFileChoice.keptName(data, "Checking", oct20, "a.qfx");
        Files.createDirectories(first.getParent());
        Files.writeString(first, "x");
        assertEquals(data.resolve("imports/Checking_import_2026-10-20 (2).qfx"),
                ImportFileChoice.keptName(data, "Checking", oct20, "b.qfx"));
    }
}
