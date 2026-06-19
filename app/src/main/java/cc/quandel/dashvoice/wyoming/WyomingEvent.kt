package cc.quandel.dashvoice.wyoming

import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * One Wyoming protocol event.
 *
 * Wire format (see github.com/rhasspy/wyoming):
 *   1. a single-line JSON header + "\n"   ->  {"type": "...", "data_length": N, "payload_length": M}
 *   2. N bytes of UTF-8 JSON  (the "data" object)   [if data_length present]
 *   3. M bytes of binary payload                     [if payload_length present]
 */
data class WyomingEvent(
    val type: String,
    val data: JSONObject? = null,
    val payload: ByteArray? = null
) {
    fun writeTo(out: OutputStream) {
        val header = JSONObject().put("type", type)
        val dataBytes: ByteArray? = data?.toString()?.toByteArray(Charsets.UTF_8)
        if (dataBytes != null) header.put("data_length", dataBytes.size)
        if (payload != null) header.put("payload_length", payload.size)
        synchronized(out) {
            out.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
            if (dataBytes != null) out.write(dataBytes)
            if (payload != null) out.write(payload)
            out.flush()
        }
    }

    // data class with ByteArray: identity-based equality is fine for our use.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** Reads a single event from the stream; returns null on EOF. */
        fun readFrom(input: DataInputStream): WyomingEvent? {
            var line = readHeaderLine(input) ?: return null
            while (line.isBlank()) {
                line = readHeaderLine(input) ?: return null
            }
            val header = JSONObject(line)
            val type = header.getString("type")

            val dataLen = header.optInt("data_length", 0)
            val payloadLen = header.optInt("payload_length", 0)

            var data: JSONObject? = null
            if (dataLen > 0) {
                val buf = ByteArray(dataLen)
                input.readFully(buf)
                data = JSONObject(String(buf, Charsets.UTF_8))
            }
            var payload: ByteArray? = null
            if (payloadLen > 0) {
                payload = ByteArray(payloadLen)
                input.readFully(payload)
            }
            return WyomingEvent(type, data, payload)
        }

        /** Header line is ASCII JSON terminated by '\n'. */
        private fun readHeaderLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
            }
        }
    }
}
