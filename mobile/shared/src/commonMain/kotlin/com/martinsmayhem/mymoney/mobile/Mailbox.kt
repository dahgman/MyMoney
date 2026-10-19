package com.martinsmayhem.mymoney.mobile

/** The mailbox couldn't be used: the server answered with an error. */
open class MailboxException(message: String) : Exception(message)

/** The NAS refused the user name or password. */
class MailboxAuthException(message: String) : MailboxException(message)

/**
 * Where the phone and the computer leave each other files (docs/SYNC-DESIGN.md,
 * "The mailbox"): the same interface as the desktop's Mailbox. Paths are
 * '/'-separated, relative to the mailbox's top.
 */
interface Mailbox {
    /** The files in a folder ("*.tmp" ones only if withTemp); empty if it isn't there. */
    suspend fun list(folder: String, withTemp: Boolean = false): List<String>

    /** A file's contents, or null if it isn't there. */
    suspend fun read(path: String): ByteArray?

    /** Writes a file whole (to "<path>.tmp", then moved into place), making folders as needed. */
    suspend fun write(path: String, data: ByteArray)

    /** Deletes a file (nothing if it isn't there). */
    suspend fun delete(path: String)
}
