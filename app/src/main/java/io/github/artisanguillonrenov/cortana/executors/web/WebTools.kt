package io.github.artisanguillonrenov.cortana.executors.web

import io.github.artisanguillonrenov.cortana.core.chat.WebResultItem
import io.github.artisanguillonrenov.cortana.core.chat.WebResults
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.model.await
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** §16 — blocks private, loopback, link-local (and CGNAT/ULA) addresses unless explicitly allowed. */
object SsrfGuard {
    fun isBlocked(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress) return true
        val b = addr.address
        if (addr is Inet4Address) {
            val a0 = b[0].toInt() and 0xff
            val a1 = b[1].toInt() and 0xff
            if (a0 == 100 && a1 in 64..127) return true // CGNAT
            if (a0 == 0) return true
            if (a0 == 169 && a1 == 254) return true
        }
        if (addr is Inet6Address) {
            val first = b[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return true // fc00::/7 unique local
            embeddedIpv4(b)?.let { return isBlocked(it) }
        }
        return false
    }

    /** The IPv4 address carried by ::a.b.c.d, ::ffff:a.b.c.d and the NAT64 prefix 64:ff9b::/96. */
    private fun embeddedIpv4(b: ByteArray): InetAddress? {
        val zeros = (0 until 10).all { b[it].toInt() == 0 }
        val mapped = zeros && ((b[10].toInt() == 0 && b[11].toInt() == 0) || (b[10].toInt() and 0xff == 0xff && b[11].toInt() and 0xff == 0xff))
        val nat64 = b[0].toInt() == 0 && b[1].toInt() == 0x64 && b[2].toInt() and 0xff == 0xff && b[3].toInt() and 0xff == 0x9b && (4 until 12).all { b[it].toInt() == 0 }
        return if (mapped || nat64) InetAddress.getByAddress(b.copyOfRange(12, 16)) else null
    }

    fun isBlockedLiteral(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true
        val looksIp = h.matches(Regex("^[0-9.]+$")) || h.contains(':')
        if (!looksIp) return false
        return runCatching { isBlocked(InetAddress.getByName(h)) }.getOrDefault(true)
    }

    fun dns(allowPrivate: () -> Boolean): Dns = dnsFor { allowPrivate() }

    /**
     * [allowPrivate] receives the host name, so an owner-configured LAN service can be exempted.
     * DNS rebinding: the addresses returned here are the very ones OkHttp connects to, on every
     * connection — there is no earlier resolution to trust — so a name answering a public address,
     * then a private one (or both at once), never reaches the private address.
     */
    fun dnsFor(upstream: Dns = Dns.SYSTEM, allowPrivate: (String) -> Boolean): Dns = GuardedDns(upstream, allowPrivate)

    class GuardedDns(private val upstream: Dns, private val allowPrivate: (String) -> Boolean) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = upstream.lookup(hostname)
            if (allowPrivate(hostname)) return all
            val ok = all.filterNot(::isBlocked)
            if (ok.isEmpty()) throw UnknownHostException("Adresse privée ou locale bloquée (protection SSRF) : $hostname")
            return ok
        }
    }

    class Blocked(host: String) : IOException("Adresse privée ou locale bloquée (protection SSRF) : $host")

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /**
     * Follows redirects itself (the client must use `followRedirects(false)`) so that every hop is
     * checked by [allowed] *before* a connection is opened: OkHttp connects to IP literals without
     * consulting [Dns], so a public page redirecting to `http://169.254.169.254/` would otherwise
     * reach it. HTTPS → HTTP downgrades are refused; credentials are not carried across hosts.
     */
    fun redirectGuard(allowed: (HttpUrl) -> Boolean, maxHops: Int = 10): Interceptor = Interceptor { chain -> follow(chain, allowed, maxHops) }

    private fun follow(chain: Interceptor.Chain, allowed: (HttpUrl) -> Boolean, maxHops: Int): Response {
        var req = chain.request()
        var hops = 0
        while (true) {
            if (!allowed(req.url)) throw Blocked(req.url.host)
            val resp = chain.proceed(req)
            val next = resp.header("Location")?.takeIf { resp.code in REDIRECTS }?.let { resp.request.url.resolve(it) } ?: return resp
            resp.close()
            if (++hops > maxHops) throw IOException("Trop de redirections (> $maxHops)")
            if (req.url.isHttps && !next.isHttps) throw IOException("Redirection de https vers http refusée : ${next.host}")
            val b = req.newBuilder().url(next)
            if (resp.code == 303 || (resp.code in 301..302 && req.method == "POST")) b.method("GET", null).removeHeader("Content-Type").removeHeader("Content-Length")
            if (next.host != req.url.host) b.removeHeader("Authorization")
            req = b.build()
        }
    }
}

