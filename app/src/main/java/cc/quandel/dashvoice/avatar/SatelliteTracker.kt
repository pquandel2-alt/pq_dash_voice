package cc.quandel.dashvoice.avatar

/**
 * Merkt sich Zustand und Änderungszeitpunkt aller Satelliten und bildet daraus den
 * aggregierten Zustand. Ein Satellit, der länger als sein Hänger-Limit im selben aktiven
 * Zustand steht (z. B. HA-Pipeline ohne erkannte Sprache bleibt ewig "listening"), zählt als
 * idle – so kann ein hängender Satellit den Avatar nicht dauerhaft einblenden.
 */
class SatelliteTracker(private val limits: Limits = Limits()) {

    data class Limits(
        val listeningMs: Long = 30_000L,
        val processingMs: Long = 60_000L,
        val respondingMs: Long = 300_000L,
    ) {
        fun of(state: AssistState): Long = when (state) {
            AssistState.LISTENING -> listeningMs
            AssistState.PROCESSING -> processingMs
            AssistState.RESPONDING -> respondingMs
            AssistState.IDLE -> Long.MAX_VALUE
        }
    }

    private data class Entry(val state: AssistState, val since: Long)

    private val entries = HashMap<String, Entry>()

    val size: Int get() = entries.size

    fun clear() = entries.clear()

    /**
     * Neuer HA-Zustand; null = Entität entfernt. [ageMs] = wie lange HA den Zustand schon
     * meldet (aus last_changed), damit ein schon länger hängender Satellit nach einem
     * Reconnect nicht erneut die volle Frist bekommt.
     */
    fun update(entityId: String, haState: String?, now: Long, ageMs: Long = 0L) {
        if (haState == null) {
            entries.remove(entityId)
            return
        }
        val state = AssistState.fromHa(haState)
        val previous = entries[entityId]
        if (previous == null || previous.state != state) {
            entries[entityId] = Entry(state, now - ageMs.coerceIn(0L, MAX_AGE_MS))
        }
    }

    fun aggregate(now: Long): AssistState =
        AssistState.aggregate(entries.values.map { if (isStale(it, now)) AssistState.IDLE else it.state })

    /** Satelliten, die gerade wegen Hänger-Limit ignoriert werden (für das Log). */
    fun staleIds(now: Long): List<String> = entries.filter { isStale(it.value, now) }.keys.sorted()

    private companion object {
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }

    private fun isStale(e: Entry, now: Long) = e.state != AssistState.IDLE && now - e.since >= limits.of(e.state)
}
