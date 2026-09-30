package io.github.artisanguillonrenov.cortana.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.artisanguillonrenov.cortana.CortanaApp
import io.github.artisanguillonrenov.cortana.executors.accessibility.AccessibilityBridge
import io.github.artisanguillonrenov.cortana.util.CLog

/**
 * The one component Android forces into its own system-bound service (§1). It only relays events
 * and hosts the automation indicator; all logic lives in [io.github.artisanguillonrenov.cortana.executors.accessibility.UiController].
 */
class CortanaAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var indicator: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.service.value = this
        CLog.i("accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        AccessibilityBridge.onEvent(event, packageName)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideIndicator()
        if (AccessibilityBridge.service.value === this) AccessibilityBridge.service.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideIndicator()
        if (AccessibilityBridge.service.value === this) AccessibilityBridge.service.value = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** §10 — TYPE_ACCESSIBILITY_OVERLAY indicator (no overlay permission needed) with STOP. */
    fun showIndicator() = main.post {
        if (indicator != null) return@post
        try {
            val wm = getSystemService(WindowManager::class.java)
            val stop = {
                (application as CortanaApp).container.killSwitch.halt("indicateur")
            }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.argb(230, 20, 20, 28)); setStroke(dp(2), Color.rgb(127, 227, 255)) }
                addView(TextView(this@CortanaAccessibilityService).apply {
                    text = "● Cortana contrôle l'écran"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                addView(Button(this@CortanaAccessibilityService).apply {
                    text = "STOP"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(200, 30, 30)) }
                    minHeight = dp(32); minimumHeight = dp(32)
                    setPadding(dp(14), 0, dp(14), 0)
                    setOnClickListener { stop() }
                    contentDescription = "Arrêter Cortana"
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(10) })
                setOnLongClickListener { stop(); true }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(28)
            }
            wm.addView(layout, lp)
            indicator = layout
        } catch (t: Throwable) {
            CLog.w("indicator failed", t)
        }
    }

    fun hideIndicator() = main.post {
        val v = indicator ?: return@post
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        indicator = null
    }
}
