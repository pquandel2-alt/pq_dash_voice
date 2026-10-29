package cc.quandel.dashvoice.avatar

import kotlin.random.Random

/** Ein lokal gecachter Clip. [durationMs]/[frameMs] sind 0, falls unbekannt. */
data class AvatarClip(
    val fileName: String,
    val path: String,
    val durationMs: Long = 0L,
    val frameMs: Long = 0L,
) {
    /** Basisname ohne Variantennummer, z. B. "speaking_a" für "speaking_a_1.mp4". */
    val base: String get() = baseOf(fileName)

    companion object {
        private val NUMBERED = Regex("^(.+)_(\\d+)\\.mp4$", RegexOption.IGNORE_CASE)
        private val PLAIN = Regex("^(.+)\\.mp4$", RegexOption.IGNORE_CASE)

        fun baseOf(fileName: String): String {
            NUMBERED.find(fileName)?.let { return it.groupValues[1].lowercase() }
            PLAIN.find(fileName)?.let { return it.groupValues[1].lowercase() }
            return fileName.lowercase()
        }
    }
}

/**
 * Zuordnung Szene → Varianten.
 *
 * Varianten einer Szene `S` sind alle Clips mit Basisname `S` (`S_1.mp4`, `S_2.mp4` …) sowie
 * Gruppen-Clips `S_<buchstabe>_<n>.mp4` – so gehören speaking_a/b/c zur Szene „speaking“,
 * während look_left nicht zu einer Szene „look“ zählt.
 *
 * Bei mehreren Varianten wird zufällig gewählt, aber nie zweimal hintereinander dieselbe.
 */
class ClipLibrary(clips: Collection<AvatarClip>, private val random: Random = Random.Default) {

    private val clips: List<AvatarClip> = clips.sortedBy { it.fileName }
    private val lastPick = HashMap<String, String>()
    private val cache = HashMap<String, List<AvatarClip>>()

    val isEmpty: Boolean get() = clips.isEmpty()
    val fileNames: List<String> get() = clips.map { it.fileName }

    fun variants(scene: String): List<AvatarClip> {
        val key = scene.trim().lowercase()
        return cache.getOrPut(key) {
            val group = Regex("^${Regex.escape(key)}_[a-z]$")
            clips.filter { it.base == key || group.matches(it.base) }
        }
    }

    fun has(scene: String): Boolean = variants(scene).isNotEmpty()

    fun pick(scene: String): AvatarClip? {
        val key = scene.trim().lowercase()
        val options = variants(key)
        if (options.isEmpty()) return null
        val last = lastPick[key]
        val pool = if (options.size > 1) options.filter { it.fileName != last } else options
        val chosen = pool[random.nextInt(pool.size)]
        lastPick[key] = chosen.fileName
        return chosen
    }

    fun byFileName(fileName: String): AvatarClip? = clips.firstOrNull { it.fileName == fileName }
}
