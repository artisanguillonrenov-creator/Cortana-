package io.github.artisanguillonrenov.cortana.ui.health

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.artisanguillonrenov.cortana.service.CortanaNotificationListener
import io.github.artisanguillonrenov.cortana.ui.common.LocalContainer
import io.github.artisanguillonrenov.cortana.ui.common.SectionCard

/** Communications permissions (phase 18): each one asked separately, only when the owner taps it. */
@Composable
fun CommsCard() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val listener = ComponentName(ctx, CortanaNotificationListener::class.java).flattenToString()
    val notifAccess = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(listener) == true
    val rows = listOf(
        Triple("Contacts", arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), "chercher et ajouter des contacts"),
        Triple("Agenda", arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), "consulter et gérer les événements"),
    ) + (if (c.telephony.canCall) listOf(Triple("Appels", arrayOf(Manifest.permission.CALL_PHONE), "appeler après votre accord")) else emptyList()) +
        (if (c.telephony.canSms) listOf(Triple("SMS", arrayOf(Manifest.permission.SEND_SMS), "envoyer après votre accord")) else emptyList())
    SectionCard("Communications") {
        tick.let { }
        rows.forEach { (label, perms, why) ->
            val ok = perms.all(::has)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (ok) "✅" else "⚪"} $label — $why", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (!ok) OutlinedButton(onClick = { ask.launch(perms) }) { Text("Autoriser") }
            }
        }
        if (!c.telephony.canCall && !c.telephony.canSms) Text("Cette tablette n'a pas de téléphonie : appels et SMS ne sont pas proposés (« Préparer » ouvre seulement les applications si elles existent).", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${if (notifAccess) "✅" else "⚪"} Notifications — lire celles des applications choisies (Réglages → Notifications)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (!notifAccess) OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Ouvrir") }
        }
    }
}
