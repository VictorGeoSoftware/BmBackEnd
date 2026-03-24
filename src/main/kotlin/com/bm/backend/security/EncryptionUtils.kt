package com.bm.backend.security

import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionUtils {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private var secretKey: SecretKey? = null

    fun init(base64Key: String) {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        require(keyBytes.size == 32) { "Encryption key must be 256 bits (32 bytes). Got ${keyBytes.size} bytes." }
        secretKey = SecretKeySpec(keyBytes, "AES")
    }

    fun isInitialized(): Boolean = secretKey != null

    fun encrypt(plaintext: String): String {
        val key = secretKey ?: throw IllegalStateException("EncryptionUtils not initialized. Call init() first.")

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend IV to ciphertext: [IV (12 bytes) | ciphertext+tag]
        val combined = iv + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(encrypted: String): String {
        val key = secretKey ?: throw IllegalStateException("EncryptionUtils not initialized. Call init() first.")

        val combined = Base64.getDecoder().decode(encrypted)
        require(combined.size > GCM_IV_LENGTH) { "Invalid encrypted data" }

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    fun encryptNullable(value: String?): String? = value?.let { encrypt(it) }

    fun generateKey(): String {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        return Base64.getEncoder().encodeToString(keyBytes)
    }
}
