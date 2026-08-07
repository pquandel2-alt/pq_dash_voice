package cc.quandel.dashvoice.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import cc.quandel.dashvoice.util.AppLog as Log
import java.nio.FloatBuffer

/**
 * openWakeWord 3-model pipeline running on-device via ONNX Runtime.
 *
 * Required assets (NOT shipped in this scaffold — add in WP2):
 *   assets/openwakeword/melspectrogram.onnx
 *   assets/openwakeword/embedding_model.onnx
 *   assets/openwakeword/<wakeWord>.onnx      e.g. ok_nabu.onnx
 *
 * If any asset is missing, [available] is false and the service uses tap-to-talk.
 *
 * IMPORTANT: the feature scaling (mel/10 + 2), window (76) and stride (8) follow the
 * openWakeWord reference but are UNVERIFIED on the MatePad. Detection quality must be
 * tuned on-device (WP2/WP6). The ONNX I/O wiring below is the part validated by the build.
 */
class OpenWakeWordDetector(
    context: Context,
    val wakeWord: String,
    threshold: Float
) : WakeWordDetector {

    /** Live änderbar (SettingsActivity → VoiceService.applySettings), ohne Neustart. */
    @Volatile var threshold: Float = threshold

    private val env = OrtEnvironment.getEnvironment()
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var wwSession: OrtSession? = null

    private val melBuffer = ArrayList<FloatArray>()  // each row = 32 mel bins
    private val embBuffer = ArrayList<FloatArray>()   // each = 96 dims
    private var melFramesSinceEmb = 0
    private var lastFire = 0L

    override val available: Boolean

    init {
        var ok = false
        try {
            melSession = loadAsset(context, "openwakeword/melspectrogram.onnx")
            embSession = loadAsset(context, "openwakeword/embedding_model.onnx")
            wwSession = loadAsset(context, "openwakeword/$wakeWord.onnx")
            ok = melSession != null && embSession != null && wwSession != null
        } catch (e: Exception) {
            Log.w(TAG, "init failed: ${e.message}")
        }
        available = ok
        Log.i(TAG, "available=$available (wake=$wakeWord)")
    }

    private fun loadAsset(ctx: Context, path: String): OrtSession? = try {
        ctx.assets.open(path).use { input ->
            env.createSession(input.readBytes(), OrtSession.SessionOptions())
        }
    } catch (e: Exception) {
        Log.w(TAG, "asset missing: $path")
        null
    }

    override fun accept(frame: ShortArray): Boolean {
        if (!available) return false
        return try {
            process(frame)
        } catch (e: Exception) {
            Log.w(TAG, "infer: ${e.message}")
            false
        }
    }

    private fun process(frame: ShortArray): Boolean {
        val mel = melSession ?: return false
        val emb = embSession ?: return false
        val ww = wwSession ?: return false

        // 1) audio -> mel frames
        val audio = FloatArray(frame.size) { frame[it].toFloat() }
        val melIn = mel.inputNames.iterator().next()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong()))
            .use { t ->
                mel.run(mapOf(melIn to t)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = res[0].value as Array<Array<Array<FloatArray>>>  // [1,1,F,32]
                    for (f in out[0][0]) {
                        melBuffer.add(FloatArray(f.size) { i -> f[i] / 10.0f + 2.0f })
                        melFramesSinceEmb++
                    }
                }
            }

        var fired = false

        // 2) every MEL_STRIDE new frames, if a full window exists, compute one embedding
        while (melBuffer.size >= MEL_WINDOW && melFramesSinceEmb >= MEL_STRIDE) {
            val start = melBuffer.size - MEL_WINDOW
            val flat = FloatArray(MEL_WINDOW * MEL_BINS)
            var k = 0
            for (i in 0 until MEL_WINDOW) {
                val row = melBuffer[start + i]
                for (v in row) flat[k++] = v
            }
            val embIn = emb.inputNames.iterator().next()
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flat),
                longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)
            ).use { t ->
                emb.run(mapOf(embIn to t)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = res[0].value as Array<Array<Array<FloatArray>>>  // [1,1,1,96]
                    embBuffer.add(out[0][0][0])
                }
            }
            if (embBuffer.size > EMB_WINDOW) embBuffer.removeAt(0)
            melFramesSinceEmb -= MEL_STRIDE

            // 3) classify once a full embedding window is available
            if (embBuffer.size == EMB_WINDOW) {
                val flatEmb = FloatArray(EMB_WINDOW * EMB_DIMS)
                var j = 0
                for (e in embBuffer) for (v in e) flatEmb[j++] = v
                val wwIn = ww.inputNames.iterator().next()
                var score = 0f
                OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(flatEmb),
                    longArrayOf(1, EMB_WINDOW.toLong(), EMB_DIMS.toLong())
                ).use { t ->
                    ww.run(mapOf(wwIn to t)).use { res ->
                        @Suppress("UNCHECKED_CAST")
                        val out = res[0].value as Array<FloatArray>  // [1,1]
                        score = out[0][0]
                    }
                }
                if (score > 0.1f) Log.d(TAG, "score=$score")
                val now = System.currentTimeMillis()
                if (score >= threshold && now - lastFire > REFRACTORY_MS) {
                    lastFire = now
                    fired = true
                    Log.i(TAG, "WAKE fired (score=$score)")
                }
            }
        }

        // keep the mel buffer bounded
        if (melBuffer.size > MEL_WINDOW * 4) {
            repeat(melBuffer.size - MEL_WINDOW * 2) { melBuffer.removeAt(0) }
        }
        return fired
    }

    override fun reset() {
        lastFire = System.currentTimeMillis()
        melFramesSinceEmb = 0
        embBuffer.clear()  // alte Embeddings löschen — verhindert Sofort-Trigger nach TTS
    }

    override fun close() {
        try {
            melSession?.close(); embSession?.close(); wwSession?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "OpenWakeWord"
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val MEL_STRIDE = 8
        private const val EMB_WINDOW = 16
        private const val EMB_DIMS = 96
        private const val REFRACTORY_MS = 2000L
    }
}
