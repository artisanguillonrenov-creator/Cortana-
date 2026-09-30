package io.github.artisanguillonrenov.cortana.executors.system

import android.content.Context
import android.content.pm.PackageManager
import io.github.artisanguillonrenov.cortana.core.skills.SkillEnvironment
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityBridge

/** Device facts a skill checks before each step: foreground app and installed app versions. */
class AndroidSkillEnvironment(private val context: Context) : SkillEnvironment {
    override fun foregroundPackage(): String? = AccessibilityBridge.foregroundPackage

    override fun installedVersion(packageName: String): String? = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
