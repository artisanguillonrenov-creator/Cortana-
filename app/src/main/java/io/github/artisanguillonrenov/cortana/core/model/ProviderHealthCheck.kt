package io.github.artisanguillonrenov.cortana.core.model

import io.github.artisanguillonrenov.cortana.core.memory.ProviderEntity
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * On-demand check of one provider (owner's "Tester la connexion"): one model-list request, never a
 * generation, never a pod start. The outcome tells apart what the owner can act on: an obsolete
 * address, a stopped pod, a refused key, a missing model, a busy or failing server, a timeout.
 */
object ProviderHealthCheck {
    enum class Kind { OK, MODEL_MISSING, ADDRESS, POD_STOPPED, AUTH, BUSY, SERVER, TIMEOUT, NETWORK }

    data class Diagnosis(val kind: Kind, val message: String, val models: List<ModelDescriptor> = emptyList()) {
        val ok get() = kind == Kind.OK
    }

    fun isRunPodProxy(baseUrl: String): Boolean =
        runCatching { java.net.URI(baseUrl).host?.lowercase()?.endsWith(".proxy.runpod.net") == true }.getOrDefault(false)

    suspend fun run(p: ProviderEntity, provider: ModelProvider): Diagnosis {
        val pod = isRunPodProxy(p.baseUrl)
        val models = try {
            provider.listModels()
        } catch (e: Exception) {
            return classify(e, pod)
        }
        val wanted = p.defaultModelId
        return when {
            wanted != null && models.isNotEmpty() && models.none { it.id == wanted } -> Diagnosis(Kind.MODEL_MISSING,
                "Le serveur répond, mais le modèle « $wanted » n'y est pas (modèles proposés : ${models.take(5).joinToString { it.id }}). Choisissez-en un ci-dessous.", models)
            else -> Diagnosis(Kind.OK, "Connexion réussie — ${models.size} modèle(s) disponible(s).", models)
        }
    }

    fun classify(e: Throwable, pod: Boolean): Diagnosis {
        val stoppedHint = if (pod) " Le pod RunPod est peut-être arrêté : démarrez-le dans la console RunPod, ou vérifiez son adresse." else ""
        return when (e) {
            is ModelException -> when (val code = e.httpCode) {
                401, 403 -> Diagnosis(Kind.AUTH, "Accès refusé (HTTP $code) : clé absente ou incorrecte. Vérifiez la clé de ce fournisseur.")
                404 -> if (pod) Diagnosis(Kind.ADDRESS, "Adresse introuvable (HTTP 404) : le pod a peut-être été recréé avec une nouvelle adresse, ou n'est pas démarré. Corrigez l'adresse de base ci-dessus.$stoppedHint")
                       else Diagnosis(Kind.ADDRESS, "Adresse introuvable (HTTP 404) : vérifiez l'adresse de base (elle se termine souvent par /v1).")
                429 -> Diagnosis(Kind.BUSY, "Serveur saturé ou quota atteint (HTTP 429) : réessayez dans un moment.")
                502, 504 -> Diagnosis(if (pod) Kind.POD_STOPPED else Kind.SERVER, "Serveur injoignable derrière la passerelle (HTTP $code).$stoppedHint")
                503 -> Diagnosis(Kind.BUSY, "Serveur indisponible pour l'instant (HTTP 503) : modèle en cours de chargement ou serveur occupé. Réessayez dans une minute.$stoppedHint")
                null -> Diagnosis(Kind.NETWORK, e.message ?: "Erreur réseau.")
                else -> if (code >= 500) Diagnosis(Kind.SERVER, "Erreur du serveur (HTTP $code) : ${e.message}") else Diagnosis(Kind.SERVER, e.message ?: "Erreur HTTP $code.")
            }
            is UnknownHostException -> Diagnosis(Kind.ADDRESS, "Adresse inconnue (${e.message}) : vérifiez l'adresse de base, ou la connexion Internet de la tablette.${if (pod) " Un pod supprimé ou recréé change d'adresse." else ""}")
            is InterruptedIOException -> Diagnosis(Kind.TIMEOUT, "Pas de réponse à temps : serveur lent, surchargé ou arrêté.$stoppedHint")
            is ConnectException -> Diagnosis(if (pod) Kind.POD_STOPPED else Kind.NETWORK, "Connexion refusée : le serveur n'écoute pas à cette adresse.$stoppedHint")
            is SSLException -> Diagnosis(Kind.NETWORK, "Connexion sécurisée impossible (certificat ou protocole) : ${e.message}")
            else -> Diagnosis(Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
    }
}
