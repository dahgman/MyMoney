package com.martinsmayhem.mymoney.mobile.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.martinsmayhem.mymoney.mobile.PhoneState
import com.martinsmayhem.mymoney.mobile.PhoneStore
import com.martinsmayhem.mymoney.mobile.SyncJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the phone's state (pairing, the NAS password, the last snapshot,
 * pending changes) in one file in the app's private storage, encrypted with
 * AES-GCM under a key held in the Android Keystore: it never leaves the
 * phone's secure hardware, and the file is useless anywhere else.
 */
class AndroidStore(context: Context) : PhoneStore {

    private val file = File(context.filesDir, "state.mms")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    override suspend fun load(): PhoneState? = withContext(Dispatchers.IO) {
        val bytes = read(file) ?: return@withContext null
        try {
            SyncJson.decodeFromString(PhoneState.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            null                                   // unreadable (say, the key was reset): start again, unpaired
        }
    }

    override suspend fun save(state: PhoneState) = withContext(Dispatchers.IO) {
        write(file, SyncJson.encodeToString(PhoneState.serializer(), state).encodeToByteArray())
    }

    // Receipt photos and thumbnails: one encrypted file each, in receipts/.
    private val blobs = File(context.filesDir, "receipts")

    private fun blob(name: String) = File(blobs, name.replace(Regex("[^A-Za-z0-9-]"), "_") + ".mms")

    override suspend fun saveBlob(name: String, data: ByteArray) = withContext(Dispatchers.IO) {
        blobs.mkdirs()
        write(blob(name), data)
    }

    override suspend fun loadBlob(name: String): ByteArray? = withContext(Dispatchers.IO) { read(blob(name)) }

    override suspend fun deleteBlob(name: String) = withContext(Dispatchers.IO) {
        blob(name).delete()
        Unit
    }

    /** A file's contents, decrypted; null if it isn't there or can't be read. */
    private fun read(f: File): ByteArray? {
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, bytes[0].toInt()))
            c.doFinal(bytes, 1 + bytes[0], bytes.size - 1 - bytes[0])
        } catch (e: Exception) {
            null
        }
    }

    /** Writes a file encrypted (its IV first), whole: to a .tmp, then renamed into place. */
    private fun write(f: File, data: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val sealed = c.doFinal(data)
        val iv = c.iv
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + sealed)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private companion object {
        const val ALIAS = "mymoney-state"
    }
}
