package dev.humanagent.llm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for the settings blob, backed by a hardware-bound AES key in the Android
 * Keystore. The plaintext keys the user types (model API keys, speech endpoints) never touch the
 * datastore as readable text: what is stored is a sealed box only this app on this device can
 * open. Rooted-device and forensic-read exposure of endpoint credentials drops accordingly.
 *
 * Format: `v1:<base64(iv)>:<base64(ciphertext+tag)>`. Legacy plaintext is detected by the missing
 * prefix and sealed on the next write; a corrupt box reads as missing settings, the same
 * tolerant path a corrupt plaintext file took before.
 */
object SecretVault {

    private const val KEY_ALIAS = "humanphone-settings"
    private const val PREFIX = "v1:"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    /** True when [stored] is a sealed box rather than legacy plaintext. Pure, so it unit tests. */
    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /**
     * Seals [plain]. Fails open loudly: if the Keystore refuses, the caller keeps the legacy
     * path rather than losing the user's keys.
     */
    fun seal(plain: String): Result<String> = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX +
            android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP) + ":" +
            android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP)
    }

    /** Opens a sealed box, or strips nothing when [stored] is legacy plaintext. */
    fun open(stored: String): Result<String> {
        if (!isSealed(stored)) return Result.success(stored)
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(':')
            require(parts.size == 2) { "malformed sealed settings" }
            val iv = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
            require(iv.size == IV_LENGTH_BYTES) { "bad iv" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}