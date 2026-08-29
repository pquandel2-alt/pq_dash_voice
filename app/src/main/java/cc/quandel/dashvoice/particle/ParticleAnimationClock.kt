package cc.quandel.dashvoice.particle

/** Keeps animation time small enough to retain frame-level Float precision. */
internal object ParticleAnimationClock {
    fun elapsedSeconds(nowMs: Long, startedAtMs: Long, speed: Float): Float =
        (nowMs - startedAtMs).coerceAtLeast(0L) / 1_000f * speed
}
