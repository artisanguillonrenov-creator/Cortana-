package io.github.artisanguillonrenov.cortana.ui.connections

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * The OAuth redirect URI (RFC 8252 private-use scheme). Any app could open it, so it carries no
 * authority by itself: the ConnectionManager accepts it only for a pending authorization whose
 * secret state matches, then exchanges the code with the PKCE verifier kept on the tablet.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data?.toString()
        val c = (application as CortanaApp).container
        if (uri != null) c.appScope.launch {
            val r = runCatching { c.connections.completeOAuth(uri) }
            c.notifications.owner(if (r.isSuccess) "Connexion autorisée" else "Autorisation refusée",
                r.fold({ "« ${it.name} » est connectée." }, { it.message ?: "échec de l'autorisation" }))
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
