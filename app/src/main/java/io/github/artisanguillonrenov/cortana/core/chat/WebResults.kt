package io.github.artisanguillonrenov.cortana.core.chat

import kotlinx.serialization.Serializable

/**
 * One rich web result (image, video or page) returned by `web.search` and kept with the conversation
 * (the tool row's [MessageMeta.webResults]). Every field is external, untrusted data: it is displayed
 * by typed views only (never as HTML or Markdown), and its links pass [WebResults.sanitize] first.
 */
@Serializable
data class WebResultItem(
    /** [WebResults.WEB] | [WebResults.IMAGE] | [WebResults.VIDEO]. */
    val type: String,
    val title: String,
    /** The page the result comes from (opened in the browser). */
    val url: String,
    /** Display name of the source (domain or publisher). */
    val source: String? = null,
    /** Small image shown in the conversation (https only). */
    val thumbnailUrl: String? = null,
    /** Full image or video stream (https only). */
    val mediaUrl: String? = null,
    val snippet: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Int? = null,
    /** Search engine that returned it (DuckDuckGo, Brave Search, SearXNG). */
    val provider: String? = null,
    /** Rendering hints (e.g. `videoKind`: direct | youtube | page). String values only. */
    val metadata: Map<String, String> = emptyMap(),
)

/** Validation and classification of [WebResultItem]s. Pure (no Android, no I/O) — unit-tested. */
object WebResults {
    /** Key of the rich results in a `web.search` ToolResult.data (read by the step runner, stored with the conversation). */
    const val DATA_KEY = "webResults"
    const val WEB = "web"
    const val IMAGE = "image"
    const val VIDEO = "video"
    val MODES = listOf("web", "images", "videos", "mixed")

    const val MAX_ITEMS = 24
    private const val MAX_TITLE = 200
    private const val MAX_SNIPPET = 400
    private const val MAX_URL = 2048

    /** How a video can be shown: [DIRECT] plays inline, [YOUTUBE] and [PAGE] open their page. */
    const val DIRECT = "direct"
    const val YOUTUBE = "youtube"
    const val PAGE = "page"

    private val directExt = Regex("(?i)\\.(mp4|m4v|webm|mkv|3gp|mov|m3u8)$")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com")

    /** A link to open: http(s) with a public host name, no credentials, no control characters. */
    fun link(raw: String?): String? {
        val u = SafeLinks.sanitize(raw) ?: return null
        if (u.length > MAX_URL) return null
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase() ?: return null
        if (localHost(host)) return null
        return u
    }

    /** A resource Cortana itself downloads (thumbnail, image, stream): [link] rules, https only. */
    fun media(raw: String?): String? = link(raw)?.takeIf { it.startsWith("https://", ignoreCase = true) }

