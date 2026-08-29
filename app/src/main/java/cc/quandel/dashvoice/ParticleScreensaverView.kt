package cc.quandel.dashvoice

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import cc.quandel.dashvoice.particle.ParticleState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Native Android particle screensaver. It deliberately avoids WebView/Chromium/WebGL so the
 * MatePad cannot end up with a permanently black renderer after its WebView GPU context dies.
 */
class ParticleScreensaverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Particle(
        val targetX: Float,
        val targetY: Float,
        val color: Int,
        val brightness: Float,
        val phase: Float,
        val drift: Float,
        val radius: Float,
        var startX: Float = 0f,
        var startY: Float = 0f,
    )

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random.Default
    private var particles: List<Particle> = emptyList()
    private var running = false
    private var assemblyEnabled = true
    private var assemblyStartedAt = 0L
    private var animationSpeed = 1f
    private var currentState = ParticleState.IDLE
    private var audioLevel = 0f

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            particles = buildParticles(w, h)
            resetAssembly()
        }
    }

    fun start(assemble: Boolean, speed: Float) {
        assemblyEnabled = assemble
        animationSpeed = speed.coerceIn(0.5f, 2f)
        running = true
        currentState = if (assemble) ParticleState.ASSEMBLING else ParticleState.IDLE
        resetAssembly()
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        visibility = GONE
    }

    fun setParticleState(state: ParticleState) {
        currentState = state
        if (state == ParticleState.ASSEMBLING) resetAssembly()
        if (running) postInvalidateOnAnimation()
    }

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
    }

    private fun resetAssembly() {
        assemblyStartedAt = System.currentTimeMillis()
        val spread = max(width, height) * 0.64f
        for (particle in particles) {
            val angle = random.nextFloat() * (PI * 2).toFloat()
            val radius = sqrt(random.nextFloat()) * spread
            particle.startX = width * 0.5f + cos(angle) * radius
            particle.startY = height * 0.5f + sin(angle) * radius
        }
    }

    private fun buildParticles(viewWidth: Int, viewHeight: Int): List<Particle> {
        val bitmap = try {
            context.assets.open("images/particle-humanoid-reference.jpg").use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val pixels = IntArray(sourceWidth * sourceHeight)
        bitmap.getPixels(pixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
        bitmap.recycle()

        data class Candidate(val x: Int, val y: Int, val color: Int, val brightness: Int)
        val candidates = ArrayList<Candidate>()
        var brightMinX = sourceWidth
        var brightMaxX = 0
        var brightMinY = sourceHeight
        var brightMaxY = 0

        for (sourceY in 0 until sourceHeight step 3) {
            for (sourceX in 0 until sourceWidth step 3) {
                val color = pixels[sourceY * sourceWidth + sourceX]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val brightness = max(red, max(green, blue))
                if (brightness < 34 || red + green + blue < 82) continue
                if (brightness > 82) {
                    brightMinX = min(brightMinX, sourceX)
                    brightMaxX = max(brightMaxX, sourceX)
                    brightMinY = min(brightMinY, sourceY)
                    brightMaxY = max(brightMaxY, sourceY)
                }
                candidates += Candidate(sourceX, sourceY, color, brightness)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        if (brightMaxX <= brightMinX || brightMaxY <= brightMinY) {
            brightMinX = 0
            brightMinY = 0
            brightMaxX = sourceWidth
            brightMaxY = sourceHeight
        }
        val brightWidth = max(1, brightMaxX - brightMinX)
        val brightHeight = max(1, brightMaxY - brightMinY)
        val scale = min(viewWidth * 0.84f / brightWidth, viewHeight * 0.92f / brightHeight)
        val sourceCenterX = (brightMinX + brightMaxX) * 0.5f
        val sourceCenterY = (brightMinY + brightMaxY) * 0.5f

        val maxParticles = 5_200
        val stride = max(1.0, candidates.size.toDouble() / maxParticles)
        val result = ArrayList<Particle>(min(maxParticles, candidates.size))
        var cursor = random.nextDouble() * stride
        while (cursor < candidates.size) {
            val candidate = candidates[cursor.toInt()]
            val brightness = candidate.brightness / 255f
            result += Particle(
                targetX = viewWidth * 0.5f + (candidate.x - sourceCenterX) * scale,
                targetY = viewHeight * 0.5f + (candidate.y - sourceCenterY) * scale,
                color = candidate.color,
                brightness = brightness,
                phase = random.nextFloat() * (PI * 2).toFloat(),
                drift = 0.45f + random.nextFloat() * 1.35f,
                radius = 0.7f + brightness * 1.65f + random.nextFloat() * 0.45f,
            )
            cursor += stride
        }
        return result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (!running) return

        val now = System.currentTimeMillis()
        val seconds = now / 1000f * animationSpeed
        if (particles.isEmpty()) {
            drawEmergencyAvatar(canvas, seconds)
            postInvalidateDelayed(33L)
            return
        }

        var assembly = 1f
        if (currentState == ParticleState.ASSEMBLING && assemblyEnabled) {
            val raw = ((now - assemblyStartedAt) / (3_200f / animationSpeed)).coerceIn(0f, 1f)
            assembly = if (raw < 0.5f) {
                4f * raw * raw * raw
            } else {
                1f - (-2f * raw + 2f).pow(3) / 2f
            }
            if (raw >= 1f) currentState = ParticleState.IDLE
        }

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val breathe = 1f + sin(seconds * 1.15f) * 0.009f
        val speaking = if (currentState == ParticleState.SPEAKING) audioLevel else 0f

        for (particle in particles) {
            val targetX = centerX + (particle.targetX - centerX) * breathe
            val targetY = centerY + (particle.targetY - centerY) * breathe
            val x: Float
            val y: Float
            if (assembly < 1f) {
                x = particle.startX + (targetX - particle.startX) * assembly
                y = particle.startY + (targetY - particle.startY) * assembly
            } else {
                x = targetX + sin(seconds * (0.65f + particle.drift * 0.16f) + particle.phase) * particle.drift
                y = targetY + cos(seconds * (0.55f + particle.drift * 0.13f) + particle.phase) * particle.drift
            }
            val pulse = 0.86f + 0.14f * sin(seconds * 2.1f + particle.phase) + speaking * 0.28f
            val alpha = (255f * (particle.brightness * pulse + 0.18f).coerceIn(0.32f, 1f)).toInt()
            particlePaint.color = Color.argb(
                alpha,
                Color.red(particle.color),
                Color.green(particle.color),
                Color.blue(particle.color),
            )
            canvas.drawCircle(x, y, particle.radius * (1f + speaking * 0.22f), particlePaint)
        }
        postInvalidateDelayed(33L)
    }

    /** Always-visible native fallback. A decoding failure must never result in a black screen. */
    private fun drawEmergencyAvatar(canvas: Canvas, seconds: Float) {
        val cx = width * 0.5f
        val cy = height * 0.45f
        val pulse = 1f + sin(seconds * 1.4f) * 0.025f
        fallbackPaint.style = Paint.Style.STROKE
        fallbackPaint.strokeWidth = max(2f, width / 500f)
        fallbackPaint.color = Color.CYAN
        for (ring in 0 until 14) {
            val inset = ring * 4f
            canvas.drawOval(
                cx - (height * 0.15f - inset) * pulse,
                cy - (height * 0.25f - inset) * pulse,
                cx + (height * 0.15f - inset) * pulse,
                cy + (height * 0.25f - inset) * pulse,
                fallbackPaint,
            )
        }
        fallbackPaint.style = Paint.Style.FILL
        fallbackPaint.color = Color.rgb(255, 145, 0)
        canvas.drawCircle(cx, cy, height * 0.085f * pulse, fallbackPaint)
        fallbackPaint.style = Paint.Style.STROKE
        fallbackPaint.color = Color.CYAN
        fallbackPaint.strokeWidth = max(3f, width / 350f)
        canvas.drawArc(
            cx - width * 0.34f,
            cy + height * 0.16f,
            cx + width * 0.34f,
            cy + height * 0.68f,
            200f,
            140f,
            false,
            fallbackPaint,
        )
    }
}
