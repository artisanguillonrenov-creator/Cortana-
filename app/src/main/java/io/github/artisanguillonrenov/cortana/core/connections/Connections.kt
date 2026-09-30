package io.github.artisanguillonrenov.cortana.core.connections

import io.github.artisanguillonrenov.cortana.core.memory.ConnectionDao
import io.github.artisanguillonrenov.cortana.core.memory.ConnectionEntity
import io.github.artisanguillonrenov.cortana.core.memory.ConnectionEventEntity
import io.github.artisanguillonrenov.cortana.core.policy.AuditLog
import io.github.artisanguillonrenov.cortana.util.AppJson
import io.github.artisanguillonrenov.cortana.util.Ids
import io.github.artisanguillonrenov.cortana.util.Redactor
import io.github.artisanguillonrenov.cortana.util.truncateBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Secret storage seen by the connection manager (the Keystore-backed store on the device). */
interface SecretVault {
    fun newHandle(): String
    fun put(handle: String, value: String)
    fun get(handle: String?): String?
    fun remove(handle: String?)
}

class ConnectionException(message: String) : Exception(message)

data class ConnectorField(val key: String, val label: String, val secret: Boolean = false, val required: Boolean = true, val default: String? = null)

/** A connector type (doc 05 §14): auth schemes, fields, what it provides, rate limit, risk. */
data class ConnectorKind(
    val id: String, val label: String, val description: String, val authSchemes: List<String>, val fields: List<ConnectorField>,
    val provides: List<String>, val rateLimitPerMinute: Int, val risk: String,
)

object ConnectorKinds {
    private val oauth = listOf(
        ConnectorField("oauth_issuer", "OAuth : émetteur (découverte automatique)", required = false),
        ConnectorField("oauth_authorize_url", "OAuth : adresse d'autorisation", required = false),
        ConnectorField("oauth_token_url", "OAuth : adresse des jetons", required = false),
        ConnectorField("oauth_revoke_url", "OAuth : adresse de révocation", required = false),
        ConnectorField("oauth_client_id", "OAuth : identifiant client (vide = enregistrement automatique)", required = false),
        ConnectorField("oauth_client_secret", "OAuth : secret client (clients confidentiels)", secret = true, required = false),
        ConnectorField("oauth_scopes", "OAuth : portées (séparées par des espaces)", required = false),
        ConnectorField("oauth_resource", "OAuth : ressource (RFC 8707)", required = false),
    )

    val HTTP = ConnectorKind("http", "API HTTP", "Une API REST : adresse de base, méthodes permises, authentification injectée par Cortana.",
        listOf("none", "bearer", "api_key", "basic", "oauth2"), listOf(
            ConnectorField("base_url", "Adresse de base"), ConnectorField("methods", "Méthodes permises (ex. GET,POST)", required = false, default = "GET"),
            ConnectorField("api_key_header", "En-tête de la clé d'API", required = false, default = "X-API-Key"), ConnectorField("username", "Utilisateur (basic)", required = false),
            ConnectorField("health_path", "Chemin de test de santé", required = false), ConnectorField("token", "Jeton ou clé d'API", secret = true, required = false),
            ConnectorField("password", "Mot de passe (basic)", secret = true, required = false),
        ) + oauth, listOf("http.request"), 60, "lecture L1 ; écriture L2 ; suppression L3")

    val WEBHOOK_OUT = ConnectorKind("webhook_out", "Webhook sortant", "Envoie un événement JSON signé (HMAC-SHA256) à une adresse.",
        listOf("hmac", "none"), listOf(ConnectorField("url", "Adresse de destination"), ConnectorField("signing_secret", "Secret de signature", secret = true, required = false)),
        listOf("webhook.send"), 30, "envoi L2")

    val WEBHOOK_IN = ConnectorKind("webhook_in", "Webhook entrant", "Reçoit des événements signés via un worker appairé ; chaque événement lance une tâche contaminée.",
        listOf("hmac"), listOf(
            ConnectorField("worker_id", "Worker qui héberge l'adresse"),
            ConnectorField("objective", "Consigne pour chaque événement", required = false, default = "Un événement est arrivé sur ce webhook : résume-le et préviens-moi seulement s'il demande une action."),
            ConnectorField("max_per_minute", "Événements par minute au plus", required = false, default = "30"),
        ), listOf("déclencheur de tâche"), 30, "tâches contaminées")

