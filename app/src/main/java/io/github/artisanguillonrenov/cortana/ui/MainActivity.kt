package io.github.artisanguillonrenov.cortana.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.LocalResumeAutonomy
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val openSession = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CortanaApp).container
        val startOnboarding = !container.settings.current.onboardingDone
        handleIntent(intent)
        setContent {
            val settings by container.settings.state.collectAsState()
            CortanaTheme(highContrast = settings.chat.theme == "contrast", reduceMotion = settings.chat.reduceMotion, devMode = settings.chat.developer) {
                CompositionLocalProvider(
                    LocalContainer provides container, LocalResumeAutonomy provides ::resumeAutonomy,
                    // Web results in the conversation: pictures and inline video through the guarded web client only.
                    io.github.artisanguillonrenov.cortana.ui.components.LocalWebMedia provides
                        io.github.artisanguillonrenov.cortana.ui.components.WebMediaAccess(container.remoteImages, container.web.client),
                ) {
                    AppNav(startOnboarding, openSession.value, onSessionConsumed = { openSession.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SESSION)?.let { openSession.value = it }
        io.github.artisanguillonrenov.cortana.ui.workspace.ShareInbox.parse(intent)?.let { io.github.artisanguillonrenov.cortana.ui.workspace.ShareInbox.offer(it) }
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true) {
            intent.removeExtra(EXTRA_RESUME)
            window.decorView.post { resumeAutonomy() }
        }
    }

    /** §9.4 — resuming after STOP requires the device biometric/credential and is audited. */
    fun resumeAutonomy() {
        val c = (application as CortanaApp).container
        if (!c.killSwitch.isHalted()) return
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Nothing to verify against: resume, but record it and warn the owner.
            c.killSwitch.resume("app (appareil sans verrouillage)")
            c.appScope.launch { c.audit.record("owner", "kill_switch.resume_unverified", null, "active") }
            Toast.makeText(this, "Reprise sans vérification : configurez un code et une empreinte (Santé).", Toast.LENGTH_LONG).show()
            return
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                c.killSwitch.resume("app")
                Toast.makeText(this@MainActivity, "Autonomie réactivée", Toast.LENGTH_SHORT).show()
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Reprendre l'autonomie de Cortana")
                .setSubtitle("Confirmez avec votre empreinte ou votre code")
                .setAllowedAuthenticators(auth)
                .build()
        )
    }

    companion object {
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_RESUME = "resume_autonomy"
    }
}
