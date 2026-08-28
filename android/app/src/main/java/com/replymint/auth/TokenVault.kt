package com.replymint.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the auth token with a key that lives in the Android Keystore, so the token in
 * SharedPreferences is ciphertext. (androidx.security-crypto is deprecated; this is the
 * dependency-free equivalent for the one string we need to protect.)
 *
 * Any failure — key rotated by the OS, corrupted blob — decrypts to null, which the app treats
 * as signed-out. Never throws to the caller.
 */
object TokenVault {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "replymint_token_key"
    private const val GCM_TAG_BITS = 128

    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(blob: String): String? = runCatching {
        val (ivPart, dataPart) = blob.split(":", limit = 2).also { require(it.size == 2) }
        val iv = Base64.decode(ivPart, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(Base64.decode(dataPart, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
