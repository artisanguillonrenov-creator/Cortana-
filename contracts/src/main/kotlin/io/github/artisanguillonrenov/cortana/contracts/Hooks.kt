package io.github.artisanguillonrenov.cortana.contracts

import kotlinx.serialization.Serializable
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhooks (blueprint §37.2), shared by the tablet and the worker. Inbound: a paired worker hosts
 * the public endpoint `POST /hooks/{hookId}`, checks the sender's signature, freshness, replays and
 * rate, and queues the event for the tablet that registered the hook. Outbound: Cortana signs what
 * it sends with the same scheme.
 */
@Serializable
data class HookRegistration(val secret: String, val maxPerMinute: Int = 30, val label: String = "")

@Serializable
data class HookInfo(val hookId: String, val path: String)

@Serializable
data class HookEvent(
    val seq: Long,
    val hookId: String,
    val name: String,
    val receivedAt: Long,
    /** Only a few descriptive headers (content type, event name, delivery id, user agent). */
    val headers: Map<String, String> = emptyMap(),
    val body: String,
)

@Serializable
data class HookEvents(val events: List<HookEvent>, val next: Long)

object WebhookSignature {
    const val HEADER = "X-Cortana-Signature"
    const val TIMESTAMP = "X-Cortana-Timestamp"
    const val GITHUB = "X-Hub-Signature-256"
    const val MAX_SKEW_SECONDS = 300L
    const val MAX_BODY = 256 * 1024

    fun hmacHex(secret: ByteArray, data: ByteArray): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(data).joinToString("") { "%02x".format(it) }

    /** Cortana scheme: HMAC-SHA256 over "timestamp.body". */
    fun sign(secret: ByteArray, timestamp: Long, body: ByteArray): String = "sha256=" + hmacHex(secret, "$timestamp.".toByteArray() + body)

    private fun same(a: String, b: String) = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /**
     * Null when valid, otherwise the reason. Accepts the Cortana scheme (signature + fresh
     * timestamp) or GitHub's (signature over the body; replays are caught by the delivery id).
     */
    fun verify(secret: ByteArray, header: (String) -> String?, body: ByteArray, nowSeconds: Long): String? {
        header(HEADER)?.let { sig ->
            val ts = header(TIMESTAMP)?.trim()?.toLongOrNull() ?: return "horodatage manquant"
            if (kotlin.math.abs(nowSeconds - ts) > MAX_SKEW_SECONDS) return "horodatage trop ancien ou futur"
            return if (same(sig.trim(), sign(secret, ts, body))) null else "signature invalide"
        }
        header(GITHUB)?.let { sig -> return if (same(sig.trim(), "sha256=" + hmacHex(secret, body))) null else "signature invalide" }
        return "signature manquante"
    }
}
