package cc.quandel.dashvoice.particle

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Koordiniert die Partikel-Screensaver-Szene mit dem Sprachassistenten-State.
 *
 * Verantwortlichkeiten:
 * - State Machine für Partikel-States
 * - JavaScript-Interface zu WebView
 * - Übergänge triggern (Wake → LISTENING, TTS → SPEAKING, etc.)
 * - Audio-Level Feedback (für Mund-Sync)
 * - Scene Reset beim Screensaver-Start
 */
class ParticleAssistantController(private val webView: WebView) {

    private val stateMachine = ParticleStateMachine()
    private val handler = Handler(Looper.getMainLooper())
    private val audioLevel = AtomicReference(0f)

    init {
        // JavaScript-Interface registrieren
        webView.addJavascriptInterface(
            ParticleSceneInterface(this),
            "particleInterface"
        )
        stateMachine.onStateChanged = { prev, new ->
            onStateTransitioned(prev, new)
        }
    }

    fun resetScene() {
        Log.i("ParticleController", "Screensaver state → ASSEMBLING (scene reset)")
        stateMachine.transitionTo(ParticleState.ASSEMBLING)
        // reset() rebuilds particles at fresh spawn positions, setState('ASSEMBLING') starts the
        // flow-to-target animation. Also harmless as a no-op if the page hasn't finished loading yet —
        // the page's own bootstrap script (particle-screensaver.html) independently starts in
        // ASSEMBLING on every fresh load, so this call is redundant-but-safe rather than load-bearing.
        executeJS("window.particleScene?.reset(); window.particleScene?.setState('ASSEMBLING');")
        setTranscript(null)
        setResponse(null)
    }

    fun transitionTo(state: ParticleState) {
        if (!stateMachine.transitionTo(state)) return
        Log.i("ParticleController", "Assistant state → ${state.name}")
        executeJS("window.particleScene?.setState('${state.name}');")
    }

    fun setAudioLevel(level: Float) {
        audioLevel.set(level.coerceIn(0f, 1f))
        executeJS("window.particleScene?.setAudioLevel($level);")
    }

    /** Zeigt/versteckt das Transkript-Overlay in der Partikel-Szene. null/leer = ausblenden. */
    fun setTranscript(text: String?) {
        executeJS("window.setTranscript?.(${JSONObject.quote(text ?: "")});")
    }

    /** Zeigt/versteckt das Antwort-Overlay in der Partikel-Szene. null/leer = ausblenden. */
    fun setResponse(text: String?) {
        executeJS("window.setResponse?.(${JSONObject.quote(text ?: "")});")
    }

    fun getState(): ParticleState = stateMachine.getState()

    fun isActive(): Boolean = stateMachine.isActive()

    fun pause() {
        Log.i("ParticleController", "Paused")
        executeJS("window.particleScene?.pause();")
    }

    fun resume() {
        Log.i("ParticleController", "Resumed")
        executeJS("window.particleScene?.resume();")
    }

    fun destroy() {
        Log.i("ParticleController", "Destroyed")
        pause()
        stateMachine.transitionTo(ParticleState.OFF)
    }

    private fun onStateTransitioned(@Suppress("UNUSED_PARAMETER") prev: ParticleState, new: ParticleState) {
        when (new) {
            ParticleState.LISTENING -> {
                Log.i("ParticleController", "User is listening to Mic")
            }
            ParticleState.THINKING -> {
                Log.i("ParticleController", "Processing user input")
            }
            ParticleState.SPEAKING -> {
                Log.i("ParticleController", "Playing TTS response")
            }
            ParticleState.SUCCESS -> {
                Log.i("ParticleController", "Action completed successfully")
                // Auto-return to IDLE nach Kurzer Zeit
                handler.postDelayed({
                    transitionTo(ParticleState.IDLE)
                }, 1500)
            }
            ParticleState.ERROR -> {
                Log.i("ParticleController", "An error occurred")
                // Auto-return to IDLE
                handler.postDelayed({
                    transitionTo(ParticleState.IDLE)
                }, 2000)
            }
            else -> {}
        }
    }

    private fun executeJS(script: String) {
        handler.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.w("ParticleController", "JS execution failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ParticleController"
    }
}
