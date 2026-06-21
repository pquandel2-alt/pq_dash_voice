package cc.quandel.dashvoice

/**
 * Erkennt einen lokalen Timer-/Wecker-Befehl im Vosk-Transkript und parst die Dauer (DE).
 * Beispiele: „stell einen timer auf fünf minuten", „timer auf 10 minuten",
 * „wecker in einer halben stunde", „timer 30 sekunden".
 */
object LocalTimer {

    private val NUM = linkedMapOf(
        "anderthalb" to 1.5, "eineinhalb" to 1.5,
        "fünfzehn" to 15.0, "zwanzig" to 20.0, "dreißig" to 30.0, "vierzig" to 40.0,
        "fünfzig" to 50.0, "sechzig" to 60.0, "neunzig" to 90.0, "hundert" to 100.0,
        "elf" to 11.0, "zwölf" to 12.0, "dreizehn" to 13.0, "vierzehn" to 14.0,
        "eine" to 1.0, "einen" to 1.0, "einer" to 1.0, "ein" to 1.0, "eins" to 1.0,
        "zwei" to 2.0, "drei" to 3.0, "vier" to 4.0, "fünf" to 5.0, "sechs" to 6.0,
        "sieben" to 7.0, "acht" to 8.0, "neun" to 9.0, "zehn" to 10.0
    )

    /** @return Dauer in ms oder null, wenn es kein Timer-Intent ist. */
    fun parse(text: String): Long? {
        val t = " ${text.lowercase().trim()} "
        val isTimer = t.contains("timer") || t.contains("wecker") || t.contains("erinner")
        if (!isTimer) return null

        // Einheit finden; die Zahl steht direkt DAVOR (so wird „ein timer" nicht als 1 gewertet).
        val unit = Regex("\\b(sekunden?|minuten?|stunden?)\\b").find(t) ?: return null
        val unitMs = when {
            unit.value.startsWith("stunde") -> 3_600_000.0
            unit.value.startsWith("sekunde") -> 1_000.0
            else -> 60_000.0
        }
        val before = t.substring(0, unit.range.first).trim()
        val value = numberBefore(before) ?: return null
        val ms = (value * unitMs).toLong()
        return if (ms in 1_000..86_400_000) ms else null
    }

    /** Liest die Zahl aus den 1–2 Wörtern unmittelbar vor der Einheit. */
    private fun numberBefore(before: String): Double? {
        val words = before.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val tail = words.takeLast(2)
        val joined = tail.joinToString(" ")
        if (joined.contains("anderthalb") || joined.contains("eineinhalb")) return 1.5
        if (joined.contains("halb")) return 0.5   // „halbe/halben"
        val last = tail.last()
        last.replace(',', '.').toDoubleOrNull()?.let { return it }
        Regex("\\d+([.,]\\d+)?").find(last)?.let { return it.value.replace(',', '.').toDoubleOrNull() }
        return NUM[last]
    }

    fun humanize(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600} Std" + if (s % 3600 >= 60) " ${(s % 3600) / 60} Min" else ""
            s >= 60 -> "${s / 60} Min" + if (s % 60 != 0L) " ${s % 60} Sek" else ""
            else -> "$s Sek"
        }
    }
}
