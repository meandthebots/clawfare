package com.clawfare.db

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * CLWDB002: authenticated, bounded-memory AES-256-GCM record archive.
 *
 * A fixed header contains magic (8), random salt (16) and random nonce
 * prefix (8). Each record has a signed 4-byte plaintext length (0..65536),
 * followed by its independently authenticated AES-GCM ciphertext + 16-byte
 * tag. A zero-length authenticated record terminates the archive; it must
 * be followed by EOF. The per-record nonce is prefix || record counter.
 *
 * GCM authentication is explicitly checked with doFinal before a record is
 * written. CipherInputStream is deliberately not used: some JCE providers
 * can suppress authentication failures while closing or reading that API.
 */
internal object AuthenticatedSqliteArchiveCodec {
    private val magic = "CLWDB002".toByteArray(StandardCharsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val TAG_BYTES = 16
    private const val CHUNK_BYTES = 64 * 1024
    private const val KDF_ITERATIONS = 250_000

    fun encrypt(
        plaintext: Path,
        archive: Path,
        passphrase: CharArray,
    ) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also { SecureRandom().nextBytes(it) }
        val header = magic + salt + noncePrefix
        withKey(passphrase, salt) { key ->
            DataOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).use { output ->
                output.write(header)
                Files.newInputStream(plaintext).use { source ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var counter = 0
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        writeRecord(output, header, noncePrefix, key, counter, buffer.copyOf(read))
                        if (counter == Int.MAX_VALUE) throw IOException("Archive exceeds supported record count.")
                        counter++
                    }
                    // Authenticated end marker means dropping trailing records
                    // is detected. A truncated archive has no valid end marker.
                    writeRecord(output, header, noncePrefix, key, counter, ByteArray(0))
                }
            }
        }
    }

    fun decrypt(
        archive: Path,
        plaintext: Path,
        passphrase: CharArray,
    ) {
        DataInputStream(Files.newInputStream(archive)).use { input ->
            val header = ByteArray(magic.size + SALT_BYTES + NONCE_PREFIX_BYTES)
            input.readFully(header)
            if (!MessageDigest.isEqual(magic, header.copyOfRange(0, magic.size))) {
                throw IOException("Unsupported Clawfare encrypted backup format.")
            }
            val salt = header.copyOfRange(magic.size, magic.size + SALT_BYTES)
            val noncePrefix = header.copyOfRange(magic.size + SALT_BYTES, header.size)
            withKey(passphrase, salt) { key ->
                Files.newOutputStream(plaintext, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    var counter = 0
                    while (true) {
                        val length =
                            try {
                                input.readInt()
                            } catch (_: EOFException) {
                                throw IOException("Truncated encrypted archive (no authenticated end marker).")
                            }
                        if (length !in 0..CHUNK_BYTES) throw IOException("Invalid encrypted record length.")
                        val encrypted = ByteArray(length + TAG_BYTES)
                        input.readFully(encrypted)
                        val cipher = cipher(Cipher.DECRYPT_MODE, key, noncePrefix, counter, header, length)
                        // AEADBadTagException MUST escape; no output is published
                        // unless all tags and the final authenticated marker pass.
                        val clear = cipher.doFinal(encrypted)
                        if (clear.size != length) throw IOException("Invalid authenticated archive record.")
                        if (length == 0) {
                            if (input.read() != -1) throw IOException("Unexpected bytes after encrypted end marker.")
                            break
                        }
                        output.write(clear)
                        clear.fill(0)
                        if (counter == Int.MAX_VALUE) throw IOException("Archive exceeds supported record count.")
                        counter++
                    }
                }
            }
        }
    }

    private fun writeRecord(
        output: DataOutputStream,
        header: ByteArray,
        noncePrefix: ByteArray,
        key: ByteArray,
        counter: Int,
        data: ByteArray,
    ) {
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, noncePrefix, counter, header, data.size)
        output.writeInt(data.size)
        output.write(cipher.doFinal(data))
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        noncePrefix: ByteArray,
        counter: Int,
        header: ByteArray,
        length: Int,
    ): Cipher {
        val nonce = noncePrefix + intBytes(counter)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(header)
            updateAAD(intBytes(counter))
            updateAAD(intBytes(length))
        }
    }

    private fun intBytes(number: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(number).array()

    private inline fun withKey(
        password: CharArray,
        salt: ByteArray,
        action: (ByteArray) -> Unit,
    ) {
        val spec = PBEKeySpec(password, salt, KDF_ITERATIONS, 256)
        val key =
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        try {
            action(key)
        } finally {
            key.fill(0)
        }
    }
}
