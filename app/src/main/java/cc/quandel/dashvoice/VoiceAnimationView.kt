package cc.quandel.dashvoice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.view.View

/**
 * Full-screen centered voice animation: expanding ripple rings + solid center dot.
 * Call setState() to switch colors, start()/stop() to show/hide.
 */
class VoiceAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State(val color: Int) {
        LISTENING(Color.parseColor("#4CAF50")),   // green
        THINKING(Color.parseColor("#2196F3")),    // blue
        SPEAKING(Color.parseColor("#9C27B0"))     // purple
    }

    private var state = State.LISTENING
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = minOf(cx, cy) * 0.85f
        val dotRadius = minOf(cx, cy) * 0.18f
        val phase = (animator.animatedValue as? Float) ?: 0f
        val c = state.color

        ringPaint.strokeWidth = minOf(cx, cy) * 0.04f

        // Draw 3 rings staggered by 1/3 phase each
        for (i in 0..2) {
            val p = ((phase + i / 3f) % 1f)
            // Rings start small and expand, fading out
            val radius = dotRadius + p * (maxRadius - dotRadius)
            val alpha = ((1f - p) * 220).toInt().coerceIn(0, 255)
            ringPaint.color = c
            ringPaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        // Solid center dot — pulses slightly in size
        val pulse = 1f + 0.12f * Math.sin(phase * 2 * Math.PI).toFloat()
        dotPaint.color = c
        dotPaint.alpha = 255
        canvas.drawCircle(cx, cy, dotRadius * pulse, dotPaint)

        // Smaller white core for depth
        dotPaint.color = Color.WHITE
        dotPaint.alpha = 180
        canvas.drawCircle(cx, cy, dotRadius * 0.45f * pulse, dotPaint)
    }

    fun setState(s: State) {
        state = s
        invalidate()
    }

    fun startAnimation() {
        visibility = VISIBLE
        if (!animator.isRunning) animator.start()
    }

    fun stopAnimation() {
        animator.cancel()
        visibility = GONE
    }
}
