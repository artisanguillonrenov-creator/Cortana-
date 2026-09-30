package io.github.artisanguillonrenov.cortana.ui.common

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.artisanguillonrenov.cortana.ui.health.Fix
import kotlinx.coroutines.launch

/** Resumes autonomy after STOP — requires the device credential (§9.4). Provided by MainActivity. */
val LocalResumeAutonomy = compositionLocalOf<() -> Unit> { {} }

/** Returns a handler that performs a Health/Onboarding fix: permission request, SAF picker or system screen. */
@Composable
fun rememberFixHandler(onProviders: () -> Unit, onChanged: () -> Unit = {}): (Fix) -> Unit {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val resume = LocalResumeAutonomy.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        onChanged()
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            scope.launch {
                c.settings.update { it.copy(workingFolderUri = uri.toString()) }
                c.audit.record("owner", "files.folder_granted", uri.lastPathSegment, "ok")
                onChanged()
            }
        }
    }
    return { fix ->
        when (fix) {
            Fix.PROVIDERS -> onProviders()
            Fix.RESUME -> resume()
            Fix.FOLDER -> runCatching { folder.launch(null) }
            Fix.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else c.health.intentFor(fix)?.let { runCatching { ctx.startActivity(it) } }
            Fix.MICROPHONE -> permission.launch(Manifest.permission.RECORD_AUDIO)
            Fix.NONE -> Unit
            else -> {
                val i = c.health.intentFor(fix)
                val ok = i != null && runCatching { ctx.startActivity(i) }.isSuccess
                if (!ok) runCatching { ctx.startActivity(c.health.intentFor(Fix.APP_INFO)) }
            }
        }
    }
}
