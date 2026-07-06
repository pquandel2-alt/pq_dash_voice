package cc.quandel.dashvoice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.view.View
import kotlin.math.*

class VoiceAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State(val color: Int) {
        LISTENING(Color.parseColor("#4CAF50")),
        THINKING(Color.parseColor("#2196F3")),
        SPEAKING(Color.parseColor("#9C27B0")),
        DONE(Color.parseColor("#00E676"))   // Sofortbefehl ausgeführt (grün)
    }

    /** 0=Orb 1=Frequenz 2=Neural 3=Vortex 4=Geometrie 5=Aurora */
    var style: Int = 0

    private var state = State.LISTENING
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpPath = Path()
    private val PI_F = Math.PI.toFloat()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 16L
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    // ── Style 1: Frequency ──
    private val freqH = FloatArray(64)
    private val freqT = FloatArray(64)
    private var freqTick = 0L

    // ── Style 2: Neural ──
    private data class NNode(val x: Float, val y: Float, val r: Float, val conn: MutableList<Int> = mutableListOf())
    private data class NPulse(val fi: Int, val ti: Int, var t: Float, val sp: Float, val col: Int)
    private var nNodes = emptyList<NNode>()
    private val nPulses = mutableListOf<NPulse>()
    private var nLastSpawn = 0L

    // ── Style 3: Vortex ──
    private class Vp(var angle: Float, var radius: Float, val speed: Float, val size: Float, val a: Float)
    private var vParts = emptyList<Vp>()

    // ── Style 4: Geometry ──
    private var gRot = 0f
    private var gMorphT = 1f
    private var gCur = FloatArray(0)
    private var gTgt = FloatArray(0)
    private val GEO_PTS = 60

    // ── Style 5: Aurora ──
    private val aPhases = FloatArray(5) { it * 0.8f }

    // ── Style 6: Face / Bubble ──
    private var curHue = 140f
    private var curSat = 0.78f
    private var mouthLevel = 0f
    @Volatile private var mouthTarget = 0f
    private var blinkPhase = 1f
    private var nextBlinkAt = 0L
    private var gazeX = 0f; private var gazeY = 0f
    private var gTX = 0f;   private var gTY = 0f
    private var nextSaccadeAt = 0L
    private val hsvTmp = FloatArray(3)

    // ────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val t = System.currentTimeMillis()
        when (style) {
            0 -> drawOrb(canvas, cx, cy, t)
            1 -> drawFreq(canvas, cx, cy, t)
            2 -> drawNeural(canvas, cx, cy, t)
            3 -> drawVortex(canvas, cx, cy, t)
            4 -> drawGeo(canvas, cx, cy, t)
            5 -> drawAurora(canvas, cx, cy, t)
            6 -> drawFace(canvas, cx, cy, t)
        }
    }

    fun setState(s: State) {
        state = s
        if (style == 4) updateGeoTarget()
        invalidate()
    }

    /** Aktueller TTS-Pegel (0..1) — bewegt den Mund im SPEAKING-Zustand (Stil 6). */
    fun setMouthLevel(level: Float) { mouthTarget = level.coerceIn(0f, 1f) }

    fun startAnimation() {
        visibility = VISIBLE
        if (width > 0) initStyle()
        if (!animator.isRunning) animator.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (animator.isRunning) initStyle()
    }

    fun stopAnimation() {
        animator.cancel()
        visibility = GONE
    }

    private fun initStyle() {
        when (style) {
            1 -> { freqH.fill(0f); freqT.fill(0f); freqTick = 0L }
            2 -> { nNodes = buildNodes(); nPulses.clear(); nLastSpawn = 0L }
            3 -> { vParts = buildParticles() }
            4 -> { gRot = 0f; gMorphT = 1f; initGeoShapes() }
            5 -> { for (i in aPhases.indices) aPhases[i] = i * 0.8f }
            6 -> {
                curHue = stateHue(); curSat = stateSat()
                mouthLevel = 0f; mouthTarget = 0f
                blinkPhase = 1f; nextBlinkAt = 0L
                gazeX = 0f; gazeY = 0f; gTX = 0f; gTY = 0f; nextSaccadeAt = 0L
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun cr(col: Int) = Color.red(col)
    private fun cg(col: Int) = Color.green(col)
    private fun cb(col: Int) = Color.blue(col)
    private fun argb(a: Int, col: Int) = Color.argb(a, cr(col), cg(col), cb(col))

    // ═══════════════════════════════════════════════════════
    //  Style 0: Ripple Orb
    // ═══════════════════════════════════════════════════════
    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, t: Long) {
        val maxR = minOf(cx, cy) * 0.85f
        val dotR = minOf(cx, cy) * 0.18f
        val col  = state.color
        val cycleMs = when (state) { State.SPEAKING -> 900L; State.THINKING -> 2000L; else -> 1400L }
        val rings   = when (state) { State.SPEAKING -> 4;    State.THINKING -> 3;     else -> 5 }
        val phase = (t % cycleMs).toFloat() / cycleMs

        p.shader = null
        p.style  = Paint.Style.STROKE
        p.strokeWidth = minOf(cx, cy) * 0.04f
        for (i in 0 until rings) {
            val ph = ((phase + i.toFloat() / rings) % 1f)
            p.color = col
            p.alpha = ((1f - ph) * 220).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, dotR + ph * (maxR - dotR), p)
        }

        val pulse = 1f + 0.10f * sin((t % 1200L).toFloat() / 1200f * PI_F * 2f)

        p.style = Paint.Style.FILL; p.alpha = 255
        p.shader = RadialGradient(cx, cy, dotR * pulse * 2.5f,
            intArrayOf(argb(80, col), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, dotR * pulse * 2.5f, p)

        p.shader = RadialGradient(cx - dotR * .25f, cy - dotR * .25f, dotR * pulse,
            intArrayOf(Color.WHITE, col, argb(128, col)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, dotR * pulse, p)
        p.shader = null
    }

    // ═══════════════════════════════════════════════════════
    //  Style 1: Frequency Ring
    // ═══════════════════════════════════════════════════════
    private fun drawFreq(canvas: Canvas, cx: Float, cy: Float, t: Long) {
        val col = state.color
        val baseR  = minOf(cx, cy) * 0.38f
        val maxBar = minOf(cx, cy) * 0.40f
        val n = freqH.size
        val intensity = when (state) { State.SPEAKING -> 1.0f; State.LISTENING -> 0.55f; State.THINKING -> 0.22f; State.DONE -> 0.7f }

        if (t - freqTick > 90L) {
            freqTick = t
            val phase = t / 300f
            for (i in 0 until n) {
                freqT[i] = if (Math.random() < 0.22) freqT[i] * 0.3f
                           else intensity * (0.1f + abs(sin(i * 0.35f + phase)) * 0.9f)
            }
        }
        for (i in 0 until n) freqH[i] = lerp(freqH[i], freqT[i], 0.14f)

        p.style = Paint.Style.FILL; p.alpha = 255
        p.shader = RadialGradient(cx, cy, baseR,
            intArrayOf(argb(50, col), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, baseR, p)

        p.shader = RadialGradient(cx - 8f, cy - 8f, 26f,
            intArrayOf(Color.WHITE, col), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 26f, p)
        p.shader = null

        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 5f
        for (i in 0 until n) {
            val h = freqH[i] * maxBar
            val angle = i.toFloat() / n * PI_F * 2f - PI_F / 2f
            p.color = col
            p.alpha = ((0.4f + freqH[i] * 0.6f) * 255f).toInt().coerceIn(0, 255)
            canvas.drawLine(
                cx + cos(angle) * baseR,      cy + sin(angle) * baseR,
                cx + cos(angle) * (baseR + h), cy + sin(angle) * (baseR + h), p)
        }
        p.strokeCap = Paint.Cap.BUTT
    }

    // ═══════════════════════════════════════════════════════
    //  Style 2: Neural Network
    // ═══════════════════════════════════════════════════════
    private fun drawNeural(canvas: Canvas, cx: Float, cy: Float, t: Long) {
        val col = state.color
        if (nNodes.isEmpty()) return

        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.shader = null
        p.color = col; p.alpha = 30
        for (node in nNodes)
            for (j in node.conn)
                canvas.drawLine(node.x, node.y, nNodes[j].x, nNodes[j].y, p)

        if (t - nLastSpawn > 130L && nPulses.size < 20) {
            nLastSpawn = t
            val fi = (Math.random() * nNodes.size).toInt().coerceIn(0, nNodes.size - 1)
            val fc = nNodes[fi]
            if (fc.conn.isNotEmpty()) {
                val ti = fc.conn[(Math.random() * fc.conn.size).toInt().coerceIn(0, fc.conn.size - 1)]
                nPulses.add(NPulse(fi, ti, 0f, 0.025f + Math.random().toFloat() * 0.02f, col))
            }
        }

        val it = nPulses.iterator()
        while (it.hasNext()) {
            val pulse = it.next(); pulse.t += pulse.sp
            if (pulse.t >= 1f) { it.remove(); continue }
            val a = nNodes[pulse.fi]; val b = nNodes[pulse.ti]
            val px = lerp(a.x, b.x, pulse.t); val py = lerp(a.y, b.y, pulse.t)
            val alpha = sin(pulse.t * PI_F)
            p.style = Paint.Style.FILL; p.color = pulse.col
            p.alpha = (alpha * 55f).toInt()
            canvas.drawCircle(px, py, 10f, p)
            p.alpha = (alpha * 220f).toInt()
            canvas.drawCircle(px, py, 4f, p)
        }

        for (node in nNodes) {
            p.style = Paint.Style.FILL; p.alpha = 255
            p.shader = RadialGradient(node.x, node.y, node.r * 2.2f,
                intArrayOf(argb(80, col), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(node.x, node.y, node.r * 2.2f, p)
            p.shader = RadialGradient(node.x - node.r * .3f, node.y - node.r * .3f, node.r,
                intArrayOf(Color.WHITE, col), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(node.x, node.y, node.r, p)
            p.shader = null
        }
    }

    private fun buildNodes(): List<NNode> {
        val cx = width / 2f; val cy = height / 2f
        val raw = mutableListOf<NNode>()
        raw.add(NNode(cx, cy, 12f))
        for (i in 0..5) {
            val a = i / 6f * PI_F * 2f
            raw.add(NNode(cx + cos(a) * 85f, cy + sin(a) * 85f, 7f))
        }
        for (i in 0..9) {
            val a = i / 10f * PI_F * 2f + 0.3f
            raw.add(NNode(cx + cos(a) * 148f, cy + sin(a) * 148f, 5f))
        }
        return raw.mapIndexed { i, n ->
            val conn = mutableListOf<Int>()
            raw.forEachIndexed { j, m ->
                if (i != j && hypot(n.x - m.x, n.y - m.y) < 110f) conn.add(j)
            }
            NNode(n.x, n.y, n.r, conn)
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Style 3: Particle Vortex
    // ═══════════════════════════════════════════════════════
    private fun drawVortex(canvas: Canvas, cx: Float, cy: Float, t: Long) {
        val col = state.color
        val swirlFactor = when (state) { State.SPEAKING -> 0.022f; State.THINKING -> 0.004f; else -> 0.012f }
        val shrink = state == State.THINKING

        p.style = Paint.Style.FILL; p.shader = null
        for (vp in vParts) {
            vp.angle += vp.speed * 20f * swirlFactor
            if (shrink) {
                vp.radius *= 0.997f
                if (vp.radius < 20f) vp.radius = 55f + Math.random().toFloat() * 80f
            }
            val wobble = if (state == State.SPEAKING) sin(t / 320f + vp.angle) * 14f else 0.0
            p.color = col
            p.alpha = (vp.a * 200f).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                cx + cos(vp.angle) * (vp.radius + wobble.toFloat()),
                cy + sin(vp.angle) * vp.radius * 0.85f,
                vp.size, p)
        }

        p.shader = RadialGradient(cx, cy, 42f,
            intArrayOf(argb(170, col), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        p.alpha = 255; canvas.drawCircle(cx, cy, 42f, p)
        p.shader = RadialGradient(cx - 5f, cy - 5f, 18f,
            intArrayOf(Color.WHITE, col), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 18f, p)
        p.shader = null
    }

    private fun buildParticles() = List(120) {
        val a = (Math.random() * PI_F * 2).toFloat()
        val r = 55f + Math.random().toFloat() * 130f
        Vp(a, r, 0.004f + Math.random().toFloat() * 0.006f,
           1.5f + Math.random().toFloat() * 2.5f,
           0.3f + Math.random().toFloat() * 0.5f)
    }

    // ═══════════════════════════════════════════════════════
    //  Style 4: Morphing Geometry
    // ═══════════════════════════════════════════════════════
    private fun drawGeo(canvas: Canvas, cx: Float, cy: Float, t: Long) {
        if (gCur.isEmpty() || gTgt.isEmpty()) return
        val col = state.color
        gRot += when (state) { State.SPEAKING -> 0.018f; State.THINKING -> 0.004f; else -> 0.009f }
        gMorphT = min(1f, gMorphT + 0.022f)
        val n = GEO_PTS
        val cosR = cos(gRot); val sinR = sin(gRot)

        for (layer in 0..2) {
            val scale = 1f - layer * 0.08f
            val alpha = floatArrayOf(0.9f, 0.45f, 0.18f)[layer]

            tmpPath.reset()
            for (k in 0 until n) {
                val bx = lerp(gCur[k * 2], gTgt[k * 2], gMorphT) - cx
                val by = lerp(gCur[k * 2 + 1], gTgt[k * 2 + 1], gMorphT) - cy
                val rx = cx + (bx * cosR - by * sinR) * scale
                val ry = cy + (bx * sinR + by * cosR) * scale
                if (k == 0) tmpPath.moveTo(rx, ry) else tmpPath.lineTo(rx, ry)
            }
            tmpPath.close()

            p.style = Paint.Style.STROKE; p.shader = null
            p.strokeWidth = floatArrayOf(3.5f, 1.8f, 0.9f)[layer]
            p.color = col; p.alpha = (alpha * 255f).toInt()
            canvas.drawPath(tmpPath, p)

            if (layer == 0) {
                p.style = Paint.Style.FILL; p.alpha = 18
                canvas.drawPath(tmpPath, p)
            }
        }

        p.style = Paint.Style.FILL; p.alpha = 255
        p.shader = RadialGradient(cx - 6f, cy - 6f, 20f,
            intArrayOf(Color.WHITE, col), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 20f, p)
        p.shader = null
    }

    private fun polygon(sides: Int, r: Float, cx: Float, cy: Float, offset: Float = 0f) =
        FloatArray(sides * 2) { i ->
            val k = i / 2
            val a = k.toFloat() / sides * PI_F * 2f + offset - PI_F / 2f
            if (i % 2 == 0) cx + cos(a) * r else cy + sin(a) * r
        }

    private fun star(pts: Int, outer: Float, inner: Float, cx: Float, cy: Float): FloatArray {
        val total = pts * 2
        return FloatArray(total * 2) { i ->
            val k = i / 2
            val r = if (k % 2 == 0) outer else inner
            val a = k.toFloat() / total * PI_F * 2f - PI_F / 2f
            if (i % 2 == 0) cx + cos(a) * r else cy + sin(a) * r
        }
    }

    private fun resamplePoly(src: FloatArray, n: Int): FloatArray {
        val pts = src.size / 2
        return FloatArray(n * 2) { i ->
            val k = i / 2; val coord = i % 2
            val tf = k.toFloat() / n * pts
            val a = tf.toInt() % pts; val b = (a + 1) % pts
            lerp(src[a * 2 + coord], src[b * 2 + coord], tf - tf.toInt())
        }
    }

    private fun initGeoShapes() {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) * 0.62f
        gCur = resamplePoly(polygon(6, r, cx, cy), GEO_PTS)
        gTgt = gCur.copyOf()
    }

    private fun updateGeoTarget() {
        val cx = width / 2f; val cy = height / 2f
        if (cx == 0f) return
        val r = minOf(cx, cy) * 0.62f
        val newTgt = when (state) {
            State.LISTENING -> resamplePoly(polygon(6, r, cx, cy), GEO_PTS)
            State.THINKING  -> resamplePoly(polygon(GEO_PTS, r, cx, cy), GEO_PTS)
            State.SPEAKING  -> resamplePoly(star(6, r, r * 0.5f, cx, cy), GEO_PTS)
            State.DONE      -> resamplePoly(polygon(6, r, cx, cy), GEO_PTS)
        }
        gCur = FloatArray(GEO_PTS * 2) { i -> lerp(gCur[i], gTgt[i], gMorphT) }
        gTgt = newTgt; gMorphT = 0f
    }

    // ═══════════════════════════════════════════════════════
    //  Style 5: Aurora
    // ═══════════════════════════════════════════════════════
    private fun drawAurora(canvas: Canvas, cx: Float, cy: Float, t: Long) {
        val col  = state.color
        val tSec = t / 1000f
        val bandCols = arrayOf(
            intArrayOf(cr(col), cg(col), cb(col)),
            intArrayOf((cr(col) * 0.6f).toInt(), (cg(col) * 0.6f).toInt(), minOf(255, cb(col) + 70)),
            intArrayOf(minOf(255, cr(col) + 50), (cg(col) * 0.45f).toInt(), (cb(col) * 0.85f).toInt()),
            intArrayOf((cr(col) * 0.5f).toInt(), minOf(255, cg(col) + 30), (cb(col) * 0.75f).toInt()),
            intArrayOf(cr(col), cg(col), cb(col)),
        )

        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        for (i in 0..4) {
            aPhases[i] += 0.003f + i * 0.001f
            val yBase  = cy + (-80f + i * 40f) + sin((tSec * 0.35f + i * 1.1f).toDouble()).toFloat() * 28f
            val amp    = 22f + i * 7f
            val spd    = 0.55f + i * 0.12f
            val wc     = bandCols[i]
            val halfH  = 75f + i * 10f

            p.strokeWidth = 80f + i * 15f
            p.shader = LinearGradient(cx, yBase - halfH, cx, yBase + halfH,
                intArrayOf(Color.TRANSPARENT,
                    Color.argb(70, wc[0], wc[1], wc[2]),
                    Color.argb(100, wc[0], wc[1], wc[2]),
                    Color.argb(70, wc[0], wc[1], wc[2]),
                    Color.TRANSPARENT),
                floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
                Shader.TileMode.CLAMP)
            p.alpha = 255

            tmpPath.reset()
            val steps = 14
            tmpPath.moveTo(0f, yBase + sin(aPhases[i].toDouble()).toFloat() * amp)
            for (s in 1..steps) {
                val x = s.toFloat() / steps * width
                val y = yBase + sin((aPhases[i] + x / width * PI_F * 2f * spd).toDouble()).toFloat() * amp
                tmpPath.lineTo(x, y)
            }
            canvas.drawPath(tmpPath, p)
        }
        p.shader = null; p.strokeCap = Paint.Cap.BUTT

        val pulse = 1f + 0.08f * sin(t / 350.0).toFloat()
        p.style = Paint.Style.FILL; p.alpha = 255
        p.shader = RadialGradient(cx, cy, 48f * pulse,
            intArrayOf(Color.WHITE, col, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, 48f * pulse, p)
        p.shader = null
    }

    // ═══════════════════════════════════════════════════════
    //  Style 6: Face / Bubble — zustandsfarbiger Nebel + Gesicht + Lippensync
    //  Farbe: grün=LISTENING, blau=THINKING, lila=SPEAKING, grün=DONE
    // ═══════════════════════════════════════════════════════
    private fun stateHue() = when (state) {
        State.LISTENING -> 140f; State.THINKING -> 210f; State.SPEAKING -> 285f; State.DONE -> 150f
    }
    private fun stateSat() = when (state) {
        State.LISTENING -> 0.78f; State.THINKING -> 0.82f; State.SPEAKING -> 0.74f; State.DONE -> 0.80f
    }
    private fun hsv(h: Float, s: Float, v: Float, a: Int): Int {
        hsvTmp[0] = ((h % 360f) + 360f) % 360f
        hsvTmp[1] = s.coerceIn(0f, 1f); hsvTmp[2] = v.coerceIn(0f, 1f)
        return (a.coerceIn(0, 255) shl 24) or (Color.HSVToColor(hsvTmp) and 0x00FFFFFF)
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, t: Long) {
        val time = t / 1000f
        curHue += (stateHue() - curHue) * 0.10f
        curSat += (stateSat() - curSat) * 0.10f

        val baseR   = minOf(cx, cy) * 0.62f
        val breathe = 1f + 0.025f * sin(time * 1.05f)
        val sq      = 0.05f * sin(time * 1.5f)
        val rx = baseR * breathe * (1f + sq)
        val ry = baseR * breathe * (1f - sq)
        val R  = (rx + ry) / 2f

        p.style = Paint.Style.FILL; p.alpha = 255

        // --- Nebel-Rand: gefederter radialer Farbring (weiche Innen-/Außenkante) ---
        p.shader = RadialGradient(cx, cy, R * 1.42f,
            intArrayOf(0, 0, hsv(curHue, curSat, 0.56f, 235), hsv(curHue, curSat, 0.50f, 90), 0),
            floatArrayOf(0f, 0.32f, 0.60f, 0.85f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, R * 1.42f, p)

        // wandernder Schimmer (Lebendigkeit)
        val sa = time * 0.6f
        val sx = cx + cos(sa) * R * 0.86f
        val sy = cy + sin(sa) * R * 0.86f
        p.shader = RadialGradient(sx, sy, R * 0.55f,
            intArrayOf(hsv(curHue, curSat * 0.7f, 0.85f, 80), 0), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(sx, sy, R * 0.55f, p)

        // --- Körper (auf Zustandsfarbe getintet, sonst kippt Lila nach Blau) ---
        p.shader = RadialGradient(cx, cy - ry * 0.15f, ry * 1.02f,
            intArrayOf(hsv(curHue, 0.45f, 0.15f, 130), hsv(curHue, 0.42f, 0.09f, 115), hsv(curHue, 0.40f, 0.05f, 30)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, R * 0.95f, p)

        // --- Innenglühen ---
        p.shader = RadialGradient(cx, cy, R * 0.72f,
            intArrayOf(hsv(curHue, curSat, 0.48f, 56), 0), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, R * 0.72f, p)
        p.shader = null

        // --- Augen: Blinzeln + Blickdrift ---
        if (t > nextBlinkAt) { blinkPhase = 0f; nextBlinkAt = t + 1800L + (Math.random() * 4200).toLong() }
        blinkPhase = min(1f, blinkPhase + 0.10f)
        val blinkScale = if (blinkPhase < 0.5f) 1f - blinkPhase * 2f else (blinkPhase - 0.5f) * 2f
        if (t > nextSaccadeAt) {
            gTX = ((Math.random() * 2 - 1) * (R * 0.07f)).toFloat()
            gTY = ((Math.random() * 2 - 1) * (R * 0.045f)).toFloat()
            if (state == State.THINKING) gTY = (-(R * 0.04f) - Math.random() * (R * 0.04f)).toFloat()
            nextSaccadeAt = t + 900L + (Math.random() * 2200).toLong()
        }
        gazeX += (gTX - gazeX) * 0.08f
        gazeY += (gTY - gazeY) * 0.08f

        val eyeDX = R * 0.185f
        val eyeY  = cy - ry * 0.06f
        val eW    = R * 0.13f
        val eH    = R * 0.24f * (0.28f + 0.72f * blinkScale)
        for (sgn in intArrayOf(-1, 1)) {
            val ex = cx + sgn * eyeDX + gazeX
            val ey = eyeY + gazeY
            p.shader = RadialGradient(ex, ey, eW * 1.8f,
                intArrayOf(hsv(0f, 0f, 1f, 90), 0), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(ex, ey, eW * 1.8f, p)
            p.shader = null
            p.color = Color.WHITE
            val r = eW / 2f
            canvas.drawRoundRect(ex - eW / 2f, ey - eH / 2f, ex + eW / 2f, ey + eH / 2f, r, r, p)
        }

        // --- Mund: echte TTS-Amplitude (schneller Anstieg, sanftes Schließen) ---
        val tgt = if (state == State.SPEAKING) mouthTarget else 0f
        mouthLevel += (tgt - mouthLevel) * (if (tgt > mouthLevel) 0.45f else 0.18f)
        if (mouthLevel > 0.02f) {
            val mw = R * 0.26f + mouthLevel * R * 0.07f
            val mh = R * 0.045f + mouthLevel * R * 0.24f
            val my = cy + ry * 0.30f
            p.color = Color.WHITE
            canvas.drawOval(cx - mw / 2f, my - mh / 2f, cx + mw / 2f, my + mh / 2f, p)
            p.color = hsv(curHue, 0.40f, 0.18f, 90)
            canvas.drawOval(cx - mw * 0.31f, my - mh * 0.30f, cx + mw * 0.31f, my + mh * 0.30f, p)
        }
    }
}
