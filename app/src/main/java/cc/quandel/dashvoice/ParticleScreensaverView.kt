package cc.quandel.dashvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import cc.quandel.dashvoice.particle.ParticleAnimationClock
import cc.quandel.dashvoice.particle.ParticleState
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
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
        val region: String,
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
    private var animationStartedAt = 0L
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
            particles = buildSharedGeometry(w, h)
            resetAssembly()
        }
    }

    fun start(assemble: Boolean, speed: Float) {
        assemblyEnabled = assemble
        animationSpeed = speed.coerceIn(0.5f, 2f)
        running = true
        animationStartedAt = System.currentTimeMillis()
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
        // The always-streaming satellite can emit IDLE while the entrance animation is still
        // running. Do not let that event snap all particles straight to the finished portrait.
        if (state == ParticleState.IDLE && currentState == ParticleState.ASSEMBLING &&
            System.currentTimeMillis() - assemblyStartedAt < ASSEMBLY_DURATION_MS
        ) return
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

    /** Loads the exact same normalized point set used by tools/avatar-preview.html. */
    private fun buildSharedGeometry(viewWidth: Int, viewHeight: Int): List<Particle> {
        val json = context.assets.open("avatar-geometry.json").bufferedReader().use { it.readText() }
        val source = JSONObject(json).getJSONArray("particles")
        return ArrayList<Particle>(source.length()).apply {
            for (index in 0 until source.length()) {
                val item = source.getJSONObject(index)
                val color = item.getJSONArray("color")
                add(
                    Particle(
                        targetX = item.getDouble("x").toFloat() * viewWidth,
                        targetY = item.getDouble("y").toFloat() * viewHeight,
                        color = Color.rgb(color.getInt(0), color.getInt(1), color.getInt(2)),
                        brightness = item.getDouble("brightness").toFloat(),
                        phase = item.getDouble("phase").toFloat(),
                        drift = item.getDouble("drift").toFloat(),
                        radius = item.getDouble("radius").toFloat(),
                        region = item.getString("region"),
                    )
                )
            }
        }
    }

    /** Builds an original avatar from mathematical curves; no bitmap is read or sampled. */
    private fun buildParticles(viewWidth: Int, viewHeight: Int): List<Particle> {
        val result = ArrayList<Particle>(PARTICLE_COUNT)
        val centerX = viewWidth * 0.5f
        val headCenterY = viewHeight * 0.325f
        val headRadiusX = viewHeight * 0.142f
        val headRadiusY = viewHeight * 0.205f
        val chinY = headCenterY + headRadiusY
        val neckBaseY = viewHeight * 0.645f
        val shoulderOuterY = viewHeight * 0.735f

        // Half-width of a human-like head from crown (-1) to chin (+1). Unlike an ellipse,
        // this gives us a small crown, temples/cheekbones, jaw and narrow chin.
        fun headHalfWidth(vertical: Float): Float {
            val stops = arrayOf(
                -1.00f to 0.18f,
                -0.82f to 0.68f,
                -0.52f to 0.96f,
                -0.10f to 1.00f,
                0.30f to 0.92f,
                0.60f to 0.72f,
                0.84f to 0.43f,
                1.00f to 0.16f,
            )
            for (index in 0 until stops.lastIndex) {
                val (fromY, fromWidth) = stops[index]
                val (toY, toWidth) = stops[index + 1]
                if (vertical in fromY..toY) {
                    val t = (vertical - fromY) / (toY - fromY)
                    return headRadiusX * (fromWidth + (toWidth - fromWidth) * t)
                }
            }
            return headRadiusX * 0.16f
        }

        fun cyan(brightness: Float): Int = Color.rgb(
            (8 + brightness * 32).toInt().coerceIn(0, 255),
            (125 + brightness * 105).toInt().coerceIn(0, 255),
            (210 + brightness * 45).toInt().coerceIn(0, 255),
        )

        fun add(
            x: Float,
            y: Float,
            color: Int,
            brightness: Float,
            radius: Float = 1.6f,
            region: String = "structure",
        ) {
            if (result.size >= PARTICLE_COUNT) return
            result += Particle(
                targetX = x,
                targetY = y,
                color = color,
                brightness = brightness.coerceIn(0.35f, 1f),
                phase = random.nextFloat() * (PI * 2).toFloat(),
                drift = 1.8f + random.nextFloat() * 4.2f,
                radius = radius + random.nextFloat() * 0.65f,
                region = region,
            )
        }

        // Fewer, clearly separated scan-lines keep the head translucent instead of egg-shaped.
        for (line in 0 until 27) {
            val vertical = -1f + line / 26f * 2f
            val halfWidth = headHalfWidth(vertical)
            val profile = (halfWidth / headRadiusX).coerceIn(0f, 1f)
            val count = (16 + profile * 28).toInt()
            val y = headCenterY + vertical * headRadiusY
            for (point in 0 until count) {
                val across = if (count <= 1) 0.5f else point / (count - 1f)
                val x = centerX - halfWidth + halfWidth * 2f * across
                val wave = sin(across * PI.toFloat() * 2f + line * 0.32f) * 1.5f
                val edge = kotlin.math.abs(across - 0.5f) * 2f
                val brightness = 0.38f + profile * 0.19f + edge * 0.22f + random.nextFloat() * 0.09f
                add(x, y + wave, cyan(brightness), brightness, 1.05f)
            }
        }

        // Bright profile-following shells define the silhouette without filling it solidly.
        for (shell in 0 until 5) {
            val shellOffset = shell * viewHeight * 0.0028f
            for (point in 0 until 92) {
                val vertical = -1f + point / 91f * 2f
                val y = headCenterY + vertical * (headRadiusY + shellOffset)
                val halfWidth = headHalfWidth(vertical) + shellOffset
                for (side in listOf(-1f, 1f)) {
                    add(
                        centerX + side * halfWidth,
                        y,
                        cyan(0.98f),
                        0.88f + random.nextFloat() * 0.12f,
                        1.65f,
                    )
                }
            }
        }

        // Independent warm energy field floating inside the otherwise faceless head.
        val faceCenterY = headCenterY + headRadiusY * 0.12f
        for (ring in 0 until 20) {
            val ringRadius = (ring + 1f) / 20f
            val points = 20 + ring
            for (point in 0 until points) {
                val angle = point / points.toFloat() * (PI * 2).toFloat() + ring * 0.19f
                val shimmer = 0.92f + random.nextFloat() * 0.16f
                val red = 255
                val green = (105 + (1f - ringRadius) * 105).toInt()
                val blue = (8 + (1f - ringRadius) * 48).toInt()
                add(
                    centerX + cos(angle) * headRadiusX * 0.70f * ringRadius * shimmer,
                    faceCenterY + sin(angle) * headRadiusY * 0.50f * ringRadius * shimmer,
                    Color.rgb(red, green, blue),
                    0.72f + (1f - ringRadius) * 0.28f,
                    1.65f + (1f - ringRadius) * 0.55f,
                )
            }
        }

        // Gold/cyan streams connect the head to the chest like living neural fibres.
        for (stream in -7..7) {
            val streamPosition = stream / 7f
            for (point in 0 until 38) {
                val t = point / 37f
                val spread = viewHeight * (0.014f + t * 0.072f)
                val curve = sin(t * PI.toFloat()) * sin(stream * 1.35f) * viewWidth * 0.006f
                val gold = kotlin.math.abs(stream) <= 2
                add(
                    centerX + streamPosition * spread + curve,
                    chinY + (neckBaseY - chinY) * t,
                    if (gold) Color.rgb(240, 176, 70) else cyan(0.78f),
                    if (gold) 0.88f else 0.70f,
                    1.35f,
                )
            }
        }

        // Anatomical shoulder contours slope down from the neck instead of forming flat wings.
        val shoulderHalfWidth = viewWidth * 0.355f
        val neckHalfWidth = viewHeight * 0.068f
        for (side in listOf(-1f, 1f)) {
            for (layer in 0 until 12) {
                for (point in 0 until 34) {
                    val t = point / 33f
                    val x = centerX + side * (neckHalfWidth + (shoulderHalfWidth - neckHalfWidth) * t)
                    val naturalDrop = (shoulderOuterY - neckBaseY) * t.pow(1.35f)
                    val muscleCurve = sin(t * PI.toFloat()) * viewHeight * 0.022f
                    val layerDrop = layer * viewHeight * 0.0048f * (0.35f + t * 0.65f)
                    val y = neckBaseY + naturalDrop - muscleCurve + layerDrop
                    val brightness = 0.44f + (1f - layer / 12f) * 0.37f
                    add(x, y, cyan(brightness), brightness, 1.22f)
                }
            }
        }

        // Nested torso streams bend inward gradually and fade at different endpoints.
        val chestBottomY = viewHeight * 0.925f
        for (side in listOf(-1f, 1f)) {
            for (layer in 0 until 11) {
                for (point in 0 until 34) {
                    val t = point / 33f
                    val startWidth = shoulderHalfWidth * (0.88f - layer * 0.045f)
                    val endWidth = viewWidth * (0.035f + layer * 0.004f)
                    val inward = t.pow(1.18f)
                    val x = centerX + side * (startWidth + (endWidth - startWidth) * inward)
                    val endpointY = chestBottomY - layer * viewHeight * 0.006f
                    val y = shoulderOuterY + (endpointY - shoulderOuterY) * t +
                        sin(t * PI.toFloat()) * viewHeight * 0.018f
                    val brightness = 0.38f + (1f - t) * 0.34f
                    add(x, y, cyan(brightness), brightness, 1.08f)
                }
            }
        }

        // Fine central fibres keep the torso connected instead of ending in an empty V.
        for (stream in -5..5) {
            for (point in 0 until 28) {
                val t = point / 27f
                val x = centerX + stream * viewWidth * 0.006f * (1f - t * 0.55f) +
                    sin(t * PI.toFloat() * 2f + stream) * viewWidth * 0.003f
                val y = neckBaseY + (chestBottomY - neckBaseY) * t
                add(x, y, if (kotlin.math.abs(stream) <= 1) Color.rgb(225, 177, 92) else cyan(0.65f), 0.62f, 1.05f)
            }
        }

        val chestCoreY = viewHeight * 0.785f
        for (point in 0 until 260) {
            val angle = random.nextFloat() * (PI * 2).toFloat()
            val radius = sqrt(random.nextFloat()) * viewHeight * 0.045f
            add(
                centerX + cos(angle) * radius * 0.42f,
                chestCoreY + sin(angle) * radius,
                Color.rgb(110, 245, 255),
                0.78f + random.nextFloat() * 0.22f,
                1.65f,
            )
        }

        // Remaining particles stay close to the crown and shoulders, not across the whole screen.
        while (result.size < PARTICLE_COUNT) {
            val crownParticle = random.nextFloat() < 0.58f
            val x = if (crownParticle) {
                centerX + (random.nextFloat() - 0.5f) * headRadiusX * 2.5f
            } else {
                centerX + (random.nextFloat() - 0.5f) * shoulderHalfWidth * 2.35f
            }
            val y = if (crownParticle) {
                headCenterY - headRadiusY - random.nextFloat().pow(1.7f) * viewHeight * 0.14f
            } else {
                shoulderOuterY + (random.nextFloat() - 0.7f) * viewHeight * 0.16f
            }
            add(x, y, cyan(0.48f), 0.38f + random.nextFloat() * 0.32f, 0.9f)
        }
        return result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (!running) return

        val now = System.currentTimeMillis()
        // Always calculate from a small elapsed duration. Converting Unix epoch milliseconds
        // directly to Float loses sub-minute precision and freezes every sine-based movement.
        val seconds = ParticleAnimationClock.elapsedSeconds(now, animationStartedAt, animationSpeed)
        if (particles.isEmpty()) {
            drawEmergencyAvatar(canvas, seconds)
            postInvalidateDelayed(33L)
            return
        }

        var assembly = 1f
        if (currentState == ParticleState.ASSEMBLING && assemblyEnabled) {
            val raw = ((now - assemblyStartedAt) /
                (ASSEMBLY_DURATION_MS / animationSpeed)).coerceIn(0f, 1f)
            assembly = if (raw < 0.5f) {
                4f * raw * raw * raw
            } else {
                1f - (-2f * raw + 2f).pow(3) / 2f
            }
            if (raw >= 1f) currentState = ParticleState.IDLE
        }

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        // Whole-body breathing and a tiny head-led sway make the silhouette feel inhabited.
        val breatheX = 1f + sin(seconds * 1.05f) * 0.014f
        val breatheY = 1f + sin(seconds * 1.05f + 0.35f) * 0.010f
        val bodySway = sin(seconds * 0.58f) * width * 0.0045f
        val speaking = if (currentState == ParticleState.SPEAKING) audioLevel else 0f

        for (particle in particles) {
            val normalizedY = (particle.targetY / height).coerceIn(0f, 1f)
            val upperBodyInfluence = (1.15f - normalizedY).coerceIn(0.25f, 1f)
            var targetX = centerX + (particle.targetX - centerX) * breatheX +
                bodySway * upperBodyInfluence
            var targetY = centerY + (particle.targetY - centerY) * breatheY

            // The warm face core expands and contracts independently, like a slow heartbeat.
            val red = Color.red(particle.color)
            val green = Color.green(particle.color)
            val blue = Color.blue(particle.color)
            val warmCore = particle.region == "core"
            if (warmCore) {
                val faceCenterY = height * 0.353f
                val corePulse = 1f + sin(seconds * 2.35f) * 0.032f
                targetX = centerX + (targetX - centerX) * corePulse
                targetY = faceCenterY + (targetY - faceCenterY) * corePulse
            }
            val x: Float
            val y: Float
            if (assembly < 1f) {
                // A curved stream is visibly different from a flat cross-fade into the image.
                val stream = sin(particle.phase + assembly * PI.toFloat() * 3f) *
                    (1f - assembly) * width * 0.055f
                x = particle.startX + (targetX - particle.startX) * assembly + stream
                y = particle.startY + (targetY - particle.startY) * assembly -
                    cos(particle.phase + assembly * PI.toFloat() * 2f) *
                    (1f - assembly) * height * 0.035f
            } else {
                // Every particle has its own orbit. A small rolling subset detaches farther from
                // the body and returns, so motion stays obvious without destroying the silhouette.
                val orbitX = sin(seconds * (0.72f + particle.drift * 0.055f) + particle.phase) * particle.drift
                val orbitY = cos(seconds * (0.61f + particle.drift * 0.047f) + particle.phase) * particle.drift
                val energyWave = sin(seconds * 2.2f - normalizedY * 15f + particle.phase * 0.16f) * 1.8f
                val cycle = (seconds * 0.105f + particle.phase / (PI.toFloat() * 2f)) % 1f
                val release = if (cycle < 0.11f) sin(cycle / 0.11f * PI.toFloat()) else 0f
                val releaseX = sin(particle.phase + seconds * 0.85f) * release * (8f + particle.drift * 2.2f)
                val releaseY = -release * (7f + particle.drift * 2.8f)
                x = targetX + orbitX + releaseX + energyWave * 0.45f
                y = targetY + orbitY + releaseY + energyWave
            }
            val listeningEnergy = if (currentState == ParticleState.LISTENING) {
                0.78f + 0.12f * sin(seconds * 4.2f)
            } else 0f
            val speakingBeat = if (currentState == ParticleState.SPEAKING) {
                val beat = (sin(seconds * 9f) + 1f) * 0.5f
                (0.35f + beat * 0.65f) * (0.72f + audioLevel * 0.28f) * 0.92f
            } else 0f
            val coreEnergy = if (warmCore) max(listeningEnergy, speakingBeat) else 0f
            val pulse = 0.68f + 0.32f * sin(seconds * 2.4f + particle.phase) + speaking * 0.28f
            val alpha = (255f * (particle.brightness * pulse + 0.18f + coreEnergy * 0.42f)
                .coerceIn(0.32f, 1f)).toInt()
            particlePaint.color = Color.argb(
                alpha,
                red,
                green,
                blue,
            )
            val twinkleSize = 0.78f + 0.32f *
                ((sin(seconds * 2.7f + particle.phase) + 1f) * 0.5f)
            val radius = particle.radius * twinkleSize *
                (1f + speaking * 0.22f + coreEnergy * 0.62f)
            val structuralGlow = when (particle.region) {
                "halo", "neckShell" -> 0.12f
                "chestCore" -> 0.16f
                "core" -> 0.07f
                else -> 0f
            }
            if (structuralGlow > 0f) {
                particlePaint.alpha = (255f * structuralGlow * particle.brightness)
                    .toInt().coerceIn(0, 72)
                canvas.drawCircle(x, y, radius * 2.65f, particlePaint)
                particlePaint.alpha = alpha
            }
            if (warmCore && coreEnergy > 0.05f) {
                particlePaint.alpha = (42 + coreEnergy * 62f).toInt().coerceIn(0, 120)
                canvas.drawCircle(x, y, radius * (2.1f + coreEnergy), particlePaint)
                particlePaint.alpha = alpha
            }
            canvas.drawCircle(
                x,
                y,
                radius,
                particlePaint,
            )
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

    companion object {
        private const val ASSEMBLY_DURATION_MS = 3_200f
        private const val PARTICLE_COUNT = 9_800
    }
}
