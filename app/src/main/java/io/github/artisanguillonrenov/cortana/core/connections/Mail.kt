package io.github.artisanguillonrenov.cortana.core.connections

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MailException(message: String) : Exception(message)

data class MailAttachment(val name: String, val mime: String, val bytes: ByteArray)

data class MailMessage(
    val from: String, val to: List<String>, val cc: List<String>, val replyTo: List<String>, val subject: String, val date: String?,
    val messageId: String?, val inReplyTo: String?, val references: List<String>, val text: String, val attachments: List<MailAttachment>,
)

/**
 * The small part of MIME (RFC 2045-2049, 2047, 2231) Cortana needs to read and write mail:
 * multipart trees, base64 and quoted-printable, encoded words, charsets, attachment names.
 */
object Mime {
    private fun charset(name: String?): Charset = runCatching { Charset.forName(name?.trim('"') ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)

    fun splitHeaders(b: ByteArray): Pair<List<Pair<String, String>>, ByteArray> {
        var i = 0; var end = -1; var bodyStart = b.size
        while (i < b.size - 1) {
            if (b[i] == '\n'.code.toByte() && b[i + 1] == '\n'.code.toByte()) { end = i; bodyStart = i + 2; break }
            if (i < b.size - 3 && b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() && b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()) { end = i; bodyStart = i + 4; break }
            i++
        }
        val head = String(b, 0, if (end < 0) b.size else end, Charsets.ISO_8859_1)
        val headers = mutableListOf<Pair<String, String>>()
        for (line in head.split(Regex("\r?\n"))) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && headers.isNotEmpty()) headers[headers.lastIndex] = headers.last().let { it.first to it.second + " " + line.trim() }
            else line.indexOf(':').takeIf { it > 0 }?.let { headers += line.substring(0, it).trim() to line.substring(it + 1).trim() }
        }
        return headers to (if (bodyStart <= b.size) b.copyOfRange(bodyStart, b.size) else ByteArray(0))
    }

    /** RFC 2047 encoded words; raw 8-bit headers are read as UTF-8. */
    fun decodeWords(raw: String): String {
        val utf8 = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).takeIf { !it.contains('�') } ?: raw
        val re = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
        return re.replace(utf8.replace(Regex("(\\?=)\\s+(=\\?)"), "$1$2")) { m ->
            val cs = charset(m.groupValues[1].substringBefore('*'))
            runCatching {
                if (m.groupValues[2].equals("B", true)) String(Base64.getMimeDecoder().decode(m.groupValues[3]), cs)
                else String(qp(m.groupValues[3].replace('_', ' ').toByteArray(Charsets.ISO_8859_1)), cs)
            }.getOrDefault(m.value)
        }
    }

    fun qp(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); var i = 0
        while (i < b.size) {
            val c = b[i]
            if (c == '='.code.toByte()) {
                if (i + 1 < b.size && (b[i + 1] == '\r'.code.toByte() || b[i + 1] == '\n'.code.toByte())) { i += if (i + 2 < b.size && b[i + 1] == '\r'.code.toByte() && b[i + 2] == '\n'.code.toByte()) 3 else 2; continue }
                val h = if (i + 2 < b.size) String(b, i + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16) else null
                if (h != null) { out.write(h); i += 3; continue }
            }
            out.write(c.toInt()); i++
        }
        return out.toByteArray()
    }

    /** `type/sub; a=b; c="d"` → (type/sub, params); RFC 2231 `name*=utf-8''…` decoded. */
    fun params(value: String?): Pair<String, Map<String, String>> {
        if (value == null) return "" to emptyMap()
        val parts = Regex(";(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)").split(value)
        val p = LinkedHashMap<String, String>()
        parts.drop(1).forEach { kv ->
            val k = kv.substringBefore('=').trim().lowercase(); var v = kv.substringAfter('=', "").trim().trim('"')
            if (k.endsWith("*")) { val enc = v.split('\'', limit = 3); if (enc.size == 3) v = runCatching { java.net.URLDecoder.decode(enc[2].replace("+", "%2B"), enc[0].ifEmpty { "UTF-8" }) }.getOrDefault(enc[2]) }
            p[k.removeSuffix("*").replace(Regex("\\*\\d+$"), "")] = p[k.removeSuffix("*").replace(Regex("\\*\\d+$"), "")]?.plus(v) ?: v
        }
        return parts[0].trim().lowercase() to p.mapValues { decodeWords(it.value) }
    }

    private fun body(headers: List<Pair<String, String>>, b: ByteArray): ByteArray = when (headers.lastOrNull { it.first.equals("Content-Transfer-Encoding", true) }?.second?.lowercase()?.trim()) {
        "base64" -> runCatching { Base64.getMimeDecoder().decode(String(b, Charsets.ISO_8859_1).replace(Regex("[^A-Za-z0-9+/=]"), "")) }.getOrDefault(ByteArray(0))
        "quoted-printable" -> qp(b)
        else -> b
    }

    fun addresses(v: String?): List<String> = v?.let { decodeWords(it) }?.split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    fun bare(address: String): String = Regex("<([^>]+)>").find(address)?.groupValues?.get(1)?.trim() ?: address.trim()

    fun parse(raw: ByteArray, maxAttachment: Int = 25_000_000): MailMessage {
        val (headers, bodyBytes) = splitHeaders(raw)
        fun h(n: String) = headers.lastOrNull { it.first.equals(n, true) }?.second
        val texts = mutableListOf<String>(); val htmls = mutableListOf<String>(); val atts = mutableListOf<MailAttachment>()
        fun walk(hs: List<Pair<String, String>>, b: ByteArray, depth: Int) {
            if (depth > 12) return
            val (type, p) = params(hs.lastOrNull { it.first.equals("Content-Type", true) }?.second ?: "text/plain")
            val (disp, dp) = params(hs.lastOrNull { it.first.equals("Content-Disposition", true) }?.second)
            if (type.startsWith("multipart/")) {
                val boundary = p["boundary"] ?: return
                val s = String(b, Charsets.ISO_8859_1)
                s.split("--$boundary").drop(1).takeWhile { !it.startsWith("--") }.forEach { part ->
                    val bytes = part.removePrefix("\r\n").removePrefix("\n").removeSuffix("\r\n").removeSuffix("\n").toByteArray(Charsets.ISO_8859_1)
                    val (ph, pb) = splitHeaders(bytes); walk(ph, pb, depth + 1)
                }
                return
            }
            val data = body(hs, b)
            val name = dp["filename"] ?: p["name"]
            when {
                type == "message/rfc822" -> atts += MailAttachment(name ?: "message.eml", type, data)
                disp == "attachment" || (name != null && !type.startsWith("text/")) -> if (data.size <= maxAttachment) atts += MailAttachment(name ?: "piece-jointe", type.ifEmpty { "application/octet-stream" }, data)
                type == "text/html" -> htmls += String(data, charset(p["charset"]))
                type.startsWith("text/") || type.isEmpty() -> texts += String(data, charset(p["charset"]))
            }
        }
        walk(headers, bodyBytes, 0)
        val text = texts.joinToString("\n\n").ifBlank { htmls.joinToString("\n\n") { org.jsoup.Jsoup.parse(it).apply { select("script,style").remove() }.wholeText().lines().map(String::trim).filter { l -> l.isNotEmpty() }.joinToString("\n") } }
        return MailMessage(decodeWords(h("From") ?: ""), addresses(h("To")), addresses(h("Cc")), addresses(h("Reply-To")), decodeWords(h("Subject") ?: ""), h("Date"),
            h("Message-ID")?.trim(), h("In-Reply-To")?.trim(), h("References")?.split(Regex("\\s+"))?.filter { it.startsWith("<") }.orEmpty(), text.trim(), atts)
    }

    private fun word(s: String) = if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray()) + "?="

    /** An address list header value with non-ASCII display names encoded. */
    private fun addrs(list: List<String>) = list.joinToString(", ") { a ->
        val m = Regex("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>\\s*$").find(a)
        if (m != null && m.groupValues[1].isNotBlank()) "${word(m.groupValues[1].trim())} <${m.groupValues[2].trim()}>" else bare(a)
    }

    fun validAddress(a: String) = bare(a).matches(Regex("[^\\s@<>\"(),;:]+@[^\\s@<>\"(),;:]+\\.[^\\s@<>\"(),;:]+")) && !a.contains('\r') && !a.contains('\n')

    fun build(
        from: String, to: List<String>, cc: List<String>, subject: String, text: String, attachments: List<MailAttachment> = emptyList(),
        inReplyTo: String? = null, references: List<String> = emptyList(), messageId: String = "<${java.util.UUID.randomUUID()}@cortana.local>", date: ZonedDateTime = ZonedDateTime.now(),
    ): ByteArray {
        (listOf(from) + to + cc).firstOrNull { !validAddress(it) }?.let { throw MailException("adresse invalide : $it") }
        if (subject.contains('\n') || subject.contains('\r')) throw MailException("objet invalide")
        val sb = StringBuilder()
        sb.append("From: ${addrs(listOf(from))}\r\n")
        if (to.isNotEmpty()) sb.append("To: ${addrs(to)}\r\n")
        if (cc.isNotEmpty()) sb.append("Cc: ${addrs(cc)}\r\n")
        sb.append("Subject: ${word(subject)}\r\nDate: ${DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT).format(date)}\r\nMessage-ID: $messageId\r\n")
        inReplyTo?.let { sb.append("In-Reply-To: $it\r\n") }
        if (references.isNotEmpty()) sb.append("References: ${references.joinToString(" ")}\r\n")
        sb.append("MIME-Version: 1.0\r\nUser-Agent: Cortana\r\n")
        fun b64(b: ByteArray) = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(b)
        val textPart = "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n${b64(text.replace(Regex("\r?\n"), "\r\n").toByteArray())}\r\n"
        if (attachments.isEmpty()) sb.append(textPart)
        else {
            val boundary = "=_cortana_" + java.util.UUID.randomUUID().toString().replace("-", "")
            sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n--$boundary\r\n").append(textPart)
            for (a in attachments) {
                val n = a.name.replace(Regex("[\"\\\\\r\n]"), "_")
                val star = java.net.URLEncoder.encode(n, "UTF-8").replace("+", "%20")
                sb.append("--$boundary\r\nContent-Type: ${a.mime}; name=\"${word(n)}\"\r\nContent-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename*=UTF-8''$star\r\n\r\n${b64(a.bytes)}\r\n")
            }
            sb.append("--$boundary--\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}

/** How to reach a mail server. `plain` is refused unless the host is on the local network. */
data class MailServer(val host: String, val port: Int, val security: String, val username: String, val password: String)

private fun open(s: MailServer): Socket {
    if (s.security == "plain" && !OAuthClient.localHost(s.host)) throw MailException("connexion non chiffrée refusée hors du réseau local (${s.host})")
    val sock = Socket().apply { connect(InetSocketAddress(s.host, s.port), 20_000); soTimeout = 60_000 }
    return if (s.security == "tls") tls(sock, s.host, s.port) else sock
}

private fun tls(sock: Socket, host: String, port: Int): Socket {
    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(sock, host, port, true) as SSLSocket
    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    ssl.startHandshake()
    return ssl
}

private fun readLine(i: InputStream): String {
    val out = ByteArrayOutputStream()
    while (true) {
        val c = i.read(); if (c < 0) { if (out.size() == 0) throw MailException("connexion fermée par le serveur"); break }
        if (c == '\n'.code) break
        if (c != '\r'.code) out.write(c)
        if (out.size() > 1_000_000) throw MailException("ligne trop longue")
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/** Minimal IMAP4rev1 client (RFC 3501) with literals, STARTTLS, MOVE and UIDPLUS when offered. */
class ImapSession private constructor(private var sock: Socket) : AutoCloseable {
    private var input: InputStream = BufferedInputStream(sock.getInputStream())
    private var output: OutputStream = sock.getOutputStream()
    private var tag = 0
    var capabilities: Set<String> = emptySet(); private set

    /** One untagged response: its text with each literal replaced by a marker, and the literals. */
    class Untagged(val line: String, val literals: List<ByteArray>)

    private fun read(): Untagged {
        val sb = StringBuilder(); val lits = mutableListOf<ByteArray>()
        while (true) {
            val l = readLine(input)
            val m = Regex("\\{(\\d+)\\}$").find(l)
            if (m == null) { sb.append(l); break }
            sb.append(l.substring(0, m.range.first)).append("\u0000${lits.size}\u0000")
            val n = m.groupValues[1].toInt()
            if (n > 60_000_000) throw MailException("message trop volumineux")
            val b = ByteArray(n); var off = 0
            while (off < n) { val k = input.read(b, off, n - off); if (k < 0) throw MailException("connexion interrompue"); off += k }
            lits += b
        }
        return Untagged(sb.toString(), lits)
    }

    /** Sends a command whose [parts] are text or byte-array literals; returns the untagged responses. */
    fun cmd(vararg parts: Any): List<Untagged> {
        val t = "c${++tag}"
        output.write("$t ".toByteArray())
        parts.forEachIndexed { idx, p ->
            if (idx > 0) output.write(" ".toByteArray())
            when (p) {
                is ByteArray -> {
                    output.write("{${p.size}}\r\n".toByteArray()); output.flush()
                    val cont = readLine(input)
                    if (!cont.startsWith("+")) throw MailException("serveur IMAP : ${cont.take(160)}")
                    output.write(p)
                }
                else -> output.write(p.toString().toByteArray(Charsets.UTF_8))
            }
        }
        output.write("\r\n".toByteArray()); output.flush()
        val out = mutableListOf<Untagged>()
        while (true) {
            val r = read()
            if (r.line.startsWith("$t ")) {
                val status = r.line.substring(t.length + 1)
                if (!status.startsWith("OK")) throw MailException("IMAP : ${status.take(200)}")
                return out
            }
            if (r.line.startsWith("+")) continue
            out += r
        }
    }

    fun quote(s: String): Any = if (s.any { it.code > 126 || it == '\r' || it == '\n' }) s.toByteArray() else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Folder names in modified UTF-7 (RFC 3501 §5.1.3). */
    fun mailbox(name: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c.code in 0x20..0x7e) { sb.append(if (c == '&') "&-" else c.toString()); i++; continue }
            var j = i; while (j < name.length && name[j].code !in 0x20..0x7e) j++
            val bytes = name.substring(i, j).toByteArray(Charsets.UTF_16BE)
            sb.append('&').append(Base64.getEncoder().withoutPadding().encodeToString(bytes).replace('/', ',')).append('-')
            i = j
        }
        return "\"" + sb.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun readCaps(rs: List<Untagged>) { rs.firstOrNull { it.line.startsWith("* CAPABILITY") }?.let { capabilities = it.line.removePrefix("* CAPABILITY").trim().uppercase().split(' ').toSet() } }

    fun select(folder: String) = cmd("SELECT", mailbox(folder))

    fun uidSearch(vararg criteria: Any): List<Long> = cmd("UID SEARCH", *criteria).filter { it.line.startsWith("* SEARCH") }
        .flatMap { it.line.removePrefix("* SEARCH").trim().split(' ').mapNotNull(String::toLongOrNull) }

    data class Summary(val uid: Long, val flags: String, val size: Long, val header: ByteArray)

    fun summaries(uids: List<Long>): List<Summary> {
        if (uids.isEmpty()) return emptyList()
        return cmd("UID FETCH", uids.joinToString(","), "(UID FLAGS RFC822.SIZE BODY.PEEK[HEADER.FIELDS (FROM TO CC SUBJECT DATE MESSAGE-ID)])").mapNotNull { r ->
            val uid = Regex("UID (\\d+)").find(r.line)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            Summary(uid, Regex("FLAGS \\(([^)]*)\\)").find(r.line)?.groupValues?.get(1).orEmpty(), Regex("RFC822\\.SIZE (\\d+)").find(r.line)?.groupValues?.get(1)?.toLongOrNull() ?: 0, r.literals.firstOrNull() ?: ByteArray(0))
        }
    }

    fun message(uid: Long): ByteArray = cmd("UID FETCH", uid.toString(), "(UID BODY.PEEK[])").firstOrNull { it.literals.isNotEmpty() }?.literals?.first() ?: throw MailException("message $uid introuvable")

    fun append(folder: String, flags: String, message: ByteArray) = cmd("APPEND", mailbox(folder), "($flags)", message)

    /** Moves a message; with neither MOVE nor UIDPLUS the original is only flagged \Deleted (nothing is expunged). */
    fun move(uid: Long, folder: String): String {
        if ("MOVE" in capabilities) { cmd("UID MOVE", uid.toString(), mailbox(folder)); return "déplacé" }
        cmd("UID COPY", uid.toString(), mailbox(folder))
        cmd("UID STORE", uid.toString(), "+FLAGS.SILENT", "(\\Deleted)")
        return if ("UIDPLUS" in capabilities) { cmd("UID EXPUNGE", uid.toString()); "déplacé" } else "copié (l'original est marqué supprimé, rien n'est purgé)"
    }

    fun create(folder: String) = runCatching { cmd("CREATE", mailbox(folder)) }.isSuccess

    override fun close() { runCatching { cmd("LOGOUT") }; runCatching { sock.close() } }

    companion object {
        fun connect(s: MailServer): ImapSession {
            val sock = open(s)
            val session = ImapSession(sock)
            val greeting = readLine(session.input)
            if (!greeting.startsWith("* OK") && !greeting.startsWith("* PREAUTH")) { runCatching { sock.close() }; throw MailException("serveur IMAP : ${greeting.take(160)}") }
            try {
                if (s.security == "starttls") {
                    session.cmd("STARTTLS")
                    session.sock = tls(sock, s.host, s.port)
                    session.input = BufferedInputStream(session.sock.getInputStream()); session.output = session.sock.getOutputStream()
                }
                session.readCaps(session.cmd("CAPABILITY"))
                if ("LOGINDISABLED" in session.capabilities) throw MailException("le serveur IMAP refuse la connexion par mot de passe sans chiffrement")
                session.cmd("LOGIN", session.quote(s.username), session.quote(s.password))
                session.readCaps(session.cmd("CAPABILITY"))
            } catch (e: Exception) { runCatching { sock.close() }; throw if (e is MailException) MailException(e.message!!.replace(s.password, "***")) else MailException("IMAP : ${e.message}") }
            return session
        }
    }
}

/** Minimal SMTP submission client (RFC 5321, 4954, 3207). */
object SmtpClient {
    private class Conn(var sock: Socket) {
        var input: InputStream = BufferedInputStream(sock.getInputStream()); var output: OutputStream = sock.getOutputStream()
        fun reply(): Pair<Int, List<String>> {
            val lines = mutableListOf<String>()
            while (true) { val l = readLine(input); lines += l; if (l.length < 4 || l[3] != '-') break }
            return (lines.last().take(3).toIntOrNull() ?: 0) to lines
        }
        fun send(line: String, expect: Int): List<String> {
            output.write("$line\r\n".toByteArray()); output.flush()
            val (code, lines) = reply()
            if (code != expect && !(expect == 250 && code == 251)) throw MailException("SMTP ${line.substringBefore(' ').take(12)} : ${lines.joinToString(" ").take(200)}")
            return lines
        }
    }

    private fun session(s: MailServer): Conn {
        val c = Conn(open(s))
        val (code, lines) = c.reply(); if (code != 220) throw MailException("serveur SMTP : ${lines.joinToString(" ").take(160)}")
        var ehlo = c.send("EHLO cortana.local", 250)
        if (s.security == "starttls") {
            c.send("STARTTLS", 220)
            c.sock = tls(c.sock, s.host, s.port); c.input = BufferedInputStream(c.sock.getInputStream()); c.output = c.sock.getOutputStream()
            ehlo = c.send("EHLO cortana.local", 250)
        }
        val auth = ehlo.firstOrNull { it.drop(4).uppercase().startsWith("AUTH") }?.uppercase().orEmpty()
        try {
            when {
                auth.contains("PLAIN") -> c.send("AUTH PLAIN " + Base64.getEncoder().encodeToString("\u0000${s.username}\u0000${s.password}".toByteArray()), 235)
                auth.contains("LOGIN") -> { c.send("AUTH LOGIN", 334); c.send(Base64.getEncoder().encodeToString(s.username.toByteArray()), 334); c.send(Base64.getEncoder().encodeToString(s.password.toByteArray()), 235) }
                else -> throw MailException("le serveur SMTP ne propose pas d'authentification par mot de passe")
            }
        } catch (e: MailException) { runCatching { c.sock.close() }; throw MailException("authentification SMTP refusée") }
        return c
    }

    fun probe(s: MailServer) { val c = session(s); runCatching { c.send("QUIT", 221) }; runCatching { c.sock.close() } }

    fun send(s: MailServer, from: String, recipients: List<String>, message: ByteArray) {
        if (recipients.isEmpty()) throw MailException("aucun destinataire")
        val c = session(s)
        try {
            c.send("MAIL FROM:<${Mime.bare(from)}>", 250)
            recipients.forEach { c.send("RCPT TO:<${Mime.bare(it)}>", 250) }
            c.send("DATA", 354)
            val text = String(message, Charsets.UTF_8).replace(Regex("\r?\n"), "\r\n").split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
            c.output.write(text.toByteArray(Charsets.UTF_8)); c.output.write("\r\n.\r\n".toByteArray()); c.output.flush()
            val (code, lines) = c.reply(); if (code != 250) throw MailException("message refusé : ${lines.joinToString(" ").take(200)}")
            runCatching { c.send("QUIT", 221) }
        } finally { runCatching { c.sock.close() } }
    }
}
