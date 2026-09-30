package io.github.artisanguillonrenov.cortana.ui.voice

import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.voice.VoicePhase
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer

/** Always-visible voice state (doc 05 §5): shown on every screen whenever a voice session exists. */
@Composable
fun VoiceIndicator() {
    val c = LocalContainer.current
    val st by c.voice.state.collectAsState()
    if (st.phase == VoicePhase.OFF) return
    val (label, color) = when (st.phase) {
        VoicePhase.LISTENING -> "🎙️ Cortana écoute" + (if (st.partial.isNotBlank()) " : « ${st.partial.takeLast(80)} »" else "…") to Cortana.colors.danger
        VoicePhase.THINKING -> "💭 « ${st.lastHeard?.take(60) ?: ""} » — réflexion…" to Cortana.colors.accent
        VoicePhase.SPEAKING -> "🔊 Cortana répond" + (if (st.micOn) " — parlez pour l'interrompre (micro ouvert)" else "") to (if (st.micOn) Cortana.colors.danger else Cortana.colors.accent)
        VoicePhase.OFF -> "" to Color.Transparent
    }
    Surface(color = color, contentColor = Cortana.colors.onAccent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (st.handsFree) Text("Mode mains libres" + (if (c.settings.current.wakeWordEnabled) " — dites « ${c.settings.current.wakePhrase} … »" else ""), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { c.voice.stop("Arrêt par le propriétaire") }) { Text("Arrêter l'écoute", color = Color.White) }
        }
    }
}

/** Hands-free toggle: asks for the microphone permission first; never starts listening on its own. */
@Composable
fun HandsFreeButton() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val st by c.voice.state.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) c.voice.startHandsFree() }
    val on = st.phase != VoicePhase.OFF && st.handsFree
    TextButton(onClick = {
        when {
            on -> c.voice.stop("Mode mains libres arrêté")
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> c.voice.startHandsFree()
            else -> ask.launch(Manifest.permission.RECORD_AUDIO)
        }
    }) { Text(if (on) "⏹️" else "🗣️", style = MaterialTheme.typography.titleLarge) }
}
