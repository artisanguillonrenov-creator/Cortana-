package io.github.artisanguillonrenov.cortana.fixture

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View

/**
 * Phase 16 fixture: a screen drawn on a Canvas and hidden from accessibility — the tree has
 * nothing to act on, so only the vision fallback (capture + OCR) can find « Valider ».
 */
class CanvasFixtureActivity : Activity() {
    @Volatile var validated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(CanvasScreen(this))
    }

    private inner class CanvasScreen(ctx: Context) : View(ctx) {
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
        private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 72f; isFakeBoldText = true }
        private val fill = Paint().apply { color = Color.rgb(30, 136, 229) }
        private val button = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS; setBackgroundColor(Color.WHITE) }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawText("Formulaire de test", 60f, 220f, text)
            if (validated) { canvas.drawText("Envoyé", 60f, h / 2, text); return }
            button.set(w * 0.25f, h * 0.6f, w * 0.75f, h * 0.6f + 180f)
            canvas.drawRoundRect(button, 24f, 24f, fill)
            canvas.drawText("Valider", button.centerX() - buttonText.measureText("Valider") / 2, button.centerY() + 26f, buttonText)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP && button.contains(e.x, e.y)) { validated = true; invalidate() }
            return true
        }
    }
}
