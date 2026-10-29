package cc.quandel.dashvoice.avatar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cc.quandel.dashvoice.Prefs
import cc.quandel.dashvoice.util.AppLog as Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Verbindet HA-WebSocket, Clip-Cache, Zustandslogik und Wiedergabe des Video-Avatars.
 * Lebt in der MainActivity; alle Methoden im Main-Thread aufrufen.
 */
class AvatarController(
    private val context: Context,
    private val overlay: AvatarOverlayView,
    /** Overlay wurde sichtbar (true) bzw. ausgeblendet (false). */
    private val onVisibilityChanged: (Boolean) -> Unit,
) : HaWebSocketClient.Listener {

    private val prefs = Prefs(context)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "avatar-cache").apply { isDaemon = true } }
    private val cache = ClipCache(File(context.filesDir, "avatar"))
    private var library = ClipLibrary(cache.clips())
    private val machine = AvatarStateMachine(available = { library.has(it) }, clock = { SystemClock.elapsedRealtime() })

    private var ws: HaWebSocketClient? = null
    private var playback: AvatarPlayback? = null
    private var fadingOut = false
    private var destroyed = false

    private var enabled = false
    private var connectionKey = ""
    private var cacheKey = ""
    private var nightEntity = ""
    private var clipEntity = ""
    private var satellitePrefix = ""
    private var crossfadeMs = 200L
    private var trimFrames = 1

    private val satellites = HashMap<String, String?>()
    private var lastClipValue: String? = null

    private val tick = object : Runnable {
        override fun run() {
            playback?.sync()
            if (playback != null) ui.postDelayed(this, TICK_MS)
        }
    }
    private val periodicSync = object : Runnable {
        override fun run() {
            syncCache()
            ui.postDelayed(this, RESYNC_MS)
        }
    }

    /** Ist der Avatar aktiv und einsatzbereit (Token gesetzt, Clips vorhanden)? */
    val isActive: Boolean get() = enabled && !library.isEmpty

    val isVisible: Boolean get() = playback != null

    /** Liest die Einstellungen (neu) und startet/stoppt Verbindung und Cache-Sync bei Bedarf. */
    fun applySettings() {
        if (destroyed) return
        val baseUrl = HaMessages.baseUrl(prefs.dashboardUrl)
        val token = prefs.haToken
        enabled = prefs.avatarEnabled && token.isNotBlank() && baseUrl.startsWith("http")

        machine.config = AvatarStateMachine.Config(
            lingerMs = prefs.avatarLingerMs.coerceAtLeast(0L),
            urgentThresholdMs = prefs.avatarUrgentThresholdMs.coerceAtLeast(0L),
            alwaysShow = prefs.avatarAlwaysShow,
        )
        crossfadeMs = prefs.avatarCrossfadeMs.coerceIn(0L, 1_000L)
        trimFrames = prefs.avatarTrimFrames.coerceIn(0, 3)
        val parts = prefs.avatarAspect.split(':').mapNotNull { it.trim().toFloatOrNull() }
        val ratio = if (parts.size == 2 && parts[1] > 0f) parts[0] / parts[1] else 16f / 9f
        overlay.setAspect(ratio, prefs.avatarSizePercent / 100f)

        if (!enabled) {
            stopConnection()
            ui.removeCallbacks(periodicSync)
            hideNow()
            return
        }

        nightEntity = prefs.avatarNightEntity.trim()
        clipEntity = prefs.avatarClipEntity.trim()
        satellitePrefix = prefs.avatarSatellitePrefix.trim().ifEmpty { Prefs.DEFAULT_AVATAR_PREFIX }
        val newConnectionKey = listOf(baseUrl, token, satellitePrefix, nightEntity, clipEntity).joinToString("\n")
        if (newConnectionKey != connectionKey || ws == null) {
            stopConnection()
            connectionKey = newConnectionKey
            ws = HaWebSocketClient(
                baseUrl, token, satellitePrefix,
                listOf(nightEntity, clipEntity).filter { it.isNotEmpty() },
                this,
            ).also { it.start() }
        }
        val newCacheKey = listOf(baseUrl, prefs.avatarClipPath, prefs.avatarExtraClips).joinToString("\n")
        if (newCacheKey != cacheKey) {
            cacheKey = newCacheKey
            ui.removeCallbacks(periodicSync)
            ui.post(periodicSync)
        }
        evaluate()
    }

    fun destroy() {
        destroyed = true
        stopConnection()
        ui.removeCallbacksAndMessages(null)
        hideNow()
        io.shutdownNow()
    }

    // ── HA-Zustände ─────────────────────────────────────────────────────────────────────────

    override fun onSnapshot(updates: List<HaMessages.StateUpdate>) {
        satellites.clear()
        apply(updates)
    }

    override fun onUpdates(updates: List<HaMessages.StateUpdate>) = apply(updates)

    override fun onConnectionChanged(connected: Boolean) {
        if (!connected && satellites.isNotEmpty()) {
            // Ohne Verbindung keine verlässlichen Zustände → als idle behandeln (blendet nach Nachlauf aus).
            satellites.clear()
            machine.onAssistState(AssistState.IDLE)
            evaluate()
        }
    }

    private fun apply(updates: List<HaMessages.StateUpdate>) {
        if (!enabled) return
        for (u in updates) {
            when {
                u.entityId == nightEntity -> machine.onNightMode(u.state == "on")
                u.entityId == clipEntity -> onClipValue(u)
                u.entityId.startsWith(satellitePrefix) ->
                    if (u.state == null) satellites.remove(u.entityId) else satellites[u.entityId] = u.state
            }
        }
        val aggregate = AssistState.aggregateHa(satellites.values)
        if (aggregate != machine.assistState) Log.i(TAG, "Satelliten → $aggregate $satellites")
        machine.onAssistState(aggregate)
        evaluate()
    }

    private fun onClipValue(u: HaMessages.StateUpdate) {
        val value = u.state
        val previous = lastClipValue
        lastClipValue = value
        // Anfangszustand (Snapshot) ist keine Änderung → nicht abspielen.
        if (u.initial || value == null || value == previous) return
        val scene = HaMessages.sceneFromClipValue(value) ?: return
        val accepted = machine.onOneShot(scene)
        Log.i(TAG, "One-Shot '$scene' ${if (accepted) "angenommen" else "ignoriert (nicht idle/Schlaf/kein Clip)"}")
    }

    // ── Sichtbarkeit / Wiedergabe ───────────────────────────────────────────────────────────

    /** Nach jeder Eingangsänderung: einblenden, Folgeclip aktualisieren oder Sofortwechsel. */
    private fun evaluate() {
        if (destroyed || !enabled || fadingOut) return
        val pb = playback
        if (pb != null) {
            pb.sync()
            return
        }
        if (library.isEmpty) return
        val scene = machine.startScene() ?: return
        show(scene)
    }

    private fun show(scene: String) {
        overlay.setPoster(cache.posterFile())
        val pb = AvatarPlayback(context, overlay, machine, { library }, crossfadeMs, trimFrames, ::onPlaybackEnded)
        if (!pb.start(scene)) {
            pb.release()
            overlay.clearPoster()
            return
        }
        playback = pb
        Log.i(TAG, "Avatar einblenden mit '$scene'")
        overlay.fadeIn(FADE_MS)
        onVisibilityChanged(true)
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, TICK_MS)
    }

    private fun onPlaybackEnded() {
        if (fadingOut) return
        fadingOut = true
        ui.removeCallbacks(tick)
        Log.i(TAG, "Avatar ausblenden")
        overlay.fadeOut(FADE_MS) {
            fadingOut = false
            releasePlayback()
            onVisibilityChanged(false)
            evaluate()   // falls in der Zwischenzeit wieder etwas aktiv wurde
        }
    }

    private fun hideNow() {
        if (playback == null) return
        ui.removeCallbacks(tick)
        overlay.animate().cancel()
        overlay.visibility = android.view.View.GONE
        fadingOut = false
        releasePlayback()
        onVisibilityChanged(false)
    }

    private fun releasePlayback() {
        playback?.release()
        playback = null
        overlay.clearPoster()
        machine.onPlaybackStopped()
    }

    private fun stopConnection() {
        ws?.stop()
        ws = null
        connectionKey = ""
        satellites.clear()
        lastClipValue = null
        machine.onAssistState(AssistState.IDLE)
    }

    // ── Cache ───────────────────────────────────────────────────────────────────────────────

    private fun syncCache() {
        val baseUrl = HaMessages.baseUrl(prefs.dashboardUrl)
        val path = prefs.avatarClipPath.ifBlank { Prefs.DEFAULT_AVATAR_PATH }
        val extras = prefs.avatarExtraClips.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val extraFiles = extras.filter { it.contains('.') }
        val extraBases = extras.filterNot { it.contains('.') }
        io.execute {
            val changed = try {
                cache.sync(baseUrl, path, extraBases, extraFiles)
            } catch (e: Exception) {
                Log.w(TAG, "Cache-Sync fehlgeschlagen: ${e.message}")
                false
            }
            val clips = cache.clips()
            ui.post {
                if (destroyed) return@post
                if (changed || library.isEmpty) {
                    library = ClipLibrary(clips)
                    Log.i(TAG, "Clip-Bibliothek: ${clips.size} Clips")
                }
                evaluate()
            }
        }
    }

    companion object {
        private const val TAG = "Avatar"
        private const val FADE_MS = 200L
        private const val TICK_MS = 200L
        private const val RESYNC_MS = 6L * 60 * 60 * 1000
    }
}
