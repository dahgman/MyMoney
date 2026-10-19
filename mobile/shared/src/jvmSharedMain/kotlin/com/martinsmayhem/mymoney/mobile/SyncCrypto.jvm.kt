package com.martinsmayhem.mymoney.mobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object SyncCrypto {
    private val MAGIC = "MMS1".encodeToByteArray()
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    actual fun encrypt(key: ByteArray, path: String, contents: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(path.encodeToByteArray())
        return MAGIC + nonce + c.doFinal(gzip(contents))
    }

    actual fun decrypt(key: ByteArray, path: String, file: ByteArray): ByteArray {
        if (file.size < MAGIC.size + NONCE_BYTES + TAG_BITS / 8 || !file.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            throw BadFileException("not a MyMoney sync file")
        }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, file, MAGIC.size, NONCE_BYTES))
            c.updateAAD(path.encodeToByteArray())
            return gunzip(c.doFinal(file, MAGIC.size + NONCE_BYTES, file.size - MAGIC.size - NONCE_BYTES))
        } catch (e: AEADBadTagException) {
            throw BadFileException("it was changed, or is for another phone or place")
        } catch (e: GeneralSecurityException) {
            throw BadFileException(e.message ?: "it couldn't be decrypted")
        } catch (e: java.io.IOException) {
            throw BadFileException(e.message ?: "it couldn't be uncompressed")
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