object HtmlText {
    private val dropBlocks = Regex("(?is)<(script|style|noscript|svg|template|iframe)[^>]*>.*?</\\1>")
    private val comments = Regex("(?s)<!--.*?-->")
    private val breaks = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/header|/footer|/blockquote|/pre)\\b[^>]*>")
    private val tags = Regex("(?s)<[^>]+>")
    private val title = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val links = Regex("(?is)<a\\s[^>]*href\\s*=\\s*[\"']([^\"'#]+)[\"'][^>]*>(.*?)</a>")

    fun decodeEntities(s: String): String {
        var out = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&rsquo;", "’").replace("&laquo;", "«").replace("&raquo;", "»")
            .replace("&eacute;", "é").replace("&egrave;", "è").replace("&agrave;", "à").replace("&ccedil;", "ç").replace("&ecirc;", "ê")
        out = Regex("&#(\\d+);").replace(out) { m -> m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m -> m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value }
        return out
    }

    fun title(html: String): String? = title.find(html)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }

    fun readable(html: String): String {
        var s = comments.replace(html, " ")
        s = dropBlocks.replace(s, " ")
        s = Regex("(?is)<head[^>]*>.*?</head>").replace(s, " ")
        s = breaks.replace(s, "\n")
        s = tags.replace(s, " ")
        s = decodeEntities(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun links(html: String, base: HttpUrl, max: Int = 25): List<Pair<String, String>> =
        links.findAll(html).mapNotNull { m ->
            val href = base.resolve(decodeEntities(m.groupValues[1]).trim()) ?: return@mapNotNull null
            if (href.scheme != "http" && href.scheme != "https") return@mapNotNull null
            val text = decodeEntities(tags.replace(m.groupValues[2], " ")).replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) null else text.take(80) to href.toString()
        }.distinctBy { it.second }.take(max).toList()
}

data class SearchHit(val title: String, val url: String, val snippet: String, val thumbnailUrl: String? = null, val source: String? = null)

interface SearchProvider {
    val name: String
    suspend fun search(query: String, count: Int): List<SearchHit>
    /** Images (mode "images"); a provider without an image search says so. */
    suspend fun images(query: String, count: Int): List<WebResultItem> = throw UnsupportedOperationException("$name ne fournit pas de recherche d'images")
    /** Videos (mode "videos"). */
    suspend fun videos(query: String, count: Int): List<WebResultItem> = throw UnsupportedOperationException("$name ne fournit pas de recherche de vidéos")

    /** One search in [mode] (web | images | videos | mixed), as unvalidated rich results. */
    suspend fun rich(query: String, mode: String, count: Int): List<WebResultItem> = when (mode) {
        "images" -> images(query, count)
        "videos" -> videos(query, count)
        "mixed" -> {
            // The page results come first; a media search that fails only leaves its part out.
            val web = search(query, count).map { it.toItem(name) }
            web + runCatching { images(query, minOf(count, 8)) }.getOrDefault(emptyList()) + runCatching { videos(query, minOf(count, 4)) }.getOrDefault(emptyList())
        }
        else -> search(query, count).map { it.toItem(name) }
    }
}

