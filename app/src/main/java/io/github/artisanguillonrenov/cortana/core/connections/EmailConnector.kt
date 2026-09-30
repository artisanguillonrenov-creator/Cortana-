package io.github.artisanguillonrenov.cortana.core.connections

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * E-mail adapter (blueprint §36, doc 05 §13) over IMAP + SMTP: search, read, thread, attachment,
 * draft, send, reply, forward, archive. Nothing is ever deleted; sending is the tools' L2 business.
 */
class EmailConnector(private val mgr: ConnectionManager) : ConnectorAdapter {
    data class Header(val uid: Long, val from: String, val subject: String, val date: String?, val unread: Boolean, val size: Long, val messageId: String?)

    private suspend fun server(c: Conn, smtp: Boolean): MailServer {
        val pwd = c.secret("password") ?: throw ConnectionException("mot de passe manquant")
        val sec = c.cfg("security") ?: "tls"
        if (sec !in setOf("tls", "starttls", "plain")) throw ConnectionException("sécurité inconnue : $sec")
        val host = (if (smtp) c.cfg("smtp_host") else c.cfg("imap_host")) ?: throw ConnectionException("serveur manquant")
        val port = (if (smtp) c.cfg("smtp_port") else c.cfg("imap_port"))?.toIntOrNull() ?: throw ConnectionException("port invalide")
        // Implicit TLS on 465 but STARTTLS on 587 for SMTP, whatever "tls" means for IMAP.
        val s = if (smtp && sec == "tls" && port == 587) "starttls" else sec
        return MailServer(host, port, s, c.cfg("username") ?: c.cfg("address")!!, pwd)
    }

    private suspend fun <T> imap(c: Conn, block: (ImapSession) -> T): T {
        mgr.acquire(c)
        val s = server(c, false)
        return withContext(Dispatchers.IO) {
            try { ImapSession.connect(s).use(block) } catch (e: MailException) { throw ConnectionException(e.message ?: "IMAP") } catch (e: java.io.IOException) { throw ConnectionException("IMAP injoignable : ${e.message}") }
        }
    }

