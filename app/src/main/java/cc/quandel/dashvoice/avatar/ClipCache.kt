package cc.quandel.dashvoice.avatar

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import cc.quandel.dashvoice.util.AppLog as Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lokaler Clip-Cache. Beim Sync werden alle Clips per bedingtem GET (ETag / Last-Modified,
 * Fallback Dateigröße) geprüft und nur bei Änderung neu geladen. Varianten `<basis>_<n>.mp4`
 * werden durch Probieren von n = 1, 2, … gefunden, bis eine Datei fehlt. Abgespielt wird
 * ausschließlich aus diesem Verzeichnis.
 *
 * [sync] blockiert (Netzwerk) und muss auf einem Hintergrund-Thread laufen.
 */
class ClipCache(private val dir: File) {

    private data class Entry(
        val etag: String?,
        val lastModified: String?,
        val size: Long,
        val durationMs: Long,
        val frameMs: Long,
    )

    private enum class Fetch { UNCHANGED, UPDATED, MISSING }

    private val manifestFile = File(dir, "manifest.json")
    private val entries = LinkedHashMap<String, Entry>()

    init {
        dir.mkdirs()
        loadManifest()
    }

    @Synchronized
    fun clips(): List<AvatarClip> = entries.mapNotNull { (name, e) ->
        val f = File(dir, name)
        if (!name.endsWith(".mp4", ignoreCase = true) || !f.isFile) null
        else AvatarClip(name, f.absolutePath, e.durationMs, e.frameMs)
    }

    @Synchronized
    fun posterFile(): File? = File(dir, AvatarScenes.POSTER).takeIf { it.isFile && entries.containsKey(AvatarScenes.POSTER) }

    /**
     * Gleicht den Cache mit dem Server ab. Gibt true zurück, wenn sich etwas geändert hat.
     * Bei Netzwerkfehlern bleibt der bisherige Cache unverändert erhalten.
     */
    fun sync(baseUrl: String, path: String, extraBases: Collection<String>, extraFiles: Collection<String>): Boolean {
        val root = baseUrl.trimEnd('/') + "/" + path.trim().trim('/') + "/"
        val present = LinkedHashSet<String>()
        var changed = false
        try {
            fun check(name: String): Boolean = when (fetch(root, name)) {
                Fetch.MISSING -> false
                Fetch.UPDATED -> { changed = true; present += name; true }
                Fetch.UNCHANGED -> { present += name; true }
            }

            check(AvatarScenes.POSTER)
            val bases = (AvatarScenes.DEFAULT_BASES + extraBases)
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            for (base in bases) {
                for (n in 1..MAX_VARIANTS) {
                    if (!check("${base}_$n.mp4")) break
                }
            }
            for (name in extraFiles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
                if (name !in present) check(name)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Sync abgebrochen (${e.message}) – nutze bisherigen Cache")
            return changed
        }

        synchronized(this) {
            val stale = entries.keys.filter { it !in present }
            for (name in stale) {
                entries.remove(name)
                File(dir, name).delete()
                changed = true
            }
            if (changed) saveManifest()
        }
        Log.i(TAG, "Sync fertig: ${present.size} Dateien${if (changed) " (geändert)" else ""}")
        return changed
    }

    private fun fetch(root: String, name: String): Fetch {
        val cached = synchronized(this) { entries[name] }
        val target = File(dir, name)
        val conn = URL(root + name).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            if (cached != null && target.isFile) {
                cached.etag?.let { conn.setRequestProperty("If-None-Match", it) }
                cached.lastModified?.let { conn.setRequestProperty("If-Modified-Since", it) }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && cached != null) return Fetch.UNCHANGED
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return Fetch.MISSING
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code für $name")

            val etag = conn.getHeaderField("ETag")
            val lastModified = conn.getHeaderField("Last-Modified")
            val length = conn.contentLengthLong
            if (cached != null && target.isFile && etag == null && lastModified == null &&
                length > 0 && length == cached.size && target.length() == length
            ) {
                return Fetch.UNCHANGED
            }

            val tmp = File(dir, "$name.part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            if (length > 0 && tmp.length() != length) {
                tmp.delete()
                throw IOException("Download von $name unvollständig")
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Kann $name nicht speichern")
            }
            val (durationMs, frameMs) = if (name.endsWith(".mp4", true)) probe(target) else 0L to 0L
            synchronized(this) {
                entries[name] = Entry(etag, lastModified, target.length(), durationMs, frameMs)
                saveManifest()
            }
            Log.i(TAG, "Geladen: $name (${target.length() / 1024} KB, ${durationMs} ms, Frame ${frameMs} ms)")
            return Fetch.UPDATED
        } finally {
            conn.disconnect()
        }
    }

    /** Dauer und Frame-Dauer eines Clips (0 = unbekannt). */
    private fun probe(file: File): Pair<Long, Long> {
        var durationMs = 0L
        var frameMs = 0L
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dauer von ${file.name} unbekannt: ${e.message}")
        }
        try {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val fps = try {
                            f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        } catch (_: ClassCastException) {
                            f.getFloat(MediaFormat.KEY_FRAME_RATE)
                        }
                        if (fps > 1f) frameMs = (1000f / fps).toLong()
                    }
                    break
                }
            } finally {
                ex.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Framerate von ${file.name} unbekannt: ${e.message}")
        }
        return durationMs to frameMs
    }

    private fun loadManifest() {
        if (!manifestFile.isFile) return
        try {
            val json = JSONObject(manifestFile.readText())
            for (name in json.keys()) {
                val o = json.getJSONObject(name)
                entries[name] = Entry(
                    etag = o.optString("etag").ifEmpty { null },
                    lastModified = o.optString("lastModified").ifEmpty { null },
                    size = o.optLong("size"),
                    durationMs = o.optLong("durationMs"),
                    frameMs = o.optLong("frameMs"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Manifest unlesbar, Cache wird neu aufgebaut: ${e.message}")
            entries.clear()
        }
    }

    private fun saveManifest() {
        val json = JSONObject()
        for ((name, e) in entries) {
            json.put(
                name,
                JSONObject()
                    .put("etag", e.etag ?: "")
                    .put("lastModified", e.lastModified ?: "")
                    .put("size", e.size)
                    .put("durationMs", e.durationMs)
                    .put("frameMs", e.frameMs),
            )
        }
        val tmp = File(dir, "manifest.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(manifestFile)) {
            manifestFile.delete()
            tmp.renameTo(manifestFile)
        }
    }

    companion object {
        private const val TAG = "AvatarCache"
        private const val MAX_VARIANTS = 20
    }
}
