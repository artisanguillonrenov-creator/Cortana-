package io.github.artisanguillonrenov.cortana.ui.approval

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalDecision
import io.github.artisanguillonrenov.cortana.core.policy.ApprovalRequest
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaTheme

/**
 * §9.6 — approval screen: FLAG_SECURE, overlays hidden, obscured touches filtered. Bound to one
 * request id; L3 always requires BiometricPrompt (strong biometric or device credential).
 */
class ApprovalActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        setShowWhenLocked(false)
        val broker = (application as CortanaApp).container.approvals
        setContent {
            CortanaTheme {
                val pending by broker.pending.collectAsState()
                val req = pending
                androidx.compose.runtime.LaunchedEffect(req == null) { if (req == null) finish() }
                if (req != null) {
                    ApprovalScreen(
                        req,
                        onApprove = { grant, dest -> approve(req, grant, dest) },
                        onRefuse = {
                            broker.resolve(req.id, ApprovalDecision(false, reason = "refusé"))
                            finish()
                        },
                    )
                }
            }
        }
        window.decorView.filterTouchesWhenObscured = true
    }

    private fun approve(req: ApprovalRequest, grant: Boolean, dest: Boolean) {
        val broker = (application as CortanaApp).container.approvals
        if (!req.biometric) {
            broker.resolve(req.id, ApprovalDecision(true, rememberGrant = grant, rememberDestination = dest))
            finish()
            return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            broker.resolve(req.id, ApprovalDecision(false, reason = "aucune empreinte ni code d'appareil configuré"))
            finish()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                broker.resolve(req.id, ApprovalDecision(true, rememberDestination = dest))
                finish()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Stay on screen: the owner may retry or refuse explicitly.
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Autoriser : ${req.action}")
                .setSubtitle(req.target ?: req.capability)
                .setDescription("Action sensible (${req.risk.label}). Cortana n'agira qu'après votre authentification.")
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(true)
                .build()
        )
    }

    companion object {
        const val EXTRA_ID = "approval_id"
    }
}

@Composable
private fun ApprovalScreen(req: ApprovalRequest, onApprove: (Boolean, Boolean) -> Unit, onRefuse: () -> Unit) {
    var grant by remember { mutableStateOf(false) }
    var dest by remember { mutableStateOf(false) }
    val riskColor = when (req.risk) {
        Risk.L3 -> Cortana.colors.danger
        Risk.L2 -> Cortana.colors.warning
        else -> Cortana.colors.success
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(20.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (req.biometric) "Autorisation par empreinte" else "Confirmation requise", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(req.risk.label, color = if (req.risk == Risk.L2) Cortana.colors.background else Cortana.colors.onAccent, modifier = Modifier.background(riskColor, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                LabeledValue("Action", req.action)
                req.target?.let { LabeledValue("Cible", it) }
                LabeledValue("Réversible", if (req.reversible) "Oui" else "Non — irréversible")
                if (req.reasons.isNotEmpty()) LabeledValue("Pourquoi cette demande", req.reasons.joinToString("\n• ", prefix = "• "))
                if (req.tainted) {
                    Text(
                        "⚠️ Cette tâche a lu du contenu non fiable (${req.taintSources.joinToString().ifBlank { "source externe" }}). Vérifiez que cette action correspond bien à ce que VOUS avez demandé.",
                        color = Cortana.colors.dangerText,
                    )
                }
                Text("Paramètres", style = MaterialTheme.typography.labelLarge)
                Text(req.params, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp))
                if (req.allowGrant) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(grant, { grant = it })
                        Text("Toujours autoriser « ${req.action} » (niveau L2 uniquement)")
                    }
                }
                if (req.destination != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(dest, { dest = it })
                        Text("Faire confiance à la destination « ${req.destination} »")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onRefuse, modifier = Modifier.weight(1f)) { Text("Refuser") }
                    Button(
                        onClick = { onApprove(grant, dest) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = riskColor),
                    ) { Text(if (req.biometric) "Autoriser (empreinte)" else "Autoriser") }
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