    private fun searchArgs(s: ImapSession, from: String?, to: String?, subject: String?, text: String?, since: String?, before: String?, unread: Boolean): List<Any> {
        val a = mutableListOf<Any>()
        fun d(v: String) = runCatching { LocalDate.parse(v).format(DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)) }.getOrElse { throw ConnectionException("date invalide (AAAA-MM-JJ) : $v") }
        from?.let { a += "FROM"; a += s.quote(it) }; to?.let { a += "TO"; a += s.quote(it) }; subject?.let { a += "SUBJECT"; a += s.quote(it) }; text?.let { a += "TEXT"; a += s.quote(it) }
        since?.let { a += "SINCE"; a += d(it) }; before?.let { a += "BEFORE"; a += d(it) }
        if (unread) a += "UNSEEN"
        if (a.isEmpty()) a += "ALL"
        return if (a.any { it is ByteArray }) listOf<Any>("CHARSET", "UTF-8") + a else a
    }

    private fun header(sum: ImapSession.Summary): Header {
        val m = Mime.parse(sum.header)
        return Header(sum.uid, m.from, m.subject, m.date, !sum.flags.contains("\\Seen"), sum.size, m.messageId)
    }

    suspend fun search(c: Conn, folder: String, from: String?, to: String?, subject: String?, text: String?, since: String?, before: String?, unread: Boolean, limit: Int): Pair<Int, List<Header>> = imap(c) { s ->
        s.select(folder)
        val uids = s.uidSearch(*searchArgs(s, from, to, subject, text, since, before, unread).toTypedArray()).sortedDescending()
        uids.size to s.summaries(uids.take(limit.coerceIn(1, 50))).map(::header).sortedByDescending { it.uid }
    }

    suspend fun read(c: Conn, folder: String, uid: Long): MailMessage = imap(c) { s -> s.select(folder); Mime.parse(s.message(uid)) }

    /** The conversation around [uid]: messages whose ids it references or that reference it, in [folder] and the sent folder. */
    suspend fun thread(c: Conn, folder: String, uid: Long): List<Pair<String, MailMessage>> = imap(c) { s ->
        s.select(folder)
        val root = Mime.parse(s.message(uid))
        val ids = (root.references + listOfNotNull(root.inReplyTo, root.messageId)).distinct().take(20)
        val out = LinkedHashMap<String, Pair<String, MailMessage>>()
        for (f in listOfNotNull(folder, c.cfg("sent_folder")).distinct()) {
            runCatching { s.select(f) }.onFailure { return@imap out.values.toList() }
            val uids = ids.flatMap { id -> listOf("Message-ID", "In-Reply-To", "References").flatMap { h -> runCatching { s.uidSearch("HEADER", h, s.quote(id)) }.getOrDefault(emptyList()) } }.distinct()
            uids.take(30).forEach { u -> val m = Mime.parse(s.message(u)); out.putIfAbsent(m.messageId ?: "$f:$u", f to m) }
        }
        out.values.sortedBy { runCatching { java.time.ZonedDateTime.parse(it.second.date, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrDefault(0L) }
    }

    suspend fun attachment(c: Conn, folder: String, uid: Long, index: Int): MailAttachment {
        val m = read(c, folder, uid)
        return m.attachments.getOrNull(index - 1) ?: throw ConnectionException("pièce jointe $index introuvable (${m.attachments.size} dans ce message)")
    }

    private suspend fun from(c: Conn): String = c.cfg("display_name")?.let { "\"$it\" <${c.cfg("address")}>" } ?: c.cfg("address")!!

    suspend fun draft(c: Conn, to: List<String>, cc: List<String>, subject: String, body: String, attachments: List<MailAttachment>): String {
        val msg = try { Mime.build(from(c), to, cc, subject, body, attachments) } catch (e: MailException) { throw ConnectionException(e.message ?: "message invalide") }
        val folder = c.cfg("drafts_folder") ?: "Drafts"
        imap(c) { s -> runCatching { s.append(folder, "\\Draft", msg) }.getOrElse { s.create(folder); s.append(folder, "\\Draft", msg) } }
        return folder
    }

    /** Sends through SMTP, then keeps a copy in the sent folder when one is configured. */
    suspend fun send(c: Conn, to: List<String>, cc: List<String>, bcc: List<String>, subject: String, body: String, attachments: List<MailAttachment>, inReplyTo: String? = null, references: List<String> = emptyList()): String {
        val msg = try { Mime.build(from(c), to, cc, subject, body, attachments, inReplyTo, references) } catch (e: MailException) { throw ConnectionException(e.message ?: "message invalide") }
        bcc.firstOrNull { !Mime.validAddress(it) }?.let { throw ConnectionException("adresse invalide : $it") }
        mgr.acquire(c)
        val smtp = server(c, true)
        withContext(Dispatchers.IO) {
            try { SmtpClient.send(smtp, c.cfg("address")!!, to + cc + bcc, msg) } catch (e: MailException) { throw ConnectionException(e.message ?: "SMTP") } catch (e: java.io.IOException) { throw ConnectionException("SMTP injoignable : ${e.message}") }
        }
        val sent = c.cfg("sent_folder")
        if (sent != null) runCatching { imap(c) { s -> runCatching { s.append(sent, "\\Seen", msg) }.getOrElse { s.create(sent); s.append(sent, "\\Seen", msg) } } }
            .onFailure { return "envoyé (copie dans « $sent » impossible : ${it.message})" }
        return "envoyé"
    }

    data class Draft(val to: List<String>, val cc: List<String>, val subject: String, val body: String, val inReplyTo: String?, val references: List<String>, val attachments: List<MailAttachment>)

    /** What a reply would contain (recipients, subject, quoted text, threading headers). */
    suspend fun replyDraft(c: Conn, folder: String, uid: Long, text: String, all: Boolean): Draft {
        val m = read(c, folder, uid)
        val me = c.cfg("address")!!.lowercase()
        val to = (m.replyTo.ifEmpty { listOf(m.from) }).filter { Mime.bare(it).lowercase() != me }.ifEmpty { listOf(m.from) }
        val cc = if (all) (m.to + m.cc).filter { a -> Mime.bare(a).lowercase() != me && to.none { Mime.bare(it).equals(Mime.bare(a), true) } } else emptyList()
        val subject = if (m.subject.startsWith("Re:", true)) m.subject else "Re: ${m.subject}"
        val quoted = m.text.lines().take(40).joinToString("\n") { "> $it" }
        return Draft(to, cc, subject, "$text\n\nLe ${m.date ?: "?"}, ${m.from} a écrit :\n$quoted", m.messageId, (m.references + listOfNotNull(m.messageId)).distinct().takeLast(20), emptyList())
    }

    suspend fun forwardDraft(c: Conn, folder: String, uid: Long, to: List<String>, note: String): Draft {
        val m = read(c, folder, uid)
        val subject = if (m.subject.startsWith("Fwd:", true) || m.subject.startsWith("TR:", true)) m.subject else "Fwd: ${m.subject}"
        val body = "$note\n\n---------- Message transféré ----------\nDe : ${m.from}\nDate : ${m.date ?: "?"}\nObjet : ${m.subject}\nÀ : ${m.to.joinToString()}\n\n${m.text}"
        return Draft(to, emptyList(), subject, body, null, emptyList(), m.attachments.filter { it.bytes.size <= 15_000_000 }.take(10))
    }

    suspend fun archive(c: Conn, folder: String, uid: Long): String {
        val target = c.cfg("archive_folder") ?: "Archive"
        return imap(c) { s ->
            s.select(folder)
            runCatching { s.move(uid, target) }.getOrElse { s.create(target); s.select(folder); s.move(uid, target) } + " vers « $target »"
        }
    }

    override suspend fun probe(c: Conn): String {
        val n = imap(c) { s -> s.select("INBOX").firstOrNull { it.line.endsWith("EXISTS") }?.line?.removePrefix("* ")?.substringBefore(' ') ?: "?" }
        val smtp = server(c, true)
        withContext(Dispatchers.IO) { try { SmtpClient.probe(smtp) } catch (e: MailException) { throw ConnectionException(e.message ?: "SMTP") } catch (e: java.io.IOException) { throw ConnectionException("SMTP injoignable : ${e.message}") } }
        return "IMAP et SMTP joignables ; $n message(s) dans la boîte de réception"
    }
}