fun SearchHit.toItem(provider: String) = WebResultItem(WebResults.WEB, title, url, source, thumbnailUrl, null, snippet, provider = provider)

/** Small JSON helpers for search API responses (missing or mistyped fields are null). */
private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
private fun JsonObject.a(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

class WebExecutor(baseClient: OkHttpClient, private val settings: SettingsRepository, private val secret: (String?) -> String?) {
    private val ua = "Mozilla/5.0 (Linux; Android 15; SM-X130) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 Cortana/1.2"
    val client: OkHttpClient = baseClient.newBuilder()
        .dns(SsrfGuard.dnsFor(allowPrivate = ::privateAllowed))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .addInterceptor(SsrfGuard.redirectGuard(::allowed))
        .build()

    /**
     * Local-network addresses are refused unless the owner allowed them, or the host is the SearXNG
     * instance the owner configured (commonly on the LAN).
     */
    private fun privateAllowed(host: String): Boolean {
        val s = settings.current
        return s.allowPrivateNetworkFetch || (s.searchProvider == "searxng" && s.searchBaseUrl?.toHttpUrlOrNull()?.host.equals(host, ignoreCase = true))
    }

    /** The SSRF rule applied to every URL and redirect hop (web tools, browser, research). */
    fun allowed(u: HttpUrl): Boolean = privateAllowed(u.host) || !SsrfGuard.isBlockedLiteral(u.host)

    private fun checkUrl(url: String): HttpUrl {
        val u = url.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("URL invalide : $url")
        if (!allowed(u)) {
            throw IllegalArgumentException("Adresse privée ou locale bloquée (protection SSRF) : ${u.host}")
        }
        return u
    }

    suspend fun fetch(url: String, maxChars: Int): String {
        val u = checkUrl(url)
        val resp = client.newCall(Request.Builder().url(u).header("User-Agent", ua).header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.7").get().build()).await()
        resp.use { r ->
            val finalUrl = r.request.url
            if (!r.isSuccessful) return "HTTP ${r.code} pour $finalUrl"
            val type = r.header("Content-Type").orEmpty()
            val body = r.body ?: return "Réponse vide"
            val source = body.source()
            source.request(2_000_000)
            val raw = source.buffer.snapshot(minOf(source.buffer.size, 2_000_000L).toInt()).utf8()
            return buildString {
                appendLine("URL : $finalUrl")
                if (type.contains("html", ignoreCase = true) || raw.trimStart().startsWith("<")) {
                    HtmlText.title(raw)?.let { appendLine("Titre : $it") }
                    appendLine()
                    appendLine(HtmlText.readable(raw).take(maxChars))
                    val ls = HtmlText.links(raw, finalUrl)
                    if (ls.isNotEmpty()) {
                        appendLine("\nLiens :")
                        ls.forEach { (t, h) -> appendLine("- $t → $h") }
                    }
                } else if (type.contains("json") || type.startsWith("text/")) {
                    appendLine(raw.take(maxChars))
                } else {
                    appendLine("Contenu non textuel ($type), ${body.contentLength()} octets.")
                }
            }
        }
    }

    fun searchProvider(): SearchProvider {
        val s = settings.current
        return when (s.searchProvider) {
            "brave" -> BraveSearch(client, secret(s.searchKeyHandle))
            "searxng" -> SearxngSearch(client, s.searchBaseUrl ?: "")
            else -> DuckDuckGoHtmlSearch(client, ua)
        }
    }

    private class DuckDuckGoHtmlSearch(private val client: OkHttpClient, private val ua: String) : SearchProvider {
        override val name = "DuckDuckGo"
        private val resultRe = Regex("(?is)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
        private val snippetRe = Regex("(?is)<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>")

        override suspend fun search(query: String, count: Int): List<SearchHit> {
            val url = "https://html.duckduckgo.com/html/".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("q", query).addQueryParameter("kl", "fr-fr").build()
            val html = client.newCall(Request.Builder().url(url).header("User-Agent", ua).get().build()).await().use { it.body?.string().orEmpty() }
            val snippets = snippetRe.findAll(html).map { HtmlText.decodeEntities(it.groupValues[1].replace(Regex("<[^>]+>"), "")).trim() }.toList()
            return resultRe.findAll(html).mapIndexed { i, m ->
                var href = HtmlText.decodeEntities(m.groupValues[1])
                if (href.startsWith("//")) href = "https:$href"
                href.toHttpUrlOrNull()?.queryParameter("uddg")?.let { href = URLDecoder.decode(it, "UTF-8") }
                SearchHit(HtmlText.decodeEntities(m.groupValues[2].replace(Regex("<[^>]+>"), "")).trim(), href, snippets.getOrElse(i) { "" })
            }.filter { !it.url.contains("duckduckgo.com/y.js") }.take(count).toList()
        }

        private suspend fun vqd(query: String): String {
            val url = "https://duckduckgo.com/".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("q", query).build()
            val html = client.newCall(Request.Builder().url(url).header("User-Agent", ua).get().build()).await().use { it.body?.string().orEmpty() }
            return Regex("vqd=[\"']?([0-9-]+)").find(html)?.groupValues?.get(1) ?: throw IllegalStateException("jeton DuckDuckGo introuvable")
        }

        private suspend fun json(path: String, query: String): List<JsonObject> {
            val url = "https://duckduckgo.com/$path".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("l", "fr-fr").addQueryParameter("o", "json")
                .addQueryParameter("q", query).addQueryParameter("vqd", vqd(query)).addQueryParameter("p", "1").build()
            val body = client.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Referer", "https://duckduckgo.com/").get().build()).await()
                .use { if (!it.isSuccessful) throw IllegalStateException("DuckDuckGo HTTP ${it.code}"); it.body?.string().orEmpty() }
            return AppJson.parseToJsonElement(body).jsonObject.a("results")
        }

        override suspend fun images(query: String, count: Int): List<WebResultItem> = json("i.js", query).mapNotNull { o ->
            WebResultItem(WebResults.IMAGE, o.s("title").orEmpty(), o.s("url") ?: return@mapNotNull null, o.s("source"), o.s("thumbnail"), o.s("image"),
                null, o.i("width"), o.i("height"), provider = name)
        }.take(count)

        override suspend fun videos(query: String, count: Int): List<WebResultItem> = json("v.js", query).mapNotNull { o ->
            val img = o.o("images")
            WebResultItem(WebResults.VIDEO, o.s("title").orEmpty(), o.s("content") ?: return@mapNotNull null, o.s("publisher") ?: o.s("provider"),
                img?.s("medium") ?: img?.s("large") ?: img?.s("small"), null, o.s("description"), durationSeconds = WebResults.parseDuration(o.s("duration")), provider = name)
        }.take(count)
    }

    private class BraveSearch(private val client: OkHttpClient, private val key: String?) : SearchProvider {
        override val name = "Brave Search"
        override suspend fun search(query: String, count: Int): List<SearchHit> {
            if (key.isNullOrBlank()) throw IllegalStateException("Clé Brave Search manquante (Réglages → Recherche web)")
            val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", query).addQueryParameter("count", count.toString()).addQueryParameter("search_lang", "fr").build()
            val body = client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).get().build())
                .await().use { if (!it.isSuccessful) throw IllegalStateException("Brave HTTP ${it.code}"); it.body?.string().orEmpty() }
            val results = (AppJson.parseToJsonElement(body).jsonObject["web"] as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
            return results.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                SearchHit(
                    HtmlText.decodeEntities((o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
                    (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    HtmlText.decodeEntities((o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().replace(Regex("<[^>]+>"), "")),
                    o.o("thumbnail")?.s("src"), o.o("profile")?.s("name") ?: o.o("meta_url")?.s("hostname"),
                )
            }.take(count)
        }

        private suspend fun api(kind: String, query: String, count: Int): List<JsonObject> {
            if (key.isNullOrBlank()) throw IllegalStateException("Clé Brave Search manquante (Réglages → Recherche web)")
            val url = "https://api.search.brave.com/res/v1/$kind/search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", query).addQueryParameter("count", count.toString()).addQueryParameter("search_lang", "fr").build()
            val body = client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).get().build())
                .await().use { if (!it.isSuccessful) throw IllegalStateException("Brave HTTP ${it.code}"); it.body?.string().orEmpty() }
            return AppJson.parseToJsonElement(body).jsonObject.a("results")
        }

        override suspend fun images(query: String, count: Int): List<WebResultItem> = api("images", query, count).mapNotNull { o ->
            val props = o.o("properties"); val thumb = o.o("thumbnail")
            WebResultItem(WebResults.IMAGE, o.s("title").orEmpty(), o.s("url") ?: return@mapNotNull null, o.s("source"), thumb?.s("src"), props?.s("url"),
                null, props?.i("width") ?: thumb?.i("width"), props?.i("height") ?: thumb?.i("height"), provider = name)
        }.take(count)

        override suspend fun videos(query: String, count: Int): List<WebResultItem> = api("videos", query, count).mapNotNull { o ->
            val v = o.o("video")
            WebResultItem(WebResults.VIDEO, o.s("title").orEmpty(), o.s("url") ?: return@mapNotNull null, v?.s("creator") ?: v?.s("publisher") ?: o.o("meta_url")?.s("hostname"),
                o.o("thumbnail")?.s("src"), null, o.s("description"), durationSeconds = WebResults.parseDuration(v?.s("duration")), provider = name)
        }.take(count)
    }

    private class SearxngSearch(private val client: OkHttpClient, private val base: String) : SearchProvider {
        override val name = "SearXNG"
        override suspend fun search(query: String, count: Int): List<SearchHit> {
            val url = ("${base.trimEnd('/')}/search").toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", query)?.addQueryParameter("format", "json")?.addQueryParameter("language", "fr")?.build()
                ?: throw IllegalStateException("Adresse SearXNG invalide (Réglages → Recherche web)")
            val body = client.newCall(Request.Builder().url(url).get().build()).await().use { it.body?.string().orEmpty() }
            val results = AppJson.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                SearchHit((o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null, (o["content"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    o.s("thumbnail") ?: o.s("img_src"), null)
            }.take(count)
        }

        private suspend fun category(category: String, query: String): List<JsonObject> {
            val url = ("${base.trimEnd('/')}/search").toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", query)?.addQueryParameter("format", "json")?.addQueryParameter("language", "fr")?.addQueryParameter("categories", category)?.build()
                ?: throw IllegalStateException("Adresse SearXNG invalide (Réglages → Recherche web)")
            val body = client.newCall(Request.Builder().url(url).get().build()).await().use { it.body?.string().orEmpty() }
            return AppJson.parseToJsonElement(body).jsonObject.a("results")
        }

        override suspend fun images(query: String, count: Int): List<WebResultItem> = category("images", query).mapNotNull { o ->
            val res = o.s("resolution")?.split(Regex("\\s*[x×]\\s*"))?.mapNotNull { it.trim().toIntOrNull() }
            WebResultItem(WebResults.IMAGE, o.s("title").orEmpty(), o.s("url") ?: return@mapNotNull null, o.s("source") ?: o.s("engine"),
                o.s("thumbnail_src") ?: o.s("thumbnail"), o.s("img_src"), o.s("content"), res?.getOrNull(0), res?.getOrNull(1), provider = name)
        }.take(count)

        override suspend fun videos(query: String, count: Int): List<WebResultItem> = category("videos", query).mapNotNull { o ->
            WebResultItem(WebResults.VIDEO, o.s("title").orEmpty(), o.s("url") ?: return@mapNotNull null, o.s("author") ?: o.s("engine"),
                o.s("thumbnail"), o.s("video_src"), o.s("content"), durationSeconds = WebResults.parseDuration(o.s("length") ?: o.s("duration")), provider = name)
        }.take(count)
    }

    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            capability = "web.search",
            description = "Recherche sur le web (contenu non fiable). mode=web (défaut) : pages avec titre, URL et extrait ; images : photos ; videos : vidéos ; " +
                "mixed : pages, images et vidéos. Les images, vidéos et cartes trouvées s'affichent d'elles-mêmes dans ta réponse : choisis images, videos ou mixed " +
                "seulement quand un visuel aide vraiment (voir un lieu, un objet, un geste, un tutoriel), sinon web.",
            inputSchema = S.obj("query" to S.str("Requête de recherche"), "count" to S.int("Nombre de résultats (1-10)", 1, 10),
                "mode" to S.str("Type de résultats", WebResults.MODES), required = listOf("query")),
            baseRisk = Risk.L1, sideEffect = SideEffect.NONE, idempotency = Idempotency.INTRINSIC, dataEgress = DataEgress.EXTERNAL,
            category = ToolCategory.WEB, label = "Recherche web",
            destinationOf = { "moteur de recherche" },
            tags = listOf("chercher", "internet", "google", "actualités", "météo", "informations", "trouver"),
        ) { args, ctx ->
            val q = args.str("query").orEmpty()
            // The owner explicitly asked for pictures found on the web: images, even if the model forgot the mode.
            val asked = io.github.artisanguillonrenov.cortana.core.chat.ImageIntent.search(ctx.lastUserText)?.mode
                ?: if (io.github.artisanguillonrenov.cortana.core.chat.ImageIntent.isWebImageSearch(ctx.lastUserText)) "images" else null
            val mode = args.str("mode")?.takeIf { it in WebResults.MODES && !(it == "web" && asked != null) } ?: asked ?: "web"
            val p = searchProvider()
            val raw = runCatching { p.rich(q, mode, args.int("count") ?: 6) }.getOrElse { return@ToolDefinition ToolResult.error("Recherche impossible (${p.name}) : ${it.message}") }
            // Validated here and again when stored: nothing unsafe reaches the conversation.
            val items = WebResults.sanitize(raw)
            if (items.isEmpty()) return@ToolDefinition ToolResult.ok("Aucun résultat pour « $q » (${p.name}).", "web.search")
            ToolResult(true, WebResults.forModel(q, p.name, mode, items), "web.search:${p.name}",
                data = buildJsonObject { put(WebResults.DATA_KEY, AppJson.encodeToJsonElement(ListSerializer(WebResultItem.serializer()), items)) })
        },
        ToolDefinition(
            capability = "web.fetch",
            description = "Télécharge une page web (https) et renvoie son texte lisible et ses liens (contenu non fiable).",
            inputSchema = S.obj("url" to S.str("URL http(s) complète"), "max_chars" to S.int("Taille maximale du texte (défaut 8000)", 500, 30000), required = listOf("url")),
            baseRisk = Risk.L1, sideEffect = SideEffect.NONE, idempotency = Idempotency.INTRINSIC, dataEgress = DataEgress.EXTERNAL,
            category = ToolCategory.WEB, label = "Lecture d'une page web", maxOutputBytes = 32_000,
            tags = listOf("lien", "url", "site", "article", "lire", "ouvrir la page"),
            destinationOf = { a -> a.str("url")?.toHttpUrlOrNull()?.host },
        ) { args, _ ->
            val url = args.str("url").orEmpty()
            runCatching { ToolResult.ok(fetch(url, args.int("max_chars") ?: 8000), "web.fetch:" + (url.toHttpUrlOrNull()?.host ?: url)) }
                .getOrElse { ToolResult.error("Lecture impossible : ${it.message}") }
        },
    )
}
