package io.github.artisanguillonrenov.cortana.executors.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.github.artisanguillonrenov.cortana.core.memory.SettingsRepository
import io.github.artisanguillonrenov.cortana.core.permissions.Access
import io.github.artisanguillonrenov.cortana.core.permissions.AccessProbe
import io.github.artisanguillonrenov.cortana.service.CortanaAccessibilityService
import io.github.artisanguillonrenov.cortana.service.CortanaNotificationListener

/** Reads each access from Android as it is now (a revocation is seen at the next read). */
class AndroidAccessProbe(private val context: Context, private val settings: SettingsRepository) : AccessProbe {
    private fun perm(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun secureListContains(key: String, component: ComponentName) =
        Settings.Secure.getString(context.contentResolver, key)?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true

    override fun granted(a: Access): Boolean = runCatching {
        when (a) {
            Access.ACCESSIBILITY -> secureListContains(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ComponentName(context, CortanaAccessibilityService::class.java))
            Access.NOTIFICATION_LISTENER -> secureListContains("enabled_notification_listeners", ComponentName(context, CortanaNotificationListener::class.java))
            Access.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || perm(Manifest.permission.POST_NOTIFICATIONS)
            Access.CONTACTS -> perm(Manifest.permission.READ_CONTACTS)
            Access.CALENDAR -> perm(Manifest.permission.READ_CALENDAR)
            Access.PHONE -> perm(Manifest.permission.CALL_PHONE)
            Access.SMS -> perm(Manifest.permission.SEND_SMS)
            Access.EXACT_ALARMS -> Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            Access.WRITE_SETTINGS -> Settings.System.canWrite(context)
            Access.WORKING_FOLDER -> settings.current.workingFolderUri?.let { u -> context.contentResolver.persistedUriPermissions.any { it.uri.toString() == u && it.isWritePermission } } == true
            Access.DEVICE_SECURE -> context.getSystemService(KeyguardManager::class.java).isDeviceSecure
            Access.INSTALL_PACKAGES -> context.packageManager.canRequestPackageInstalls()
            Access.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }
    }.getOrDefault(false)

    companion object {
        /** The system screen where the owner grants or withdraws [a]. */
        fun fixIntent(context: Context, a: Access): Intent {
            val pkg = Uri.parse("package:${context.packageName}")
            return when (a) {
                Access.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                Access.NOTIFICATION_LISTENER -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                Access.POST_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                Access.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
                Access.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
                Access.DEVICE_SECURE -> Intent(Settings.ACTION_SECURITY_SETTINGS)
                Access.INSTALL_PACKAGES -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
                Access.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg) // runtime permissions and the working folder
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
