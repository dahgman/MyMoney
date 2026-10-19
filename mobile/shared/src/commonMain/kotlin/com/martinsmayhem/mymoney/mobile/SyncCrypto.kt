package com.martinsmayhem.mymoney.mobile

/** A mailbox file that isn't ours, was changed, or is for another phone or place. */
class BadFileException(message: String) : Exception(message)

/**
 * The sync mailbox's file format, exactly as the desktop's SyncCrypto writes
 * it (docs/SYNC-DESIGN.md, "Encryption"): "MMS1", a 12-byte nonce, then the
 * contents gzip-compressed and encrypted with AES-256-GCM, with the file's
 * path in the mailbox as the additional data.
 */
expect object SyncCrypto {
    fun encrypt(key: ByteArray, path: String, contents: ByteArray): ByteArray

    /** @throws BadFileException */
    fun decrypt(key: ByteArray, path: String, file: ByteArray): ByteArray
}
