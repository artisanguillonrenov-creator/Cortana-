package io.github.artisanguillonrenov.cortana.core.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.sqrt

/**
 * Embedding abstraction (doc 04 §11). The vector index is derived: [fingerprint] identifies the
 * model/version so a change triggers a controlled re-index; Room `memories` stays the source of truth.
 */
interface Embedder {
    val fingerprint: String
    /** Local embedders work offline; remote ones may fail (→ lexical fallback). */
    val local: Boolean
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * Local, offline, dependency-free embedder: signed feature hashing of normalized word stems and
 * character trigrams (384 dimensions, L2-normalized). Captures inflections, typos and accents
 * (préférer / préfère / preferences); it does not know synonyms — a remote or LAN embedding model
 * (e.g. the "Serveur local" preset) can be selected for that.
 */
class HashingEmbedder(private val dim: Int = 384) : Embedder {
    override val fingerprint = "local-hash-v1-$dim"
    override val local = true

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::vector)

    fun vector(text: String): FloatArray {
        val v = FloatArray(dim)
        for (w in TextNorm.words(text)) {
            add(v, "w:" + TextNorm.stem(w), 1.0f)
            val padded = "#$w#"
            for (i in 0..padded.length - 3) add(v, "t:" + padded.substring(i, i + 3), 0.35f)
        }
        return Vectors.normalize(v)
    }

    private fun add(v: FloatArray, feature: String, weight: Float) {
        var h = feature.hashCode() * -0x61c88647 // Fibonacci mixing
        h = h xor (h ushr 15)
        val idx = (h and 0x7fffffff) % dim
        v[idx] += if ((h ushr 31) == 0) weight else -weight
    }
}

object TextNorm {
    private val stop = setOf(
        "le", "la", "les", "l", "de", "du", "des", "d", "un", "une", "et", "a", "au", "aux", "en", "pour", "par", "sur", "dans", "avec",
        "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "ce", "cet", "cette", "ces", "que", "qui", "je", "j", "tu", "il", "elle",
        "nous", "vous", "me", "m", "moi", "te", "se", "s", "y", "ne", "pas", "est", "sont", "the", "to", "of", "and", "or", "is", "it",
    )
    private val suffixes = listOf("ations", "ation", "ements", "ement", "euses", "euse", "eurs", "eur", "ions", "ent", "ez", "er", "es", "e", "s", "x")

    fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    fun words(s: String): List<String> = normalize(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in stop }
    fun stem(t: String): String {
        for (suf in suffixes) if (t.endsWith(suf) && t.length - suf.length >= 4) return t.dropLast(suf.length)
        return t
    }
}

object Vectors {
    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val norm = sqrt(n).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    fun toBytes(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
    fun fromBytes(b: ByteArray): FloatArray { val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.size / 4) { bb.getFloat() } }
}
