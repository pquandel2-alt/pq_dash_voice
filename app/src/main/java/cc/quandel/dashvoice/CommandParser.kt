package cc.quandel.dashvoice

/**
 * Wandelt einen lokal (Vosk) erkannten Sprachbefehl in 1..n konkrete HA-Befehle:
 * - normalisiert häufige Verhörer (z. B. „schaltet"→„schalte"),
 * - trennt Mehrgeräte-Befehle an „und"/Komma,
 * - korrigiert Zielnamen per Fuzzy-Match auf echte Gerätenamen.
 */
object CommandParser {

    // Verhörer/Flexions-Normalisierung (Wortgrenzen-sicher)
    private val SYN = listOf(
        "schaltet" to "schalte", "schalter" to "schalte", "schaltete" to "schalte",
        "schalten" to "schalte",
        "aktiviert" to "aktiviere", "aktivieren" to "aktiviere", "aktivier" to "aktiviere",
        "deaktiviert" to "deaktiviere", "deaktivieren" to "deaktiviere", "deaktivier" to "deaktiviere",
        "mach mal" to "schalte", "schließ" to "schließe", "öffne mal" to "öffne"
    )

    fun normalize(text: String): String {
        var t = " ${text.lowercase().trim()} "
        for ((a, b) in SYN) t = t.replace(" $a ", " $b ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * @return Liste konkreter Befehle (1..n) oder null, wenn es kein Befehl ist (→ Frage/Fallback).
     */
    fun parse(text: String, verbs: List<String>, entities: List<String>, threshold: Float): List<String>? {
        val t = normalize(text)
        if (t.isBlank() || verbs.none { t.contains(it) }) return null

        // Familie 1: "schalte <ziele> ein|an|aus|ab"
        Regex("^(?:schalte|mach)\\s+(.*?)\\s+(ein|an|aus|ab)$").find(t)?.let { m ->
            val state = if (m.groupValues[2] in setOf("aus", "ab")) "aus" else "ein"
            return splitTargets(m.groupValues[1]).map { "schalte ${correct(it, entities, threshold)} $state" }
        }
        // Familie 2: "aktiviere|deaktiviere|starte|stoppe|öffne|schließe|fahre <ziele>"
        Regex("^(aktiviere|deaktiviere|starte|stoppe|öffne|schließe|fahre|dimme)\\s+(.*)$").find(t)?.let { m ->
            val verb = m.groupValues[1]
            return splitTargets(m.groupValues[2]).map { "$verb ${correct(it, entities, threshold)}" }
        }
        // sonst: ganzen normalisierten Text als einen Befehl
        return listOf(t)
    }

    private fun splitTargets(s: String): List<String> =
        s.split(Regex("\\s+und\\s+|\\s*,\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Bidirektionaler Score: min(score(text→name), score(name→text)).
     * Verhindert, dass kurze Script-Namen ("Nacht") auf lange Texte ("gute nacht") feuern,
     * weil dann score(name→text) niedrig ist (z. B. "gute" hat kein Pendant in "nacht").
     */
    fun scoreBidirectional(text: String, name: String): Float =
        minOf(score(text, name), score(name, text))

    /** Ersetzt eine Ziel-Phrase durch den ähnlichsten echten Gerätenamen (wenn ≥ Schwelle). */
    fun correct(target: String, entities: List<String>, threshold: Float): String {
        if (entities.isEmpty()) return target
        var best = target; var bestScore = 0f
        for (name in entities) {
            val s = score(target, name)
            if (s > bestScore) { bestScore = s; best = name }
        }
        return if (bestScore >= threshold) best else target
    }

    /** Token-Score: jedes Wort des Gerätenamens sucht sein bestes Pendant im Ziel (0..1). */
    private fun score(target: String, name: String): Float {
        val tw = target.split(' ').filter { it.isNotBlank() }
        val nw = name.split(' ').filter { it.isNotBlank() }
        if (tw.isEmpty() || nw.isEmpty()) return 0f
        var sum = 0f
        for (n in nw) {
            var b = 0f
            for (x in tw) { val s = sim(n, x); if (s > b) b = s }
            sum += b
        }
        return sum / nw.size
    }

    private fun sim(a: String, b: String): Float {
        val m = maxOf(a.length, b.length)
        return if (m == 0) 1f else 1f - lev(a, b).toFloat() / m
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
