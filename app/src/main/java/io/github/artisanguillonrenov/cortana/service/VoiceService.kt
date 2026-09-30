package io.github.artisanguillonrenov.cortana.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.util.CLog

/**
 * Microphone foreground service for a voice session (phase 17): the only way Cortana keeps the
 * microphone while the owner looks at another app, always with the "Cortana écoute" notification.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = (application as CortanaApp).container.notifications.listening()
        try {
            ServiceCompat.startForeground(this, Notifications.ID_LISTENING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (t: Throwable) {
            CLog.w("microphone foreground service refused", t)
            (application as CortanaApp).container.voice.stop("Micro en arrière-plan refusé par Android : gardez Cortana à l'écran")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java)) }
            .onFailure { CLog.w("voice service start refused", it) }.isSuccess

        fun stop(context: Context) { runCatching { context.stopService(Intent(context, VoiceService::class.java)) } }
    }
}

/** "Arrêter l'écoute" action of the listening notification. */
class VoiceStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) (context.applicationContext as CortanaApp).container.voice.stop("Écoute arrêtée depuis la notification")
    }

    companion object { const val ACTION = "io.github.artisanguillonrenov.cortana.VOICE_STOP" }
}
