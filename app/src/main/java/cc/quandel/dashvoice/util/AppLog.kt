package cc.quandel.dashvoice.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight in-app logger: mirrors to logcat AND keeps a ring buffer that the on-screen
 * log overlay (MainActivity) renders — so the app can be debugged on the tablet without ADB.
 *
 * Components import this as `Log` (alias) so existing Log.i/w/e/d call sites flow through here.
 */
object AppLog {
    private const val MAX = 300
    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var logFile: File? = null

    /** Set by the UI to be notified of new lines (invoked on the main thread). */
    @Volatile var listener: (() -> Unit)? = null

    fun initFileLog(dir: File) {
        val f = File(dir, "dashvoice_debug.log")
        f.writeText("--- Log start ${fmt.format(Date())} ---\n")
        logFile = f
    }

    fun i(tag: String, msg: String): Int { Log.i(tag, msg); return add("I", tag, msg) }
    fun w(tag: String, msg: String): Int { Log.w(tag, msg); return add("W", tag, msg) }
    fun e(tag: String, msg: String): Int { Log.e(tag, msg); return add("E", tag, msg) }
    fun d(tag: String, msg: String): Int { Log.d(tag, msg); return add("D", tag, msg) }

    fun w(tag: String, msg: String, tr: Throwable): Int {
        Log.w(tag, msg, tr); return add("W", tag, "$msg : ${tr.message}")
    }

    fun e(tag: String, msg: String, tr: Throwable): Int {
        Log.e(tag, msg, tr); return add("E", tag, "$msg : ${tr.message}")
    }

    private fun add(level: String, tag: String, msg: String): Int {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX) buffer.removeFirst()
        }
        try { logFile?.appendText(line + "\n") } catch (_: Exception) {}
        main.post { listener?.invoke() }
        return 0
    }

    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }
}
