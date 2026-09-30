package io.github.artisanguillonrenov.cortana.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.artisanguillonrenov.cortana.util.CLog
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed secret store (§9.5). Values are AES-256-GCM encrypted with a key that never
 * leaves the Android Keystore, and persisted in a private SharedPreferences file that is excluded
 * from backups. Callers only ever hold a *handle* ("secret:<uuid>"); the model never sees values.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("cortana_secrets", Context.MODE_PRIVATE)

    init {
        // Register every stored value with the redactor so it can never leak into logs/context.
        runCatching { prefs.all.keys.forEach { h -> get(h)?.let(Redactor::register) } }
            .onFailure { CLog.w("secret store warm-up failed", it) }
    }

    fun newHandle(): String = "secret:" + Ids.new()

    fun put(handle: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(1 + iv.size + ct.size)
        blob[0] = iv.size.toByte()
        System.arraycopy(iv, 0, blob, 1, iv.size)
        System.arraycopy(ct, 0, blob, 1 + iv.size, ct.size)
        get(handle)?.let(Redactor::unregister)
        prefs.edit().putString(handle, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        Redactor.register(value)
    }

    fun get(handle: String?): String? {
        if (handle == null) return null
        val b64 = prefs.getString(handle, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val ivLen = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivLen)
            val ct = blob.copyOfRange(1 + ivLen, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            CLog.w("secret decrypt failed for handle", t)
            null
        }
    }

    fun has(handle: String?): Boolean = handle != null && prefs.contains(handle)

    /** Every handle stored (never the values), for the inventory. */
    fun handles(): Set<String> = prefs.all.keys.filter { it.startsWith("secret:") }.toSet()

    fun remove(handle: String?) {
        if (handle == null) return
        get(handle)?.let(Redactor::unregister)
        prefs.edit().remove(handle).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "cortana_secret_store_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
