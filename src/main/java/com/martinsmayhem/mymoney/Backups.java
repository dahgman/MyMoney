package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Database backups: what they're called, and which old ones are kept.
 *
 * A backup is an encrypted copy of the data file (the database) only, named
 * for it and when it was made: Household_20261016_0845.MyMoney (a second in
 * the same minute: ..._0845_2.MyMoney). The attachments folder beside the
 * data file isn't in it.
 *
 * Retention only ever looks at this file's own backups (by that name) in the
 * backup folder; nothing else there is touched.
 * <ul>
 * <li>keepPerDay (0: all): each day keeps only its latest that many.</li>
 * <li>thinAfterDays (0: never): days longer ago than that keep only their
 *     latest one.</li>
 * </ul>
 */
final class Backups {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private Backups() {
    }

    /** A backup of dataFile made at when, in folder: "<name>_YYYYMMDD_HHmm.MyMoney", or "_2", "_3"... if taken. */
    static Path target(Path dataFile, Path folder, LocalDateTime when) {
        String base = baseName(dataFile);
        String ext = "." + Database.EXTENSION;
        String stamp = when.format(STAMP);
        Path p = folder.resolve(base + "_" + stamp + ext);
        for (int n = 2; Files.exists(p); n++) {
            p = folder.resolve(base + "_" + stamp + "_" + n + ext);
        }
        return p;
    }

    /** One of a file's backups, and when it was made (from its name). */
    record Backup(Path file, LocalDateTime made) {
    }

    /** The data file's backups in folder (by name), oldest first. */
    static List<Backup> list(Path dataFile, Path folder) throws IOException {
        Pattern named = Pattern.compile(Pattern.quote(baseName(dataFile)) + "_(\\d{8}_\\d{4})(?:_(\\d+))?\\."
                + Pattern.quote(Database.EXTENSION), Pattern.CASE_INSENSITIVE);
        List<Backup> list = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return list;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Matcher m = named.matcher(p.getFileName().toString());
                if (Files.isRegularFile(p) && m.matches()) {
                    try {
                        LocalDateTime made = LocalDateTime.parse(m.group(1), STAMP);
                        int n = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                        list.add(new Backup(p, made.plusNanos(n)));        // _2 comes after the first that minute
                    } catch (RuntimeException ignored) {
                        // not a real date: not one of ours
                    }
                }
            }
        }
        list.sort(Comparator.comparing(Backup::made));
        return list;
    }

    /**
     * The backups retention would remove (oldest first): beyond keepPerDay in
     * a day (0: none), and all but each day's last for days more than
     * thinAfterDays before today (0: none).
     */
    static List<Backup> toRemove(List<Backup> backups, int keepPerDay, int thinAfterDays, LocalDate today) {
        Map<LocalDate, List<Backup>> byDay = new TreeMap<>();
        for (Backup b : backups) {
            byDay.computeIfAbsent(b.made().toLocalDate(), d -> new ArrayList<>()).add(b);
        }
        List<Backup> remove = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Backup>> day : byDay.entrySet()) {
            List<Backup> ofDay = day.getValue();               // oldest first
            int keep = ofDay.size();
            if (keepPerDay > 0) {
                keep = Math.min(keep, keepPerDay);
            }
            if (thinAfterDays > 0 && day.getKey().isBefore(today.minusDays(thinAfterDays))) {
                keep = Math.min(keep, 1);
            }
            remove.addAll(ofDay.subList(0, ofDay.size() - keep));    // the day's latest are kept
        }
        remove.sort(Comparator.comparing(Backup::made));
        return remove;
    }

    /** Applies retention in folder: deletes the backups it says to. Returns how many were deleted. */
    static int prune(Path dataFile, Path folder, int keepPerDay, int thinAfterDays, LocalDate today)
            throws IOException {
        if (keepPerDay <= 0 && thinAfterDays <= 0) {
            return 0;                                          // keep everything
        }
        int deleted = 0;
        for (Backup b : toRemove(list(dataFile, folder), keepPerDay, thinAfterDays, today)) {
            Files.deleteIfExists(b.file());
            deleted++;
        }
        return deleted;
    }

    private static String baseName(Path dataFile) {
        String name = dataFile.getFileName().toString();
        String ext = "." + Database.EXTENSION;
        return name.toLowerCase().endsWith(ext.toLowerCase()) ? name.substring(0, name.length() - ext.length()) : name;
    }

    /** What to tell people about attachments, wherever backups come up. */
    static String attachmentsNote(Path dataFolder) {
        return "This backs up the database only: your accounts and transactions. Attachments and statements"
                + " (the attachments folder) aren't in it. To back those up too, back up the whole folder\n"
                + "    " + dataFolder + "\n"
                + "with a backup tool, such as FreeFileSync (free; Linux, Windows and macOS), or your system's own:"
                + " Déjà Dup on Linux, File History on Windows, Time Machine on macOS.";
    }
}
