package com.fileflow.app.domain.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The saved passwords, encrypted with a key the app cannot export.
 *
 * A file manager that can reach an SMB share or an S3 bucket is holding the
 * keys to somebody's server, and a preferences file is the first thing a backup,
 * a bug report or a borrowed phone can read. The key lives in the Android
 * keystore — hardware-backed where the device offers it — and never leaves it,
 * so the stored bytes are only usable by this app on this device.
 *
 * When the keystore cannot be used at all (a device with it disabled, or a
 * JVM), encryption degrades to a marker-prefixed plain value rather than
 * failing: a file manager that cannot save a password is worse than one that
 * says it could not encrypt it.
 */
@Singleton
class SecretBox @Inject constructor() {

    /** @return the value to store, encrypted when that is possible. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val key = key() ?: return PLAIN_PREFIX + plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + bytes
            SEALED_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        }.getOrElse { PLAIN_PREFIX + plain }
    }

    /** @return the recovered secret, or an empty string when it cannot be read. */
    fun open(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith(PLAIN_PREFIX)) return stored.removePrefix(PLAIN_PREFIX)
        if (!stored.startsWith(SEALED_PREFIX)) return stored
        val key = key() ?: return ""
        return runCatching {
            val payload = Base64.decode(stored.removePrefix(SEALED_PREFIX), Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH) return@runCatching ""
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val body = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /**
     * The keystore key, created on first use. Null when this device cannot make
     * one — checked rather than assumed, because `KeyStore.getInstance` throws
     * rather than returning null on the platforms that do not ship it.
     */
    private fun key(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: createKey()
    }.getOrNull()

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fileflow_remote_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_LENGTH = 12
        const val SEALED_PREFIX = "sealed:"
        const val PLAIN_PREFIX = "plain:"
    }
}
