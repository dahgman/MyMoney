package com.martinsmayhem.mymoney;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The sync mailbox's file format (docs/SYNC-DESIGN.md, "Encryption"): "MMS1",
 * a 12-byte nonce, then the contents gzip-compressed and encrypted with
 * AES-256-GCM. The file's path in the mailbox is the GCM additional data, so
 * a file moved to another place won't decrypt.
 */
final class SyncCrypto {

    private static final byte[] MAGIC = "MMS1".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A file that isn't ours, was changed, or was encrypted with another key (or for another path). */
    static final class BadFileException extends Exception {
        BadFileException(String message) {
            super(message);
        }
    }

    private SyncCrypto() {
    }

    /** A new random 256-bit key. */
    static byte[] newKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    static String toBase64(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    static byte[] fromBase64(String key) {
        return Base64.getDecoder().decode(key);
    }

    /** Compresses and encrypts contents for the file at path. */
    static byte[] encrypt(byte[] key, String path, byte[] contents) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(path.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = c.doFinal(gzip(contents));
            byte[] out = new byte[MAGIC.length + NONCE_BYTES + sealed.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(nonce, 0, out, MAGIC.length, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, MAGIC.length + NONCE_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM isn't available", e);
        }
    }

    /** Decrypts and uncompresses the file at path. */
    static byte[] decrypt(byte[] key, String path, byte[] file) throws BadFileException {
        if (file == null || file.length < MAGIC.length + NONCE_BYTES + TAG_BITS / 8
                || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC)) {
            throw new BadFileException("not a MyMoney sync file");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, file, MAGIC.length, NONCE_BYTES));
            c.updateAAD(path.getBytes(StandardCharsets.UTF_8));
            return gunzip(c.doFinal(file, MAGIC.length + NONCE_BYTES, file.length - MAGIC.length - NONCE_BYTES));
        } catch (javax.crypto.AEADBadTagException e) {
            throw new BadFileException("it was changed, or is for another phone or place");
        } catch (GeneralSecurityException | IOException e) {
            throw new BadFileException(e.getMessage());
        }
    }

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);           // in memory: can't happen
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return z.readAllBytes();
        }
    }
}
