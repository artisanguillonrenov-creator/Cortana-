package io.github.artisanguillonrenov.cortana.core.worker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.artisanguillonrenov.cortana.contracts.WorkerProtocol
import io.github.artisanguillonrenov.cortana.util.CLog
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The tablet's identity towards paired workers: an EC P-256 key pair. Normally generated inside the
 * Android Keystore (the private key never leaves secure hardware); if the Keystore cannot create
 * EC signing keys, a software key in the app-private directory is used and reported as such.
 */
class DeviceKey(context: Context) {
    val hardwareBacked: Boolean
    private val privateKey: PrivateKey
    val publicKey: PublicKey

    init {
        val keystore = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build())
                }.generateKeyPair()
            }
            (ks.getKey(ALIAS, null) as PrivateKey) to ks.getCertificate(ALIAS).publicKey
        }.onFailure { CLog.w("Android Keystore EC key unavailable, using a software device key", it) }.getOrNull()
        if (keystore != null) {
            hardwareBacked = true; privateKey = keystore.first; publicKey = keystore.second
        } else {
            hardwareBacked = false
            val f = File(context.filesDir, "device-key.pk8")
            val kf = KeyFactory.getInstance("EC")
            if (!f.isFile) {
                val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
                f.writeBytes(kp.private.encoded); File(context.filesDir, "device-key.pub").writeBytes(kp.public.encoded)
            }
            privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(f.readBytes()))
            publicKey = kf.generatePublic(X509EncodedKeySpec(File(context.filesDir, "device-key.pub").readBytes()))
        }
    }

    val publicKeyBase64: String get() = Base64.getEncoder().encodeToString(publicKey.encoded)

    fun sign(message: String): String =
        Base64.getEncoder().encodeToString(Signature.getInstance(WorkerProtocol.SIGNATURE_ALGORITHM).apply { initSign(privateKey); update(message.toByteArray()) }.sign())

    companion object { const val ALIAS = "cortana_device_key_v1" }
}