    val HOME_ASSISTANT = ConnectorKind("home_assistant", "Home Assistant", "Maison connectée : états des entités et appels de services limités aux domaines choisis.",
        listOf("bearer", "oauth2"), listOf(
            ConnectorField("base_url", "Adresse de Home Assistant (ex. http://homeassistant.local:8123)"), ConnectorField("token", "Jeton d'accès longue durée", secret = true, required = false),
            ConnectorField("allowed_domains", "Domaines permis", required = false, default = "light,switch,scene,script,media_player,climate,fan,cover,vacuum,input_boolean,lock,alarm_control_panel"),
            ConnectorField("sensitive_domains", "Domaines sensibles (confirmation forte)", required = false, default = "lock,alarm_control_panel,cover,valve"),
        ) + oauth, listOf("home.states", "home.call"), 60, "lecture L1 ; action L2 ; domaines sensibles L3")

    val EMAIL = ConnectorKind("email", "E-mail (IMAP/SMTP)", "Boîte aux lettres : recherche, lecture, pièces jointes, brouillons, envoi, réponse, transfert, archivage.",
        listOf("password"), listOf(
            ConnectorField("address", "Adresse e-mail"), ConnectorField("display_name", "Nom affiché", required = false),
            ConnectorField("imap_host", "Serveur IMAP"), ConnectorField("imap_port", "Port IMAP", required = false, default = "993"),
            ConnectorField("smtp_host", "Serveur SMTP"), ConnectorField("smtp_port", "Port SMTP", required = false, default = "465"),
            ConnectorField("security", "Sécurité : tls, starttls (ou plain sur le réseau local)", required = false, default = "tls"),
            ConnectorField("username", "Identifiant (défaut : l'adresse)", required = false), ConnectorField("password", "Mot de passe (ou mot de passe d'application)", secret = true),
            ConnectorField("sent_folder", "Dossier des envoyés (vide = pas de copie)", required = false), ConnectorField("drafts_folder", "Dossier des brouillons", required = false, default = "Drafts"),
            ConnectorField("archive_folder", "Dossier d'archive", required = false, default = "Archive"),
        ), listOf("email.search", "email.read", "email.attachment", "email.draft", "email.send", "email.reply", "email.forward", "email.archive"), 60,
        "lecture L0 ; brouillon et archivage L1 ; envoi L2")

    val TELEGRAM = ConnectorKind("telegram", "Telegram (bot)", "Discuter avec Cortana depuis Telegram : seuls les comptes autorisés sont écoutés ; les réponses passent par l'outbox.",
        listOf("bearer"), listOf(
            ConnectorField("bot_token", "Jeton du bot", secret = true), ConnectorField("allowed_chats", "Identifiants de discussion autorisés (séparés par des virgules)"),
            ConnectorField("api_base", "Adresse de l'API", required = false, default = "https://api.telegram.org"),
        ), listOf("messagerie entrante et sortante"), 30, "messages du propriétaire authentifié")

    val all = listOf(HTTP, WEBHOOK_OUT, WEBHOOK_IN, HOME_ASSISTANT, EMAIL, TELEGRAM)
    fun get(id: String) = all.firstOrNull { it.id == id }
}

/** A connection as its adapters see it: configuration and a way to reach its secrets. */
class Conn(val entity: ConnectionEntity, val config: Map<String, String>, private val mgr: ConnectionManager) {
    val id get() = entity.connectionId
    val name get() = entity.name
    val kind get() = entity.kind
    fun cfg(key: String): String? = config[key]?.takeIf { it.isNotBlank() } ?: ConnectorKinds.get(kind)?.fields?.firstOrNull { it.key == key }?.default
    suspend fun secret(key: String): String? = mgr.secret(this, key)
    suspend fun authHeaders(): Map<String, String> = mgr.authHeaders(this)
}

/** What a connector type plugs into the manager. */
interface ConnectorAdapter {
    /** Health probe; returns a short description or throws. */
    suspend fun probe(c: Conn): String
    suspend fun onAdd(c: Conn) {}
    suspend fun onRevoke(c: Conn) {}
}

