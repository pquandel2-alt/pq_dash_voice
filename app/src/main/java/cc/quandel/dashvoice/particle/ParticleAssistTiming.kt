package cc.quandel.dashvoice.particle

import kotlin.math.max

/** Pure timing policy for returning from the temporary Assist avatar to the regular screensaver. */
object ParticleAssistTiming {
    fun hideDelayMs(
        shownAtMs: Long,
        nowMs: Long,
        minimumVisibleMs: Long,
        completionHoldMs: Long,
    ): Long = max(
        completionHoldMs.coerceAtLeast(0L),
        (minimumVisibleMs.coerceAtLeast(0L) - (nowMs - shownAtMs).coerceAtLeast(0L))
            .coerceAtLeast(0L),
    )
}
