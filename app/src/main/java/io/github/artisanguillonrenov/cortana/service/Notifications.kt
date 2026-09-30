package io.github.artisanguillonrenov.cortana.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.artisanguillonrenov.cortana.R
import io.github.artisanguillonrenov.cortana.ui.MainActivity
import io.github.artisanguillonrenov.cortana.util.CLog

class Notifications(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_REMINDERS, "Rappels", NotificationManager.IMPORTANCE_HIGH).apply { description = "Rappels que vous avez demandés à Cortana" },
                NotificationChannel(CH_APPROVALS, "Autorisations", NotificationManager.IMPORTANCE_HIGH).apply { description = "Demandes de confirmation d'actions sensibles" },
                NotificationChannel(CH_AUTOMATION, "Cortana au travail", NotificationManager.IMPORTANCE_LOW).apply { description = "Indique qu'une tâche est en cours (avec bouton STOP)" },
                NotificationChannel(CH_TASKS, "Messages de Cortana", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Résultats de tâches et messages de Cortana" },
            )
        )
    }

    fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33

    private fun openApp(requestCode: Int, sessionId: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        sessionId?.let { i.putExtra(MainActivity.EXTRA_SESSION, it) }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(id: Int, n: android.app.Notification) {
        if (!canPost()) {
            CLog.w("notification permission missing; notification dropped")
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            CLog.w("notify failed", e)
        }
    }

    fun reminder(scheduleId: String, title: String, message: String, late: Boolean) {
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_cortana)
            .setContentTitle(if (late) "Rappel (en retard)" else "Rappel")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(scheduleId.hashCode()))
            .setAutoCancel(true)
            .build()
        post(ID_REMINDER_BASE + (scheduleId.hashCode() and 0xffff), n)
    }

    fun owner(title: String, message: String, sessionId: String? = null) {
        val n = NotificationCompat.Builder(context, CH_TASKS)
            .setSmallIcon(R.drawable.ic_stat_cortana)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp(title.hashCode(), sessionId))
            .setAutoCancel(true)
            .build()
        post(ID_OWNER_BASE + ((title + message).hashCode() and 0xffff), n)
    }

    fun automation(objective: String, step: String): android.app.Notification {
        val stop = PendingIntent.getBroadcast(
            context, 1, Intent(context, KillSwitchReceiver::class.java).setAction(KillSwitchReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CH_AUTOMATION)
            .setSmallIcon(R.drawable.ic_stat_cortana)
            .setContentTitle("Cortana travaille")
            .setContentText(step.ifBlank { objective })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$objective\n$step"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(2))
            .addAction(R.drawable.ic_stop, "STOP", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Visible for the whole voice session (doc 05 §5: "indicateur microphone visible, aucune écoute cachée"). */
    fun listening(): android.app.Notification {
        val stop = PendingIntent.getBroadcast(
            context, 4, Intent(context, VoiceStopReceiver::class.java).setAction(VoiceStopReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CH_AUTOMATION)
            .setSmallIcon(R.drawable.ic_stat_cortana)
            .setContentTitle("🎙️ Cortana écoute")
            .setContentText("Mode vocal actif — touchez « Arrêter l'écoute » pour couper le micro.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(5))
            .addAction(R.drawable.ic_stop, "Arrêter l'écoute", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ID_LISTENING = 1003
        const val CH_REMINDERS = "reminders"
        const val CH_APPROVALS = "approvals"
        const val CH_AUTOMATION = "automation"
        const val CH_TASKS = "tasks"
        const val ID_AUTOMATION = 1001
        const val ID_APPROVAL = 1002
        const val ID_REMINDER_BASE = 20000
        const val ID_OWNER_BASE = 100000
    }
}
