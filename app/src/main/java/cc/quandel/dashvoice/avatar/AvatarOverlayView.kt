package cc.quandel.dashvoice.avatar

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/**
 * Overlay für den Video-Avatar: ein zentriertes Panel mit festem Seitenverhältnis, darin
 * (von unten nach oben) das Poster (home.png, damit nie Schwarz zu sehen ist) und zwei
 * TextureViews für die beiden Player (Crossfade). Videos werden im Format „cover“ skaliert.
 *
 * Das Overlay konsumiert keine Touches – das Dashboard darunter bleibt bedienbar.
 */
class AvatarOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val panel = PanelLayout(context)
    val poster = ImageView(context)
    val surfaceA = TextureView(context)
    val surfaceB = TextureView(context)

    init {
        isClickable = false
        isFocusable = false
        panel.setBackgroundColor(Color.BLACK)
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        panel.addView(poster, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.addView(surfaceA, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.addView(surfaceB, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceA.alpha = 0f
        surfaceB.alpha = 0f
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    /** Seitenverhältnis Breite/Höhe des Panels, z. B. 16/9f oder 9/16f. */
    fun setAspect(ratio: Float, sizeFraction: Float) {
        panel.ratio = ratio
        panel.fraction = sizeFraction.coerceIn(0.2f, 1f)
        panel.requestLayout()
    }

    /** Lädt das Poster herunterskaliert auf die Panelgröße (spart Speicher). */
    fun setPoster(file: File?) {
        if (file == null) {
            poster.setImageDrawable(null)
            return
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val targetW = (if (panel.width > 0) panel.width else resources.displayMetrics.widthPixels).coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        poster.setImageBitmap(bmp)
    }

    fun clearPoster() = poster.setImageDrawable(null)

    /** Center-Crop-Transformation für eine TextureView (TextureView streckt sonst). */
    fun applyCover(view: TextureView, videoWidth: Int, videoHeight: Int, pixelRatio: Float) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        val videoRatio = videoWidth * pixelRatio / videoHeight
        val viewRatio = w / h
        val m = Matrix()
        if (videoRatio > viewRatio) m.setScale(videoRatio / viewRatio, 1f, w / 2f, h / 2f)
        else m.setScale(1f, viewRatio / videoRatio, w / 2f, h / 2f)
        view.setTransform(m)
    }

    fun fadeIn(durationMs: Long) {
        animate().cancel()
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        animate().alpha(1f).setDuration(durationMs).start()
    }

    fun fadeOut(durationMs: Long, end: () -> Unit) {
        animate().cancel()
        animate().alpha(0f).setDuration(durationMs).withEndAction {
            visibility = View.GONE
            end()
        }.start()
    }

    /** Panel mit festem Seitenverhältnis, maximal groß innerhalb des Elternbereichs × fraction. */
    private class PanelLayout(context: Context) : FrameLayout(context) {
        var ratio = 16f / 9f
        var fraction = 1f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availW = MeasureSpec.getSize(widthMeasureSpec) * fraction
            val availH = MeasureSpec.getSize(heightMeasureSpec) * fraction
            var w = availW
            var h = availW / ratio
            if (h > availH) {
                h = availH
                w = availH * ratio
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h.toInt(), MeasureSpec.EXACTLY),
            )
        }
    }
}
