package com.martinsmayhem.mymoney;

import java.io.IOException;
import java.util.List;

/**
 * Where the desktop and the phone leave each other files (docs/SYNC-DESIGN.md,
 * "The mailbox"). Paths are '/'-separated and relative to the mailbox's top.
 * Each kind of mailbox (a folder, WebDAV, later others) is one of these.
 */
interface Mailbox {

    /** The names of the files in a folder (not "*.tmp" ones); empty if the folder isn't there. */
    default List<String> list(String folder) throws IOException {
        return list(folder, false);
    }

    /** As list(folder), with the "*.tmp" ones (half-written, or left by a write that failed) too if withTemp. */
    List<String> list(String folder, boolean withTemp) throws IOException;

    /** A file's contents, or null if it isn't there. */
    byte[] read(String path) throws IOException;

    /**
     * Writes a file all at once: to path + ".tmp", then renamed into place,
     * so a reader never sees half of it. Makes its folders as needed.
     */
    void write(String path, byte[] data) throws IOException;

    /** Deletes a file (nothing if it isn't there). */
    void delete(String path) throws IOException;

    /** Deletes a folder and everything in it (nothing if it isn't there). */
    void deleteFolder(String folder) throws IOException;

    /** What it is, for messages: "the folder /mnt/…" or "WebDAV at https://…". */
    String describe();
}
