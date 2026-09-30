package io.github.artisanguillonrenov.cortana.executors.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.core.update.ApkInfo
import io.github.artisanguillonrenov.cortana.core.update.ApkInspector
import io.github.artisanguillonrenov.cortana.core.update.ApkInstaller
import io.github.artisanguillonrenov.cortana.core.update.InstalledApp
import io.github.artisanguillonrenov.cortana.core.update.UpdateException
import io.github.artisanguillonrenov.cortana.core.update.UpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

private fun Signature.certificate(): X509Certificate = CertificateFactory.getInstance("X.509").generateCertificate(toByteArray().inputStream()) as X509Certificate

/** The installed Cortana as PackageManager reports it: version and the keys that signed it. */
fun installedApp(context: Context): InstalledApp {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val signers = info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }?.toList().orEmpty()
    return InstalledApp(
        context.packageName, info.longVersionCode, Build.VERSION.SDK_INT,
        signers.mapNotNull { runCatching { it.certificate().publicKey }.getOrNull() },
        signers.map { UpdateService.sha256Hex(it.toByteArray()) }.toSet(),
    )
}

/** Reads a downloaded APK exactly as the installer will (package, version, signers, minimum SDK). */
class AndroidApkInspector(private val context: Context) : ApkInspector {
    override fun inspect(apk: File): ApkInfo? {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        val signers = info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }?.toList().orEmpty()
        return ApkInfo(info.packageName, info.longVersionCode, info.applicationInfo?.minSdkVersion ?: 0, signers.map { UpdateService.sha256Hex(it.toByteArray()) }.toSet())
    }
}

/**
 * Hands the APK to PackageInstaller; Android shows its own confirmation to the owner (never a
 * silent install). Needs "install unknown apps" allowed for Cortana.
 */
class AndroidApkInstaller(private val context: Context) : ApkInstaller {
    override suspend fun handOff(apk: File): String = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw UpdateException("autorisez d'abord Cortana à installer des applications (réglage ouvert), puis relancez")
        }
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("cortana.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender)
        }
        "installation confiée à Android (session $id) : confirmez dans la fenêtre du système"
    }
}

/** Receives PackageInstaller's status: shows the system confirmation, reports failures. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the new version starts; its doctor runs from Santé
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $status"
                runCatching { (context.applicationContext as CortanaApp).container.notifications.owner("Mise à jour non installée", msg) }
            }
        }
    }
}
