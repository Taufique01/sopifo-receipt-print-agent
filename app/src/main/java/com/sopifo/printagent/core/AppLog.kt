package com.sopifo.printagent.core

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Structured logger. Every entry is one JSON line (`ts`, `level`, `tag`, `msg`, extra fields)
 * written to logcat and to a small rotating file in app storage, so field issues can be
 * diagnosed months later. Logging itself must never throw.
 */
object AppLog {
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val RECENT_LINES = 200

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "applog").apply { isDaemon = true } }
    private val recent = ArrayDeque<String>(RECENT_LINES)
    @Volatile private var logFile: File? = null

    fun init(filesDir: File) {
        try {
            val dir = File(filesDir, "logs").apply { mkdirs() }
            logFile = File(dir, "agent.log")
        } catch (t: Throwable) {
            Log.e("AppLog", "Log file init failed", t)
        }
    }

    fun d(tag: String, msg: String, vararg fields: Pair<String, Any?>) = log("D", tag, msg, null, fields)
    fun i(tag: String, msg: String, vararg fields: Pair<String, Any?>) = log("I", tag, msg, null, fields)
    fun w(tag: String, msg: String, t: Throwable? = null, vararg fields: Pair<String, Any?>) = log("W", tag, msg, t, fields)
    fun e(tag: String, msg: String, t: Throwable? = null, vararg fields: Pair<String, Any?>) = log("E", tag, msg, t, fields)

    fun recentLines(): List<String> = synchronized(recent) { recent.toList() }

    fun logFiles(): List<File> {
        val f = logFile ?: return emptyList()
        return listOf(File(f.path + ".1"), f).filter { it.exists() }
    }

    private fun log(level: String, tag: String, msg: String, t: Throwable?, fields: Array<out Pair<String, Any?>>) {
        try {
            val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "ts" to JsonPrimitive(Instant.now().toString()),
                "level" to JsonPrimitive(level),
                "tag" to JsonPrimitive(tag),
                "msg" to JsonPrimitive(msg),
            )
            for ((k, v) in fields) map[k] = JsonPrimitive(v?.toString())
            if (t != null) {
                map["error"] = JsonPrimitive("${t.javaClass.simpleName}: ${t.message}")
            }
            val line = JsonObject(map).toString()
            when (level) {
                "E" -> Log.e("Sopifo/$tag", line, t)
                "W" -> Log.w("Sopifo/$tag", line, t)
                "I" -> Log.i("Sopifo/$tag", line)
                else -> Log.d("Sopifo/$tag", line)
            }
            synchronized(recent) {
                if (recent.size >= RECENT_LINES) recent.removeFirst()
                recent.addLast(line)
            }
            val stack = if (t != null && level == "E") Log.getStackTraceString(t) else null
            writer.execute { appendToFile(line, stack) }
        } catch (_: Throwable) {
            // Logging must never take the app down.
        }
    }

    private fun appendToFile(line: String, stack: String?) {
        val f = logFile ?: return
        try {
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val old = File(f.path + ".1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(if (stack.isNullOrEmpty()) "$line\n" else "$line\n$stack\n")
        } catch (_: Throwable) {
            // Storage full or unavailable: keep running with logcat only.
        }
    }

    /** Synchronous write used by the crash handler, where the process is about to die. */
    fun flushBlocking(line: String) {
        try {
            logFile?.appendText("$line\n")
        } catch (_: Throwable) {
        }
    }
}
