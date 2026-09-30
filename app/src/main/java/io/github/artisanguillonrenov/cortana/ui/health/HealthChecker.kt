package io.github.artisanguillonrenov.cortana.ui.health

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import io.github.artisanguillonrenov.cortana.AppContainer
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityBridge
import io.github.artisanguillonrenov.cortana.service.CortanaAccessibilityService
import kotlinx.coroutines.withTimeoutOrNull

enum class HealthStatus { OK, WARN, ERROR, INFO }

enum class Fix { ACCESSIBILITY, APP_INFO, NOTIFICATIONS, BATTERY, EXACT_ALARM, WRITE_SETTINGS, SECURITY, PROVIDERS, FOLDER, MICROPHONE, RESUME, NONE }

data class HealthItem(
    val id: String,
    val title: String,
    val status: HealthStatus,
    val detail: String,
    val fix: Fix = Fix.NONE,
    val fixLabel: String? = null,
)

/**
 * §14/§15 — diagnostics that stay meaningful even when nothing else is configured.
 * Each check detects its own state so onboarding can be re-run safely.
 */
class HealthChecker(private val context: Context, private val c: AppContainer) {
    private val pkg = context.packageName

    fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val cn = ComponentName(context, CortanaAccessibilityService::class.java)
        return enabled.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
    }

    fun accessibilityConnected(): Boolean = AccessibilityBridge.service.value != null

    /** "allowed" | "restricted" | "unknown" | "n/a" — Android 13+ restricted settings for sideloaded apps. */
    fun restrictedSettingsState(): String {
        if (Build.VERSION.SDK_INT < 33) return "n/a"
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java)
            when (ops.unsafeCheckOpNoThrow("android:access_restricted_settings", Process.myUid(), pkg)) {
                AppOpsManager.MODE_ALLOWED -> "allowed"
                AppOpsManager.MODE_DEFAULT -> "unknown"
                else -> "restricted"
            }
        } catch (t: Throwable) {
            "unknown"
        }
    }

    fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun microphoneGranted(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun ignoringBatteryOptimizations(): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)

    fun backgroundRestricted(): Boolean = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun exactAlarms(): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun biometricStatus(): Int = BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)

    /** Android 16 Advanced Protection may block accessibility for non-accessibility apps (§10). */
    fun advancedProtection(): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return false
        return try {
            val cls = Class.forName("android.security.advancedprotection.AdvancedProtectionManager")
            val mgr = context.getSystemService(cls) ?: return null
            cls.getMethod("isAdvancedProtectionEnabled").invoke(mgr) as? Boolean
        } catch (t: Throwable) {
            null
        }
    }

    fun intentFor(fix: Fix): Intent? = when (fix) {
        Fix.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Fix.APP_INFO -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        Fix.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        Fix.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
        Fix.EXACT_ALARM -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
        Fix.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$pkg"))
        Fix.SECURITY -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun run(checkProviders: Boolean = true): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        val halted = c.killSwitch.isHalted()
        items += HealthItem(
            "autonomy", "Autonomie", if (halted) HealthStatus.WARN else HealthStatus.OK,
            if (halted) "STOP actif : aucune action n'est autorisée. Reprise avec votre empreinte ou code." else "Active — le bouton STOP et la tuile de réglages rapides arrêtent tout immédiatement.",
            if (halted) Fix.RESUME else Fix.NONE, if (halted) "Reprendre" else null,
        )
        val providers = c.providers.all()
        val enabled = providers.filter { it.enabled }
        if (enabled.isEmpty()) {
            items += HealthItem("providers", "Fournisseur de modèle", HealthStatus.ERROR, "Aucun fournisseur configuré. Ajoutez Infermatic, OpenRouter, Groq…", Fix.PROVIDERS, "Configurer")
        } else if (checkProviders) {
            for (p in enabled) {
                val res = withTimeoutOrNull(12_000) { runCatching { c.providers.listModels(p.id, refresh = true) } }
                val ok = res?.isSuccess == true
                items += HealthItem(
                    "provider-${p.id}", "Fournisseur : ${p.displayName}",
                    when { !ok -> HealthStatus.ERROR; p.defaultModelId == null -> HealthStatus.WARN; else -> HealthStatus.OK },
                    when {
                        res == null -> "Pas de réponse (délai dépassé) — ${p.baseUrl}"
                        !ok -> res.exceptionOrNull()?.message ?: "Échec"
                        p.defaultModelId == null -> "Joignable (${res.getOrNull()?.size} modèles) mais aucun modèle choisi"
                        else -> "Joignable — ${res.getOrNull()?.size} modèles — modèle : ${p.defaultModelId}"
                    },
                    Fix.PROVIDERS, "Ouvrir",
                )
            }
        }
        val accEnabled = accessibilityEnabled()
        val restricted = restrictedSettingsState()
        items += HealthItem(
            "accessibility", "Accessibilité (contrôle de l'écran)",
            when { accEnabled && accessibilityConnected() -> HealthStatus.OK; accEnabled -> HealthStatus.WARN; else -> HealthStatus.ERROR },
            when {
                accEnabled && accessibilityConnected() -> "Service actif."
                accEnabled -> "Activé mais pas encore connecté. Désactivez puis réactivez Cortana dans Accessibilité."
                else -> "Désactivé. Paramètres → Accessibilité → Applications installées → Cortana → Activer."
            },
            if (accEnabled && accessibilityConnected()) Fix.NONE else Fix.ACCESSIBILITY, "Ouvrir Accessibilité",
        )
        if (!accEnabled) {
            items += HealthItem(
                "restricted", "Paramètres restreints (Android 13+)",
                when (restricted) { "allowed" -> HealthStatus.OK; "restricted" -> HealthStatus.ERROR; else -> HealthStatus.INFO },
                when (restricted) {
                    "allowed" -> "Autorisés : vous pouvez activer l'accessibilité."
                    "restricted" -> "Bloqués (application installée depuis un fichier). Infos de l'appli → ⋮ (en haut à droite) → « Autoriser les paramètres restreints », puis confirmez."
                    "n/a" -> "Non concerné."
                    else -> "État inconnu. Si l'interrupteur Cortana est grisé dans Accessibilité : Infos de l'appli → ⋮ → « Autoriser les paramètres restreints »."
                },
                if (restricted == "allowed" || restricted == "n/a") Fix.NONE else Fix.APP_INFO, "Infos de l'appli",
            )
        }
        when (advancedProtection()) {
            true -> items += HealthItem("aapm", "Protection avancée (Android 16)", HealthStatus.WARN,
                "La Protection avancée est activée : elle peut empêcher l'activation de l'accessibilité pour Cortana. Désactivez-la si le contrôle de l'écran est indispensable.", Fix.SECURITY, "Sécurité")
            null -> items += HealthItem("aapm", "Protection avancée (Android 16)", HealthStatus.INFO, "État non lisible sur cet appareil.")
            false -> Unit
        }
        items += HealthItem("notifications", "Notifications", if (notificationsGranted()) HealthStatus.OK else HealthStatus.ERROR,
            if (notificationsGranted()) "Autorisées (rappels, confirmations, STOP)." else "Refusées : les rappels ne pourront pas s'afficher.",
            if (notificationsGranted()) Fix.NONE else Fix.NOTIFICATIONS, "Autoriser")
        val battOk = ignoringBatteryOptimizations()
        items += HealthItem("battery", "Batterie (Samsung One UI)", if (battOk && !backgroundRestricted()) HealthStatus.OK else HealthStatus.WARN,
            if (battOk && !backgroundRestricted()) "Optimisation désactivée. Vérifiez aussi : Entretien de l'appareil → Batterie → Limites d'utilisation en arrière-plan → « Applis jamais en veille » → ajouter Cortana."
            else "One UI peut endormir Cortana (rappels en retard, tâches coupées). Réglez la batterie sur « Non restreinte » et ajoutez Cortana aux « Applis jamais en veille ».",
            if (battOk) Fix.APP_INFO else Fix.BATTERY, if (battOk) "Infos de l'appli" else "Désactiver l'optimisation")
        items += HealthItem("alarms", "Alarmes exactes", if (exactAlarms()) HealthStatus.OK else HealthStatus.WARN,
            if (exactAlarms()) "Autorisées : les rappels tombent à l'heure." else "Non autorisées : les rappels peuvent avoir quelques minutes de retard.",
            if (exactAlarms()) Fix.NONE else Fix.EXACT_ALARM, "Autoriser")
        items += HealthItem("write_settings", "Modifier les paramètres système", if (canWriteSettings()) HealthStatus.OK else HealthStatus.INFO,
            if (canWriteSettings()) "Autorisé (luminosité directe)." else "Optionnel : permet de régler la luminosité sans passer par l'écran des paramètres.",
            if (canWriteSettings()) Fix.NONE else Fix.WRITE_SETTINGS, "Autoriser")
        val bio = biometricStatus()
        items += HealthItem("biometric", "Empreinte / code de l'appareil",
            if (deviceSecure() && bio == BiometricManager.BIOMETRIC_SUCCESS) HealthStatus.OK else HealthStatus.ERROR,
            when {
                !deviceSecure() -> "Aucun verrouillage d'écran : les actions sensibles (L3) seront toujours refusées. Configurez un code et une empreinte."
                bio == BiometricManager.BIOMETRIC_SUCCESS -> "Disponible pour autoriser les actions sensibles et reprendre après STOP."
                else -> "Authentification forte indisponible (code $bio)."
            },
            if (deviceSecure()) Fix.NONE else Fix.SECURITY, "Sécurité")
        val folder = c.settings.current.workingFolderUri
        items += HealthItem("folder", "Dossier de travail (fichiers)", if (folder != null) HealthStatus.OK else HealthStatus.INFO,
            if (folder != null) "Autorisé : ${Uri.parse(folder).lastPathSegment}" else "Optionnel : choisissez un dossier pour que Cortana lise/modifie vos fichiers de projet.",
            Fix.FOLDER, if (folder != null) "Changer" else "Choisir")
        items += HealthItem("microphone", "Microphone (voix)", if (microphoneGranted()) HealthStatus.OK else HealthStatus.INFO,
            if (microphoneGranted()) "Autorisé (appuyer pour parler)." else "Optionnel : pour dicter vos demandes.",
            if (microphoneGranted()) Fix.NONE else Fix.MICROPHONE, "Autoriser")
        val chainBreak = runCatching { c.audit.verify() }.getOrNull()
        items += HealthItem("audit", "Journal d'audit", if (chainBreak == null) HealthStatus.OK else HealthStatus.ERROR,
            if (chainBreak == null) "Chaîne de hachage intacte." else "Chaîne rompue à l'entrée n°$chainBreak.")
        val spent = runCatching { c.gateway.spentToday() }.getOrDefault(0.0)
        val cap = c.settings.current.dailySpendCapUsd
        items += HealthItem("spend", "Dépense du jour", if (cap != null && spent >= cap) HealthStatus.WARN else HealthStatus.INFO,
            "%.4f \$".format(spent) + (cap?.let { " / plafond %.2f \$".format(it) } ?: " (aucun plafond ; coût connu seulement si le fournisseur le renvoie)"))
        return items
    }
}
