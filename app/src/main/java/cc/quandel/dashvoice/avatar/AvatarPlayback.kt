package cc.quandel.dashvoice.avatar

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import cc.quandel.dashvoice.util.AppLog as Log
import java.io.File

/**
 * Wiedergabe-Engine des Avatars mit zwei ExoPlayer-Instanzen.
 *
 * - Der aktive Player spielt eine Playlist, in der immer genau der nächste Clip schon
 *   eingereiht ist. ExoPlayer puffert ihn vor und wechselt an der Clipgrenze gapless – das ist
 *   ein harter Schnitt ohne Ladezeit, möglich weil alle Clips auf dem Home-Frame beginnen/enden.
 * - Jeder Clip wird am Ende um [trimFrames] Frames gekürzt, damit der identische Home-Frame an
 *   der Nahtstelle nicht doppelt steht (Ruckler).
 * - Der zweite Player hält den wahrscheinlichsten dringenden Clip (listening bzw. wake_up)
 *   pausiert vorbereitet und wird nur für Sofortwechsel per Crossfade benutzt.
 * - Audio ist komplett deaktiviert (kein Decoding, kein Audiofokus).
 */
class AvatarPlayback(
    private val context: Context,
    private val overlay: AvatarOverlayView,
    private val machine: AvatarStateMachine,
    private val library: () -> ClipLibrary,
    private val crossfadeMs: Long,
    private val trimFrames: Int,
    private val onEnded: () -> Unit,
) {
    private inner class Slot(val view: TextureView) : Player.Listener {
        var player: ExoPlayer? = null
        var firstFrame = false
        var standbyScene: String? = null
        var onFirstFrame: (() -> Unit)? = null

        fun ensure(): ExoPlayer = player ?: ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 8_000, 100, 100)
                    .build(),
            )
            .build().also { p ->
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                p.volume = 0f
                p.repeatMode = Player.REPEAT_MODE_OFF
                p.setVideoTextureView(view)
                p.addListener(this)
                player = p
            }

        fun release() {
            player?.removeListener(this)
            player?.release()
            player = null
            firstFrame = false
            standbyScene = null
            onFirstFrame = null
            view.animate().cancel()
            view.alpha = 0f
        }

        override fun onRenderedFirstFrame() {
            firstFrame = true
            onFirstFrame?.let { onFirstFrame = null; it() }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            overlay.applyCover(view, videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (this !== active || crossfading || released) return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            val p = player ?: return
            val scene = sceneOf(mediaItem) ?: return
            machine.onClipStarted(scene)
            Log.i(TAG, "Clip → $scene (${fileOf(mediaItem)})")
            // Abgespielte Einträge entfernen, Playlist bleibt bei max. 2 Einträgen.
            if (p.currentMediaItemIndex > 0) p.removeMediaItems(0, p.currentMediaItemIndex)
            sync()
            ui.removeCallbacks(prepareStandby)
            ui.postDelayed(prepareStandby, STANDBY_DELAY_MS)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && this === active && !crossfading && !released) {
                finished()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Player-Fehler: ${error.errorCodeName} ${error.message}")
            if (this === active && !released) finished()
            else standbyScene = null
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val slotA = Slot(overlay.surfaceA)
    private val slotB = Slot(overlay.surfaceB)
    private var active = slotA
    private var standby = slotB
    private var crossfading = false
    private var released = false
    private val prepareStandby = Runnable { warmStandby() }

    /** Startet mit [scene] (Overlay war ausgeblendet; das Poster zeigt bereits den Home-Frame). */
    fun start(scene: String): Boolean {
        val clip = library().pick(scene) ?: return false
        val slot = active
        val p = slot.ensure()
        // Erst beim ersten Frame einblenden – bis dahin zeigt das Poster den Home-Frame.
        slot.view.alpha = 0f
        slot.firstFrame = false
        slot.onFirstFrame = { slot.view.alpha = 1f }
        p.setMediaItem(item(scene, clip))
        p.prepare()
        p.playWhenReady = true
        machine.onClipStarted(scene)
        Log.i(TAG, "Start → $scene (${clip.fileName})")
        sync()
        ui.postDelayed(prepareStandby, STANDBY_DELAY_MS)
        return true
    }

    /** Hält den eingereihten Folgeclip aktuell bzw. löst einen Sofortwechsel aus. */
    fun sync() {
        if (released || crossfading) return
        val p = active.player ?: return
        val remaining = remainingMs(p)
        machine.urgentSwitch(remaining)?.let {
            crossfadeTo(it)
            return
        }
        val want = machine.nextScene(remaining)
        val nextIndex = p.currentMediaItemIndex + 1
        val queued = if (p.mediaItemCount > nextIndex) sceneOf(p.getMediaItemAt(nextIndex)) else null
        if (want == queued) return
        if (p.mediaItemCount > nextIndex) p.removeMediaItems(nextIndex, p.mediaItemCount)
        if (want != null) {
            val clip = library().pick(want)
            if (clip != null) p.addMediaItem(item(want, clip))
        }
    }

    fun release() {
        released = true
        ui.removeCallbacksAndMessages(null)
        slotA.release()
        slotB.release()
    }

    private fun crossfadeTo(scene: String) {
        val from = active
        val to = standby
        val p = to.ensure()
        val ready = to.standbyScene == scene && to.firstFrame && p.playbackState == Player.STATE_READY
        val clip = if (ready) null else library().pick(scene) ?: return
        crossfading = true
        ui.removeCallbacks(prepareStandby)
        // Alter Player läuft unter dem Fade weiter, aber ohne Folgeclip.
        from.player?.let { p ->
            val next = p.currentMediaItemIndex + 1
            if (p.mediaItemCount > next) p.removeMediaItems(next, p.mediaItemCount)
        }
        if (clip != null) {
            to.firstFrame = false
            p.setMediaItem(item(scene, clip))
            p.prepare()
        }
        to.standbyScene = null
        to.view.alpha = 0f
        to.view.bringToFront()
        p.playWhenReady = true
        machine.onClipStarted(scene)
        Log.i(TAG, "Sofortwechsel → $scene (${if (ready) "vorgeladen" else "neu geladen"})")

        var done = false
        val begin = {
            if (!done && !released) {
                done = true
                to.view.animate().alpha(1f).setDuration(crossfadeMs).withEndAction {
                    if (released) return@withEndAction
                    from.player?.let { it.stop(); it.clearMediaItems() }
                    from.view.alpha = 0f
                    from.firstFrame = false
                    from.standbyScene = null
                    active = to
                    standby = from
                    crossfading = false
                    sync()
                    ui.postDelayed(prepareStandby, STANDBY_DELAY_MS)
                }.start()
            }
        }
        if (to.firstFrame) begin() else {
            to.onFirstFrame = begin
            ui.postDelayed({ begin() }, FIRST_FRAME_TIMEOUT_MS)
        }
    }

    /** Bereitet im zweiten Player den wahrscheinlichsten Folgeclip (z. B. listening → thinking) pausiert vor. */
    private fun warmStandby() {
        if (released || crossfading || active.player == null) return
        val scene = machine.likelyUrgentScene()
        if (standby.standbyScene == scene || !library().has(scene)) return
        val clip = library().pick(scene) ?: return
        val p = standby.ensure()
        standby.firstFrame = false
        standby.view.alpha = 0f
        p.playWhenReady = false
        p.setMediaItem(item(scene, clip))
        p.prepare()
        standby.standbyScene = scene
    }

    private fun finished() {
        Log.i(TAG, "Wiedergabe an Clipgrenze beendet")
        onEnded()
    }

    private fun remainingMs(p: ExoPlayer): Long {
        val d = p.duration
        if (d == C.TIME_UNSET) return library().byFileName(fileOf(p.currentMediaItem) ?: "")?.durationMs ?: 0L
        return (d - p.currentPosition).coerceAtLeast(0L)
    }

    private fun item(scene: String, clip: AvatarClip): MediaItem {
        val b = MediaItem.Builder()
            .setUri(Uri.fromFile(File(clip.path)))
            .setMediaId("$scene|${clip.fileName}")
        val trimMs = clip.frameMs * trimFrames
        if (trimMs > 0 && clip.durationMs > trimMs + MIN_CLIP_MS) {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setEndPositionMs(clip.durationMs - trimMs)
                    .build(),
            )
        }
        return b.build()
    }

    private fun sceneOf(item: MediaItem?): String? = item?.mediaId?.substringBefore('|')?.ifEmpty { null }
    private fun fileOf(item: MediaItem?): String? = item?.mediaId?.substringAfter('|', "")?.ifEmpty { null }

    companion object {
        private const val TAG = "AvatarPlayer"
        private const val STANDBY_DELAY_MS = 600L
        private const val FIRST_FRAME_TIMEOUT_MS = 800L
        private const val MIN_CLIP_MS = 200L
    }
}
