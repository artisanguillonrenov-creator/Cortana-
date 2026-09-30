package io.github.artisanguillonrenov.cortana.executors.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.policy.DataEgress
import io.github.artisanguillonrenov.cortana.core.policy.Idempotency
import io.github.artisanguillonrenov.cortana.core.policy.Risk
import io.github.artisanguillonrenov.cortana.core.policy.RiskAssessment
import io.github.artisanguillonrenov.cortana.core.policy.SideEffect
import io.github.artisanguillonrenov.cortana.core.policy.UiRiskClassifier
import io.github.artisanguillonrenov.cortana.core.tools.S
import io.github.artisanguillonrenov.cortana.core.tools.ToolCategory
import io.github.artisanguillonrenov.cortana.core.tools.ToolDefinition
import io.github.artisanguillonrenov.cortana.core.tools.ToolResult
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityBridge
import io.github.artisanguillonrenov.cortana.util.arr
import io.github.artisanguillonrenov.cortana.util.bool
import io.github.artisanguillonrenov.cortana.util.int
import io.github.artisanguillonrenov.cortana.util.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class AppEntry(val label: String, val packageName: String)

/** §8.4 — alarms/timers (AlarmClock), volume (AudioManager), brightness (Settings.System), share, settings screens, apps, intents. */
class SystemExecutor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val classifier: UiRiskClassifier,
) {
    /** Prefer the accessibility service context: it is allowed to start activities from the background. */
    fun startActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val svc = AccessibilityBridge.service.value
        if (svc != null) svc.startActivity(intent) else context.startActivity(intent)
    }

    fun launchableApps(): List<AppEntry> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, PackageManager.MATCH_ALL).map {
            AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    fun findApp(query: String): AppEntry? {
        val apps = launchableApps()
        apps.firstOrNull { it.packageName == query }?.let { return it }
        val q = UiRiskClassifier.normalize(query.trim())
        return apps.firstOrNull { UiRiskClassifier.normalize(it.label) == q }
            ?: apps.firstOrNull { UiRiskClassifier.normalize(it.label).startsWith(q) }
            ?: apps.firstOrNull { UiRiskClassifier.normalize(it.label).contains(q) }
    }

    private val settingsScreens: Map<String, String> = linkedMapOf(
        "main" to Settings.ACTION_SETTINGS,
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "notifications" to Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "nfc" to Settings.ACTION_NFC_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
        "wireless" to Settings.ACTION_WIRELESS_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS,
        "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
        "write_settings" to Settings.ACTION_MANAGE_WRITE_SETTINGS,
        "battery_optimization" to Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        "app_details" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    )
    private val securityScreens = setOf("security", "privacy", "developer", "accessibility")

    fun openSettings(screen: String, pkg: String? = null): Boolean {
        val action = settingsScreens[screen] ?: return false
        val intent = Intent(action)
        if (screen == "app_details" || screen == "write_settings") intent.data = Uri.parse("package:" + (pkg ?: context.packageName))
        return runCatching { startActivity(intent); true }.getOrElse {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)); true }.getOrDefault(false)
        }
    }

    private fun streamOf(name: String?): Int = when (name) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else -> AudioManager.STREAM_MUSIC
    }

    fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "android.alarm.create", "Crée une alarme dans l'application Horloge.",
            S.obj(
                "hour" to S.int("Heure 0-23", 0, 23), "minute" to S.int("Minute 0-59", 0, 59), "label" to S.str("Libellé"),
                "days" to S.arr("Jours de répétition 1=lundi … 7=dimanche", S.int("jour", 1, 7)),
                required = listOf("hour", "minute"),
            ),
            Risk.L2, SideEffect.REVERSIBLE, Idempotency.KEYED, DataEgress.LOCAL, ToolCategory.SYSTEM, label = "Créer une alarme",
        ) { a, _ ->
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.int("hour")!!)
                .putExtra(AlarmClock.EXTRA_MINUTES, a.int("minute")!!)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            a.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            a.arr("days")?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.takeIf { it.isNotEmpty() }?.let { d ->
                // Calendar: SUNDAY=1 … SATURDAY=7 ; ours: 1=lundi … 7=dimanche
                i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(d.map { if (it == 7) 1 else it + 1 }))
            }
            runCatching { startActivity(i); ToolResult.ok("Alarme demandée pour %02d:%02d".format(a.int("hour"), a.int("minute"))) }
                .getOrElse { ToolResult.error("Aucune application d'horloge n'accepte la création d'alarme : ${it.message}") }
        },
        ToolDefinition(
            "android.timer.create", "Lance un minuteur dans l'application Horloge.",
            S.obj("seconds" to S.int("Durée en secondes", 1, 86400), "label" to S.str("Libellé"), required = listOf("seconds")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.KEYED, DataEgress.LOCAL, ToolCategory.SYSTEM, label = "Lancer un minuteur",
        ) { a, _ ->
            val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.int("seconds")!!).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            a.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            runCatching { startActivity(i); ToolResult.ok("Minuteur de ${a.int("seconds")} s lancé") }.getOrElse { ToolResult.error("Minuteur impossible : ${it.message}") }
        },
        ToolDefinition(
            "android.system.volume.set", "Règle le volume (media, ring, alarm, notification) en pourcentage.",
            S.obj("stream" to S.str("Flux", listOf("media", "ring", "alarm", "notification")), "percent" to S.int("0-100", 0, 100), required = listOf("percent")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SYSTEM, label = "Régler le volume",
        ) { a, _ ->
            val am = context.getSystemService(AudioManager::class.java)
            val st = streamOf(a.str("stream"))
            val max = am.getStreamMaxVolume(st)
            val v = (max * (a.int("percent")!! / 100.0)).toInt().coerceIn(0, max)
            runCatching { am.setStreamVolume(st, v, AudioManager.FLAG_SHOW_UI); ToolResult.ok("Volume ${a.str("stream") ?: "media"} réglé à ${a.int("percent")} % ($v/$max)") }
                .getOrElse { ToolResult.error("Réglage refusé (mode Ne pas déranger ?) : ${it.message}") }
        },
        ToolDefinition(
            "android.system.brightness.set", "Règle la luminosité de l'écran en pourcentage (désactive la luminosité adaptative) ou active le mode automatique.",
            S.obj("percent" to S.int("0-100", 0, 100), "auto" to S.bool("Activer la luminosité adaptative")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SYSTEM, label = "Régler la luminosité",
        ) { a, _ ->
            if (!Settings.System.canWrite(context)) {
                openSettings("write_settings")
                return@ToolDefinition ToolResult.error("Permission « Modifier les paramètres système » non accordée. L'écran d'autorisation vient d'être ouvert ; sinon, passez par l'interface (android_settings_open display puis le curseur).")
            }
            val cr = context.contentResolver
            if (a.bool("auto") == true) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return@ToolDefinition ToolResult.ok("Luminosité adaptative activée")
            }
            val pct = a.int("percent") ?: return@ToolDefinition ToolResult.error("percent requis")
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(1, 255))
            ToolResult.ok("Luminosité réglée à $pct %")
        },
        ToolDefinition(
            "android.share", "Ouvre la feuille de partage Android avec un texte.",
            S.obj("text" to S.str("Texte à partager"), "subject" to S.str("Sujet"), required = listOf("text")),
            Risk.L2, SideEffect.EXTERNAL, Idempotency.KEYED, DataEgress.EXTERNAL, ToolCategory.SYSTEM, label = "Partager un texte",
            destinationOf = { "partage Android" },
        ) { a, _ ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.str("text"))
            a.str("subject")?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
            runCatching { startActivity(Intent.createChooser(send, "Partager via")); ToolResult.ok("Feuille de partage ouverte") }
                .getOrElse { ToolResult.error("Partage impossible : ${it.message}") }
        },
        ToolDefinition(
            "android.settings.open", "Ouvre un écran des Paramètres Android.",
            S.obj("screen" to S.str("Écran", settingsScreens.keys.toList()), "package" to S.str("Paquet (pour app_details)"), required = listOf("screen")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.SYSTEM, label = "Ouvrir les paramètres",
            riskClassifier = { a, _ ->
                if (a.str("screen") in securityScreens) RiskAssessment(Risk.L2, listOf("Écran de sécurité/confidentialité signalé"), targetDescription = a.str("screen"))
                else null
            },
        ) { a, ctx ->
            ctx.markUiAutomation()
            if (openSettings(a.str("screen")!!, a.str("package"))) {
                delay(800)
                ToolResult.ok("Écran « ${a.str("screen")} » ouvert. Observe l'écran avant d'agir.")
            } else ToolResult.error("Écran inconnu ou indisponible")
        },
        ToolDefinition(
            "android.app.open", "Ouvre une application installée par son nom (ou nom de paquet).",
            S.obj("name" to S.str("Nom affiché ou paquet, ex. « Chrome »"), required = listOf("name")),
            Risk.L1, SideEffect.REVERSIBLE, Idempotency.INTRINSIC, DataEgress.NONE, ToolCategory.UI, label = "Ouvrir une application",
            riskClassifier = { a, _ ->
                val app = findApp(a.str("name").orEmpty())
                when {
                    app == null -> null
                    classifier.isSensitiveApp(app.packageName) && app.packageName !in settings.current.sensitiveAllowlist ->
                        RiskAssessment(Risk.L3, listOf("Application sensible"), deny = true, denyReason = "Automatisation interdite dans ${app.label} (application sensible). Autorisez-la dans Réglages si nécessaire.", targetDescription = app.label)
                    classifier.isSensitiveApp(app.packageName) -> RiskAssessment(Risk.L3, listOf("Application sensible autorisée"), targetDescription = app.label)
                    else -> RiskAssessment(Risk.L1, targetDescription = "${app.label} (${app.packageName})")
                }
            },
        ) { a, ctx ->
            val app = findApp(a.str("name").orEmpty()) ?: return@ToolDefinition ToolResult.error("Application introuvable : ${a.str("name")}. Applications disponibles : " + launchableApps().take(60).joinToString { it.label })
            val launch = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return@ToolDefinition ToolResult.error("Impossible de lancer ${app.label}")
            ctx.markUiAutomation()
            startActivity(launch)
            delay(1200)
            ToolResult.ok("${app.label} ouverte (${app.packageName}). Observe l'écran avant d'agir.")
        },
        ToolDefinition(
            "android.intent.open", "Ouvre un lien ou un lien profond (https, geo:, tel: en numérotation, mailto:, market:…).",
            S.obj("uri" to S.str("URI à ouvrir"), required = listOf("uri")),
            Risk.L1, SideEffect.EXTERNAL, Idempotency.INTRINSIC, DataEgress.EXTERNAL, ToolCategory.UI, label = "Ouvrir un lien",
            destinationOf = { a -> a.str("uri")?.let { Uri.parse(it) }?.let { u -> u.host ?: u.scheme } },
            riskClassifier = { a, _ -> classifyUri(a.str("uri").orEmpty()) },
        ) { a, ctx ->
            val uri = Uri.parse(a.str("uri"))
            val action = if (uri.scheme == "tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW
            ctx.markUiAutomation()
            runCatching { startActivity(Intent(action, uri)); delay(1000); ToolResult.ok("Lien ouvert : $uri. Observe l'écran avant d'agir.") }
                .getOrElse { ToolResult.error("Aucune application ne gère ce lien : ${it.message}") }
        },
    )

    fun classifyUri(raw: String): RiskAssessment {
        val u = Uri.parse(raw.trim())
        val scheme = u.scheme?.lowercase()
        return when {
            scheme == null -> RiskAssessment(Risk.L1, deny = true, denyReason = "URI sans schéma")
            scheme in setOf("intent", "file", "content", "javascript", "data", "android-app") ->
                RiskAssessment(Risk.L3, deny = true, denyReason = "Schéma « $scheme » refusé pour des raisons de sécurité")
            scheme in setOf("tel", "sms", "smsto", "mailto", "mms") -> RiskAssessment(Risk.L2, listOf("Communication ($scheme)"), targetDescription = raw)
            scheme in setOf("upi", "paypal", "bitcoin", "ethereum", "lydia") || raw.contains("pay", ignoreCase = true) && scheme !in setOf("http", "https") ->
                RiskAssessment(Risk.L3, listOf("Lien de paiement"), targetDescription = raw)
            else -> RiskAssessment(Risk.L1, targetDescription = raw)
        }
    }
}
