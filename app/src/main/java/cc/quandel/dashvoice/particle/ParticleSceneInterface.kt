package cc.quandel.dashvoice.particle

import android.webkit.JavascriptInterface
import cc.quandel.dashvoice.util.AppLog as Log

/**
 * JavaScript-zu-Kotlin Bridge. Wird von der WebView-JavaScript registriert
 * unter dem Namen "particleInterface".
 *
 * Nutzung in JavaScript:
 * ```
 * window.particleInterface?.setState("IDLE");
 * window.particleInterface?.setAudioLevel(0.5);
 * window.particleInterface?.log("Assembly complete");
 * ```
 */
class ParticleSceneInterface(private val controller: ParticleAssistantController) {

    @JavascriptInterface
    fun setState(state: String) {
        val ps = ParticleState.fromString(state)
        if (ps != null) {
            controller.transitionTo(ps)
        } else {
            Log.w("ParticleInterface", "Unknown state: $state")
        }
    }

    @JavascriptInterface
    fun setAudioLevel(level: Float) {
        controller.setAudioLevel(level)
    }

    @JavascriptInterface
    fun log(message: String) {
        Log.i("ParticleScene", message)
    }

    @JavascriptInterface
    fun getState(): String = controller.getState().name

    @JavascriptInterface
    fun isActive(): Boolean = controller.isActive()
}