/**
 * Connection Manager (blueprint §38) — the sole lifecycle authority for external accounts and
 * endpoints: add, authenticate (API key, basic, bearer, OAuth 2.1 + PKCE), refresh, health with
 * backoff, rate limits, disable, revoke (tokens revoked at the provider, secrets erased, adapter
 * torn down) and remove. Tools, MCP servers and inbound channels reach a connection only through
 * [usable]; they never see a secret.
 */
class ConnectionManager(
    private val dao: ConnectionDao,
    private val vault: () -> SecretVault,
    private val oauth: OAuthClient,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val adapters = ConcurrentHashMap<String, ConnectorAdapter>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val windows = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun register(kind: String, adapter: ConnectorAdapter) { adapters[kind] = adapter }

    fun observe(): Flow<List<ConnectionEntity>> = dao.observe()
    suspend fun all(): List<ConnectionEntity> = dao.all()
    suspend fun get(id: String): ConnectionEntity? = dao.get(id)
    suspend fun byName(name: String): ConnectionEntity? = dao.byName(name)
    suspend fun events(id: String, limit: Int = 50) = dao.events(id, limit)

    private val mapSer = MapSerializer(String.serializer(), String.serializer())
    fun configOf(e: ConnectionEntity): Map<String, String> = runCatching { AppJson.decodeFromString(mapSer, e.configJson) }.getOrDefault(emptyMap())
    private fun handlesOf(e: ConnectionEntity): Map<String, String> = runCatching { AppJson.decodeFromString(mapSer, e.secretHandlesJson) }.getOrDefault(emptyMap())
    fun conn(e: ConnectionEntity) = Conn(e, configOf(e), this)

    suspend fun record(connectionId: String, type: String, outcome: String, detail: String, taskId: String? = null, payload: String? = null, id: String = Ids.new()): Boolean =
        dao.insertEvent(ConnectionEventEntity(id, connectionId, clock(), type, outcome, Redactor.redact(detail).truncateBytes(500), taskId, payload?.truncateBytes(64_000))) != -1L

    private suspend fun save(e: ConnectionEntity) = dao.upsert(e.copy(updatedAt = clock()))

    // ------------------------------------------------------------------ lifecycle

    suspend fun add(kind: String, name: String, config: Map<String, String>, secrets: Map<String, String>, authScheme: String? = null, pluginId: String? = null): ConnectionEntity {
        val k = ConnectorKinds.get(kind) ?: throw ConnectionException("type de connexion inconnu : $kind")
        if (!name.matches(Regex("[a-z0-9][a-z0-9_-]{1,40}"))) throw ConnectionException("nom invalide : lettres minuscules, chiffres, - et _ (2 à 41 caractères)")
        if (dao.byName(name) != null) throw ConnectionException("une connexion « $name » existe déjà")
        val scheme = authScheme ?: k.authSchemes.first()
        if (scheme !in k.authSchemes) throw ConnectionException("authentification $scheme non prise en charge par ${k.label} (${k.authSchemes.joinToString()})")
        val unknown = (config.keys + secrets.keys) - k.fields.map { it.key }.toSet()
        if (unknown.isNotEmpty()) throw ConnectionException("champs inconnus : ${unknown.joinToString()}")
        k.fields.filter { !it.secret && it.required && config[it.key].isNullOrBlank() }.firstOrNull()?.let { throw ConnectionException("champ requis : ${it.label}") }
        k.fields.filter { it.secret && it.required && secrets[it.key].isNullOrBlank() }.firstOrNull()?.let { throw ConnectionException("secret requis : ${it.label}") }
        config.forEach { (key, v) -> if (key.endsWith("_url") && v.isNotBlank()) checkUrl(v) }
        if (config.keys.any { it in k.fields.filter { f -> f.secret }.map { f -> f.key } }) throw ConnectionException("un secret ne va jamais dans la configuration")
        val v = vault()
        val handles = secrets.filterValues { it.isNotBlank() }.mapValues { (_, value) -> v.newHandle().also { h -> v.put(h, value) } }
        val now = clock()
        var e = ConnectionEntity(Ids.new(), kind, name, scheme, AppJson.encodeToString(mapSer, config.filterValues { it.isNotBlank() }),
            AppJson.encodeToString(mapSer, handles), "[]", if (scheme == "oauth2") "pending_auth" else "active", createdAt = now, updatedAt = now, pluginId = pluginId)
        dao.upsert(e)
        try { adapters[kind]?.onAdd(conn(e)) } catch (x: Exception) {
            handles.values.forEach { v.remove(it) }; dao.delete(e.connectionId)
            throw ConnectionException("connexion non créée : ${x.message}")
        }
        e = dao.get(e.connectionId)!!
        record(e.connectionId, "config", "ok", "connexion ${k.label} créée (${scheme})")
        audit.record("owner", "connection.add", name, "ok", """{"kind":"$kind","auth":"$scheme"}""")
        return e
    }

    /** Stores a secret for [c] (replacing any previous one). */
    suspend fun putSecret(id: String, key: String, value: String) {
        val e = dao.get(id) ?: return
        val handles = handlesOf(e).toMutableMap()
        val v = vault()
        v.remove(handles[key])
        handles[key] = v.newHandle().also { v.put(it, value) }
        save(e.copy(secretHandlesJson = AppJson.encodeToString(mapSer, handles)))
    }

    private suspend fun dropSecret(id: String, key: String) {
        val e = dao.get(id) ?: return
        val handles = handlesOf(e).toMutableMap()
        vault().remove(handles.remove(key))
        save(e.copy(secretHandlesJson = AppJson.encodeToString(mapSer, handles)))
    }

    suspend fun setConfig(id: String, updates: Map<String, String?>) {
        val e = dao.get(id) ?: return
        val cfg = configOf(e).toMutableMap()
        updates.forEach { (k, v) -> if (v == null) cfg.remove(k) else cfg[k] = v }
        save(e.copy(configJson = AppJson.encodeToString(mapSer, cfg)))
    }

    internal suspend fun secret(c: Conn, key: String): String? = vault().get(handlesOf(dao.get(c.id) ?: c.entity)[key])

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val e = dao.get(id) ?: return
        if (e.state == "revoked") throw ConnectionException("connexion révoquée : recréez-la")
        save(e.copy(state = if (enabled) (if (e.authScheme == "oauth2" && handlesOf(e)["oauth_access"] == null) "pending_auth" else "active") else "disabled"))
        record(id, "config", "ok", if (enabled) "activée" else "désactivée")
        audit.record("owner", "connection.${if (enabled) "enable" else "disable"}", e.name, "ok")
    }

    /**
     * Disconnect for good: OAuth tokens are revoked at the provider when it allows it, the adapter
     * tears down (webhook removed from the worker, polling stopped), every secret is erased. The
     * row stays, marked revoked, with its history; nothing can use it any more.
     */
    suspend fun revoke(id: String, reason: String = "révoquée par le propriétaire"): ConnectionEntity? {
        val e = dao.get(id) ?: return null
        val c = conn(e)
        val remote = mutableListOf<String>()
        if (e.authScheme == "oauth2") {
            runCatching {
                val server = server(c)
                val clientId = c.cfg("oauth_client_id") ?: ""
                val clientSecret = secret(c, "oauth_client_secret")
                secret(c, "oauth_refresh")?.let { if (oauth.revoke(server, clientId, clientSecret, it, "refresh_token")) remote += "jeton de rafraîchissement révoqué" }
                secret(c, "oauth_access")?.let { if (oauth.revoke(server, clientId, clientSecret, it, "access_token")) remote += "jeton d'accès révoqué" }
            }.onFailure { x -> record(id, "revoke", "error", "révocation distante impossible : ${x.message} (effacement local effectué)") }
        }
        runCatching { adapters[e.kind]?.onRevoke(c) }.onFailure { x -> record(id, "revoke", "error", "arrêt de l'adaptateur : ${x.message}") }
        val v = vault()
        handlesOf(e).values.forEach { v.remove(it) }
        val out = e.copy(state = "revoked", secretHandlesJson = "{}", health = "unknown", lastError = null, updatedAt = clock())
        dao.upsert(out)
        record(id, "revoke", "ok", "$reason${if (remote.isNotEmpty()) " ; " + remote.joinToString() else ""} ; secrets effacés")
        audit.record("owner", "connection.revoke", e.name, "ok", """{"remote":${remote.size}}""")
        return out
    }

    /** Revokes, then forgets the connection and its history. */
    suspend fun remove(id: String) {
        val e = dao.get(id) ?: return
        if (e.state != "revoked") revoke(id, "supprimée")
        dao.deleteEvents(id); dao.delete(id)
        audit.record("owner", "connection.remove", e.name, "ok")
    }

    /** The only way tools and channels reach a connection. */
    suspend fun usable(name: String, kind: String? = null): Conn {
        val e = dao.byName(name) ?: throw ConnectionException("connexion « $name » inconnue (connections_list pour les voir)")
        if (kind != null && e.kind != kind) throw ConnectionException("« $name » n'est pas une connexion ${ConnectorKinds.get(kind)?.label ?: kind}")
        when (e.state) {
            "revoked" -> throw ConnectionException("connexion « $name » révoquée")
            "disabled" -> throw ConnectionException("connexion « $name » désactivée par le propriétaire")
            "pending_auth" -> throw ConnectionException("connexion « $name » à autoriser (Réglages → Connexions → Se connecter)")
        }
        return conn(e)
    }

    /** Per-connection rate limit (sliding minute). */
    fun acquire(c: Conn) {
        val limit = c.cfg("rate_per_minute")?.toIntOrNull() ?: ConnectorKinds.get(c.kind)?.rateLimitPerMinute ?: 60
        val w = windows.getOrPut(c.id) { ArrayDeque() }
        synchronized(w) {
            val now = clock()
            while (w.isNotEmpty() && now - w.first() > 60_000) w.removeFirst()
            if (w.size >= limit) throw ConnectionException("limite de $limit appels par minute atteinte pour « ${c.name} »")
            w.addLast(now)
        }
    }

    // ------------------------------------------------------------------ authentication

    fun checkUrl(url: String) {
        val u = okhttp3.HttpUrl.Companion.run { url.toHttpUrlOrNull() } ?: throw ConnectionException("adresse invalide : $url")
        if (!u.isHttps && !OAuthClient.localHost(u.host)) throw ConnectionException("https exigé hors du réseau local : $url")
    }

    suspend fun authHeaders(c: Conn): Map<String, String> = when (c.entity.authScheme) {
        "bearer" -> (secret(c, "token") ?: secret(c, "bot_token"))?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
        "api_key" -> secret(c, "token")?.let { mapOf((c.cfg("api_key_header") ?: "X-API-Key") to it) }.orEmpty()
        "basic" -> mapOf("Authorization" to okhttp3.Credentials.basic(c.cfg("username") ?: "", secret(c, "password") ?: ""))
        "oauth2" -> mapOf("Authorization" to "Bearer ${accessToken(c)}")
        else -> emptyMap()
    }

    private suspend fun server(c: Conn): OAuthServer {
        c.config["oauth_server"]?.let { s -> runCatching { AppJson.decodeFromString(OAuthServer.serializer(), s) }.getOrNull()?.let { return it } }
        val server = c.cfg("oauth_issuer")?.let { oauth.discover(it) } ?: run {
            val auth = c.cfg("oauth_authorize_url"); val token = c.cfg("oauth_token_url")
            if (auth == null || token == null) throw ConnectionException("OAuth : indiquez l'émetteur ou les adresses d'autorisation et de jetons")
            OAuthServer(null, auth, token, c.cfg("oauth_revoke_url"))
        }
        setConfig(c.id, mapOf("oauth_server" to AppJson.encodeToString(OAuthServer.serializer(), server)))
        return server
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Starts the owner's sign-in: returns the URL to open in the browser. */
    suspend fun beginOAuth(id: String): String {
        val e = dao.get(id) ?: throw ConnectionException("connexion inconnue")
        if (e.authScheme != "oauth2") throw ConnectionException("cette connexion n'utilise pas OAuth")
        if (e.state == "revoked") throw ConnectionException("connexion révoquée : recréez-la")
        var c = conn(e)
        val server = server(c)
        var clientId = c.cfg("oauth_client_id")
        if (clientId == null) {
            val (cid, csecret) = oauth.register(server, OAuthClient.REDIRECT_URI)
            setConfig(id, mapOf("oauth_client_id" to cid)); csecret?.let { putSecret(id, "oauth_client_secret", it) }
            clientId = cid
        }
        val pkce = oauth.pkce(); val state = oauth.state()
        putSecret(id, "oauth_verifier", pkce.verifier); putSecret(id, "oauth_state", state)
        setConfig(id, mapOf("oauth_pending" to hash(state)))
        c = conn(dao.get(id)!!)
        save(dao.get(id)!!.copy(state = "pending_auth"))
        record(id, "auth", "ok", "autorisation demandée au propriétaire")
        return oauth.authorizationUrl(server, clientId, OAuthClient.REDIRECT_URI, c.cfg("oauth_scopes")?.split(' ')?.filter { it.isNotBlank() }.orEmpty(), state, pkce.challenge, c.cfg("oauth_resource"))
    }

    /** The browser came back to Cortana's redirect URI. */
    suspend fun completeOAuth(redirect: String): ConnectionEntity {
        val u = okhttp3.HttpUrl.Companion.run { redirect.replaceFirst(Regex("^[a-z0-9.+-]+://"), "https://").toHttpUrlOrNull() }
            ?: throw ConnectionException("retour d'autorisation illisible")
        if (!redirect.startsWith(OAuthClient.REDIRECT_URI)) throw ConnectionException("adresse de retour inattendue")
        val state = u.queryParameter("state") ?: throw ConnectionException("retour d'autorisation sans état")
        val e = dao.all().firstOrNull { configOf(it)["oauth_pending"] == hash(state) } ?: throw ConnectionException("aucune autorisation en attente ne correspond (état inconnu ou déjà utilisé)")
        val c = conn(e)
        val expected = secret(c, "oauth_state")
        if (expected == null || !MessageDigest.isEqual(expected.toByteArray(), state.toByteArray())) throw ConnectionException("état OAuth invalide")
        u.queryParameter("error")?.let { err ->
            record(e.connectionId, "auth", "error", "autorisation refusée : $err ${u.queryParameter("error_description").orEmpty()}")
            clearPending(e.connectionId)
            throw ConnectionException("autorisation refusée : $err")
        }
        val code = u.queryParameter("code") ?: throw ConnectionException("retour d'autorisation sans code")
        val tokens = try {
            oauth.exchange(server(c), c.cfg("oauth_client_id")!!, secret(c, "oauth_client_secret"), code, secret(c, "oauth_verifier")!!, OAuthClient.REDIRECT_URI, c.cfg("oauth_resource"))
        } catch (x: OAuthException) { record(e.connectionId, "auth", "error", x.message ?: "échange refusé"); clearPending(e.connectionId); throw ConnectionException(x.message ?: "échange refusé") }
        storeTokens(e.connectionId, tokens)
        clearPending(e.connectionId)
        val out = dao.get(e.connectionId)!!.let { it.copy(state = "active", health = "unknown", failures = 0, lastError = null) }.also { save(it) }
        record(e.connectionId, "auth", "ok", "connecté${tokens.scope?.let { " (portées : $it)" } ?: ""}")
        audit.record("owner", "connection.authorize", e.name, "ok")
        return out
    }

    private suspend fun clearPending(id: String) { dropSecret(id, "oauth_state"); dropSecret(id, "oauth_verifier"); setConfig(id, mapOf("oauth_pending" to null)) }

    private suspend fun storeTokens(id: String, t: OAuthTokens) {
        putSecret(id, "oauth_access", t.accessToken)
        t.refreshToken?.let { putSecret(id, "oauth_refresh", it) }
        setConfig(id, mapOf("oauth_expires_at" to t.expiresAt?.toString(), "oauth_granted_scopes" to t.scope))
        t.scope?.let { s -> dao.get(id)?.let { save(it.copy(scopesJson = AppJson.encodeToString(ListSerializer(String.serializer()), s.split(' ').filter { x -> x.isNotBlank() }))) } }
    }

    /** A valid access token, refreshed shortly before expiry (one refresh at a time per connection). */
    suspend fun accessToken(c: Conn): String = locks.getOrPut(c.id) { Mutex() }.withLock {
        val e = dao.get(c.id) ?: throw ConnectionException("connexion inconnue")
        val cur = conn(e)
        val access = secret(cur, "oauth_access") ?: throw ConnectionException("connexion « ${e.name} » à autoriser")
        val exp = cur.config["oauth_expires_at"]?.toLongOrNull()
        if (exp == null || clock() < exp - 60_000) return@withLock access
        val refresh = secret(cur, "oauth_refresh")
        if (refresh == null) { needsAuth(e, "jeton expiré sans jeton de rafraîchissement"); throw ConnectionException("connexion « ${e.name} » expirée : reconnectez-la") }
        try {
            val t = oauth.refresh(server(cur), cur.cfg("oauth_client_id") ?: "", secret(cur, "oauth_client_secret"), refresh, cur.cfg("oauth_resource"))
            storeTokens(e.connectionId, t)
            record(e.connectionId, "refresh", "ok", "jeton renouvelé")
            t.accessToken
        } catch (x: OAuthException) {
            if (x.error == "invalid_grant" || x.error == "invalid_client" || x.error == "unauthorized_client") { needsAuth(e, "renouvellement refusé (${x.error})"); throw ConnectionException("connexion « ${e.name} » refusée par le fournisseur : reconnectez-la") }
            record(e.connectionId, "refresh", "error", x.message ?: "renouvellement impossible")
            throw ConnectionException("renouvellement du jeton impossible : ${x.message}")
        }
    }

    private suspend fun needsAuth(e: ConnectionEntity, why: String) {
        save((dao.get(e.connectionId) ?: e).copy(state = "pending_auth", lastError = why))
        record(e.connectionId, "refresh", "error", why)
        audit.record("cortana", "connection.expired", e.name, "error")
    }

    /** Bearer token of a connection for another subsystem (MCP); null when unusable. */
    suspend fun tokenFor(name: String): String? = try {
        val c = usable(name)
        if (c.entity.authScheme == "oauth2") accessToken(c) else secret(c, "token")
    } catch (e: ConnectionException) { null }

    /**
     * An OAuth connection for a protected resource (e.g. an MCP server, RFC 9728): its
     * authorization server is discovered from the resource; returns the URL to open.
     */
    suspend fun addOAuthForResource(name: String, resource: String, scopes: String?): Pair<ConnectionEntity, String> {
        checkUrl(resource)
        val issuer = oauth.authorizationServersFor(resource).first()
        val e = add("http", name, mapOf("base_url" to resource, "methods" to "POST", "oauth_issuer" to issuer, "oauth_resource" to resource, "oauth_scopes" to (scopes ?: "")), emptyMap(), "oauth2")
        return e to beginOAuth(e.connectionId)
    }

    // ------------------------------------------------------------------ health

    fun backoffMs(failures: Int): Long = if (failures == 0) 3_600_000L else (60_000L shl (failures - 1).coerceIn(0, 8)).coerceAtMost(6 * 3_600_000L)

    suspend fun check(id: String): ConnectionEntity? {
        val e = dao.get(id) ?: return null
        if (e.state != "active") return e
        val adapter = adapters[e.kind] ?: return e
        val now = clock()
        return try {
            val detail = withTimeout(30_000) { adapter.probe(conn(e)) }
            val ok = (dao.get(id) ?: e).copy(health = "ok", failures = 0, lastError = null, lastHealthAt = now)
            save(ok); record(id, "health", "ok", detail); ok
        } catch (x: CancellationException) { throw x } catch (x: Exception) {
            val f = e.failures + 1
            val msg = Redactor.redact(x.message ?: x.javaClass.simpleName).truncateBytes(300)
            val bad = (dao.get(id) ?: e).copy(health = if (f >= 3) "down" else "degraded", failures = f, lastError = msg, lastHealthAt = now)
            save(bad); record(id, "health", "error", msg); bad
        }
    }

    /** Maintenance: probes the active connections whose backoff has elapsed; purges old events. */
    suspend fun checkDue(): Int {
        var n = 0
        for (e in dao.all()) if (e.state == "active" && (e.lastHealthAt == null || clock() - e.lastHealthAt >= backoffMs(e.failures))) { check(e.connectionId); n++ }
        dao.purgeEvents(clock() - 30L * 24 * 3_600_000)
        return n
    }
}
