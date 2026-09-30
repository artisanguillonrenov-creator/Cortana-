package io.github.artisanguillonrenov.cortana.core.connections

import io.github.artisanguillonrenov.cortana.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class OAuthException(val error: String, message: String) : Exception(message)

@Serializable
data class OAuthServer(
    val issuer: String? = null,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String? = null,
    val registrationEndpoint: String? = null,
    val scopesSupported: List<String> = emptyList(),
)

data class OAuthTokens(val accessToken: String, val refreshToken: String?, val expiresAt: Long?, val scope: String?)

/**
 * OAuth 2.1 for native apps (RFC 9700 / 8252): authorization code with PKCE S256 only, exact
 * redirect URI, state checked, tokens in the secret store, refresh rotation honoured, revocation
 * (RFC 7009) on disconnect. Discovery by authorization-server metadata (RFC 8414, OIDC fallback)
 * or from a protected resource (RFC 9728, as MCP servers advertise); dynamic client registration
 * (RFC 7591) when no client id is configured. Endpoints must be https, except on the loopback or
 * local network the owner chose.
 */
class OAuthClient(private val http: OkHttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = "application/json".toMediaType()

    fun check(url: String): HttpUrl {
        val u = url.toHttpUrlOrNull() ?: throw OAuthException("invalid_endpoint", "adresse OAuth invalide : $url")
        if (!u.isHttps && !localHost(u.host)) throw OAuthException("insecure_endpoint", "OAuth exige https (${u.host})")
        return u
    }

    private suspend fun getJson(url: HttpUrl): JsonObject? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            runCatching { AppJson.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject }.getOrNull()
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** RFC 8414 (well-known inserted before the issuer path), then OpenID configuration. */
    suspend fun discover(issuer: String): OAuthServer {
        val u = check(issuer)
        val path = u.encodedPath.trimEnd('/')
        val base = u.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')
        val candidates = listOf("$base/.well-known/oauth-authorization-server$path", "$base/.well-known/openid-configuration$path", "$base$path/.well-known/openid-configuration").distinct()
        for (c in candidates) {
            val o = getJson(check(c)) ?: continue
            val auth = o.s("authorization_endpoint") ?: continue
            val token = o.s("token_endpoint") ?: continue
            return OAuthServer(o.s("issuer"), check(auth).toString(), check(token).toString(), o.s("revocation_endpoint")?.let { check(it).toString() },
                o.s("registration_endpoint")?.let { check(it).toString() }, (o["scopes_supported"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
        }
        throw OAuthException("discovery_failed", "métadonnées OAuth introuvables pour $issuer")
    }

    /** RFC 9728: the authorization servers a protected resource (e.g. an MCP server) names. */
    suspend fun authorizationServersFor(resource: String): List<String> {
        val u = check(resource)
        val path = u.encodedPath.trimEnd('/')
        val base = u.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')
        for (c in listOf("$base/.well-known/oauth-protected-resource$path", "$base/.well-known/oauth-protected-resource").distinct()) {
            val o = getJson(check(c)) ?: continue
            val servers = (o["authorization_servers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if (servers.isNotEmpty()) return servers
        }
        throw OAuthException("discovery_failed", "la ressource $resource n'annonce aucun serveur d'autorisation")
    }

    /** RFC 7591 dynamic registration as a public native client. Returns (client_id, client_secret?). */
    suspend fun register(server: OAuthServer, redirectUri: String, clientName: String = "Cortana"): Pair<String, String?> = withContext(Dispatchers.IO) {
        val endpoint = check(server.registrationEndpoint ?: throw OAuthException("no_registration", "ce serveur n'accepte pas l'enregistrement automatique : indiquez un identifiant client"))
        val body = buildJsonObject {
            put("client_name", clientName); putJsonArray("redirect_uris") { add(JsonPrimitive(redirectUri)) }
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none"); put("application_type", "native")
        }
        http.newCall(Request.Builder().url(endpoint).post(body.toString().toRequestBody(json)).build()).execute().use { r ->
            val o = runCatching { AppJson.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject }.getOrNull()
            if (!r.isSuccessful || o?.s("client_id") == null) throw OAuthException(o?.s("error") ?: "registration_failed", "enregistrement du client refusé (HTTP ${r.code})")
            o.s("client_id")!! to o.s("client_secret")
        }
    }

    data class Pkce(val verifier: String, val challenge: String)

    fun pkce(): Pkce {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val r = SecureRandom()
        val v = (1..64).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        return Pkce(v, Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII))))
    }

    fun state(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun authorizationUrl(server: OAuthServer, clientId: String, redirectUri: String, scopes: List<String>, state: String, challenge: String, resource: String?): String =
        check(server.authorizationEndpoint).newBuilder()
            .addQueryParameter("response_type", "code").addQueryParameter("client_id", clientId).addQueryParameter("redirect_uri", redirectUri)
            .apply { if (scopes.isNotEmpty()) addQueryParameter("scope", scopes.joinToString(" ")) }
            .addQueryParameter("state", state).addQueryParameter("code_challenge", challenge).addQueryParameter("code_challenge_method", "S256")
            .apply { resource?.let { addQueryParameter("resource", it) } }
            .build().toString()

    private suspend fun token(server: OAuthServer, clientId: String, clientSecret: String?, form: FormBody.Builder): OAuthTokens = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(check(server.tokenEndpoint)).header("Accept", "application/json")
        if (clientSecret != null) req.header("Authorization", okhttp3.Credentials.basic(clientId, clientSecret)) else form.add("client_id", clientId)
        http.newCall(req.post(form.build()).build()).execute().use { r ->
            val o = runCatching { AppJson.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject }.getOrNull()
            if (!r.isSuccessful || o?.s("access_token") == null) {
                val err = o?.s("error") ?: "token_error"
                throw OAuthException(err, "jeton refusé par le serveur : $err${o?.s("error_description")?.let { " ($it)" } ?: ""}")
            }
            val type = o.s("token_type") ?: "Bearer"
            if (!type.equals("bearer", true)) throw OAuthException("unsupported_token_type", "type de jeton non pris en charge : $type")
            OAuthTokens(o.s("access_token")!!, o.s("refresh_token"), o.s("expires_in")?.toLongOrNull()?.let { clock() + it * 1000 }, o.s("scope"))
        }
    }

    suspend fun exchange(server: OAuthServer, clientId: String, clientSecret: String?, code: String, verifier: String, redirectUri: String, resource: String?): OAuthTokens =
        token(server, clientId, clientSecret, FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirectUri)
            .add("code_verifier", verifier).apply { resource?.let { add("resource", it) } })

    suspend fun refresh(server: OAuthServer, clientId: String, clientSecret: String?, refreshToken: String, resource: String?): OAuthTokens =
        token(server, clientId, clientSecret, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).apply { resource?.let { add("resource", it) } })

    /** RFC 7009. Returns false when the server has no revocation endpoint. */
    suspend fun revoke(server: OAuthServer, clientId: String, clientSecret: String?, token: String, hint: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = server.revocationEndpoint ?: return@withContext false
        val form = FormBody.Builder().add("token", token).add("token_type_hint", hint)
        val req = Request.Builder().url(check(endpoint))
        if (clientSecret != null) req.header("Authorization", okhttp3.Credentials.basic(clientId, clientSecret)) else form.add("client_id", clientId)
        http.newCall(req.post(form.build()).build()).execute().use { r -> if (!r.isSuccessful) throw OAuthException("revocation_failed", "révocation refusée (HTTP ${r.code})") }
        true
    }

    companion object {
        const val REDIRECT_URI = "io.github.artisanguillonrenov.cortana://oauth2redirect"

        fun localHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            if (!(h.matches(Regex("[0-9.]+")) || h.contains(':'))) return false
            val a = runCatching { java.net.InetAddress.getByName(h) }.getOrNull() ?: return false
            return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
        }
    }
}