    private fun localHost(h: String): Boolean {
        val host = h.trim('[', ']')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || !host.contains('.') && !host.contains(':')) return true
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
        if (v4 != null) {
            val (a, b) = v4.groupValues[1].toInt() to v4.groupValues[2].toInt()
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }
        // IPv6 literals: only global unicast addresses are left to the network guard; the rest is refused here.
        if (host.contains(':')) return host == "::1" || host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("::")
        return false
    }

    fun hostOf(url: String?): String? = runCatching { java.net.URI(url ?: return null).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    fun videoKind(pageUrl: String, mediaUrl: String?): String {
        val m = mediaUrl?.substringBefore('?')?.substringBefore('#')
        if (m != null && directExt.containsMatchIn(m)) return DIRECT
        val host = runCatching { java.net.URI(pageUrl).host?.lowercase() }.getOrNull()
        return if (host != null && host in youtubeHosts) YOUTUBE else PAGE
    }

    /** The YouTube video id of a watch/short/embed/youtu.be link, when it has the expected shape. */
    fun youtubeId(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in youtubeHosts) return null
        val id = when {
            host == "youtu.be" -> uri.path.trim('/').substringBefore('/')
            uri.path.startsWith("/watch") -> uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
            uri.path.startsWith("/shorts/") || uri.path.startsWith("/embed/") -> uri.path.split('/').getOrNull(2)
            else -> null
        }
        return id?.takeIf { Regex("^[A-Za-z0-9_-]{11}$").matches(it) }
    }

    private fun clean(s: String?, max: Int): String? =
        s?.replace(Regex("<[^>]*>"), " ")?.filterNot { it.isISOControl() && it != '\n' }?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    /**
     * Keeps only well-formed items: known type, safe page link, https media, bounded text and sizes.
     * An image without a displayable picture, or a result without a page, is dropped. Duplicates
     * (same type and page/media) are merged; at most [MAX_ITEMS] are kept.
     */
    fun sanitize(items: List<WebResultItem>): List<WebResultItem> = items.mapNotNull { i ->
        val type = i.type.lowercase().takeIf { it == WEB || it == IMAGE || it == VIDEO } ?: return@mapNotNull null
        val url = link(i.url) ?: return@mapNotNull null
        val thumb = media(i.thumbnailUrl)
        val mediaUrl = media(i.mediaUrl)
        if (type == IMAGE && thumb == null && mediaUrl == null) return@mapNotNull null
        val meta = i.metadata.entries.take(8).associate { (k, v) -> k.take(40) to v.take(200) }.toMutableMap()
        if (type == VIDEO) meta["videoKind"] = videoKind(url, mediaUrl)
        WebResultItem(
            type = type,
            title = clean(i.title, MAX_TITLE) ?: hostOf(url) ?: "Résultat",
            url = url,
            source = clean(i.source, 80) ?: hostOf(url),
            thumbnailUrl = thumb,
            mediaUrl = mediaUrl,
            snippet = clean(i.snippet, MAX_SNIPPET),
            width = i.width?.takeIf { it in 1..20_000 },
            height = i.height?.takeIf { it in 1..20_000 },
            durationSeconds = i.durationSeconds?.takeIf { it in 1..86_400 },
            provider = clean(i.provider, 40),
            metadata = meta,
        )
    }.distinctBy { it.type + "|" + (it.mediaUrl ?: it.url) }.take(MAX_ITEMS)

    /** "3:07", "1:02:05" — for durations given as seconds, "mm:ss", "hh:mm:ss" or ISO-8601 ("PT3M7S"). */
    fun parseDuration(raw: String?): Int? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        s.toIntOrNull()?.let { return it.takeIf { v -> v > 0 } }
        Regex("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$").matchEntire(s)?.let { m ->
            return (m.groupValues[1].toIntOrNull() ?: 0) * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        }
        Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val t = (m.groupValues[1].toIntOrNull() ?: 0) * 3600 + (m.groupValues[2].toIntOrNull() ?: 0) * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
            return t.takeIf { it > 0 }
        }
        return null
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** The text the model reads (numbered, typed): it may cite or describe the results, never render them. */
    fun forModel(query: String, provider: String, mode: String, items: List<WebResultItem>): String = buildString {
        append("Résultats « ").append(query).append(" » (").append(provider).append(", mode ").append(mode).append(") — affichés au propriétaire sous forme de ")
        append(listOf(IMAGE to "images", VIDEO to "vidéos", WEB to "cartes web").filter { (t, _) -> items.any { it.type == t } }.joinToString(", ") { it.second })
        append(". Ne recopie pas les adresses d'images ou de vidéos dans ta réponse.\n")
        // Same layout as a plain web search ("N. title", url, one detail line): citations keep working.
        items.forEachIndexed { i, r ->
            append(i + 1).append(". ").append(when (r.type) { IMAGE -> "Image : "; VIDEO -> "Vidéo : "; else -> "" }).append(r.title).append('\n')
            append("   ").append(r.url).append('\n')
            val detail = listOfNotNull(
                r.source, r.durationSeconds?.let { formatDuration(it) }, if (r.width != null && r.height != null) "${r.width}×${r.height}" else null, r.snippet,
            ).joinToString(" · ")
            append("   ").append(detail).append('\n')
        }
    }
}
