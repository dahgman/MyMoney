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
        if (!file.exists()) return@withContext null
        try {
            val bytes = file.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, bytes[0].toInt()))
            val json = c.doFinal(bytes, 1 + bytes[0], bytes.size - 1 - bytes[0]).decodeToString()
            SyncJson.decodeFromString(PhoneState.serializer(), json)
        } catch (e: Exception) {
            null                                   // unreadable (say, the key was reset): start again, unpaired
        }
    }

    override suspend fun save(state: PhoneState) = withContext(Dispatchers.IO) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val sealed = c.doFinal(SyncJson.encodeToString(PhoneState.serializer(), state).encodeToByteArray())
        val iv = c.iv
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + sealed)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        Unit
    }

    private companion object {
        const val ALIAS = "mymoney-state"
    }
}
