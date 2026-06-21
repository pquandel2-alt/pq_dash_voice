package cc.quandel.dashvoice.wake

import android.content.Context
import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline-Spracherkennung (Vosk) für Sofort-Befehle — transkribiert lokal auf dem Tablet,
 * komplett ohne HA/Whisper. Das DE-Modell liegt in assets/model-de und wird einmalig nach
 * filesDir/vosk-de entpackt (45 MB → ~92 MB), danach per Marker übersprungen.
 *
 * Audioformat muss 16 kHz Mono 16-bit sein (= AudioCapture-Format).
 */
class LocalRecognizer(context: Context) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    @Volatile var available: Boolean = false
        private set

    init {
        available = try {
            val dir = ensureModel(context)
            val m = Model(dir.absolutePath)
            model = m
            recognizer = Recognizer(m, SAMPLE_RATE)
            Log.i(TAG, "Vosk bereit (Modell ${dir.name})")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Vosk init fehlgeschlagen: ${e.message}")
            false
        }
    }

    /** Füttert einen 16-kHz-Mono-Frame in den Erkenner. */
    fun accept(frame: ShortArray) {
        try { recognizer?.acceptWaveForm(frame, frame.size) } catch (_: Throwable) {}
    }

    /** Liefert das End-Transkript (lowercased, getrimmt) und setzt den Erkenner zurück. */
    fun finalText(): String {
        val r = recognizer ?: return ""
        return try {
            val json = r.finalResult
            r.reset()
            JSONObject(json).optString("text", "").trim().lowercase()
        } catch (_: Throwable) { "" }
    }

    fun reset() { try { recognizer?.reset() } catch (_: Throwable) {} }

    fun close() {
        try { recognizer?.close() } catch (_: Throwable) {}
        try { model?.close() } catch (_: Throwable) {}
        recognizer = null; model = null; available = false
    }

    /** Kopiert assets/model-de rekursiv nach filesDir/vosk-de (einmalig, per Marker-Datei). */
    private fun ensureModel(ctx: Context): File {
        val target = File(ctx.filesDir, MODEL_DIR)
        val marker = File(target, ".unpacked")
        if (marker.exists()) return target
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        copyAsset(ctx, ASSET_DIR, target)
        marker.writeText("ok")
        Log.i(TAG, "Vosk-Modell entpackt nach ${target.absolutePath}")
        return target
    }

    private fun copyAsset(ctx: Context, assetPath: String, out: File) {
        val children = try { ctx.assets.list(assetPath) } catch (_: Exception) { null }
        if (children.isNullOrEmpty()) {
            // Blatt = Datei
            out.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
        } else {
            out.mkdirs()
            for (c in children) copyAsset(ctx, "$assetPath/$c", File(out, c))
        }
    }

    companion object {
        private const val TAG = "Vosk"
        private const val SAMPLE_RATE = 16000.0f
        private const val ASSET_DIR = "model-de"
        private const val MODEL_DIR = "vosk-de"
    }
}
