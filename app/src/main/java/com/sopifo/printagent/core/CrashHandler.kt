package com.sopifo.printagent.core

import android.content.Context
import android.util.Log
import com.sopifo.printagent.work.WorkScheduler
import java.time.Instant

/**
 * Last line of defence. Expected failures (printer, network, bad jobs) are handled where
 * they happen; anything that still escapes is logged and a delayed recovery job is scheduled
 * with WorkManager so the agent comes back on its own after the process restarts.
 */
class CrashHandler private constructor(
    private val appContext: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val line = """{"ts":"${Instant.now()}","level":"F","tag":"Crash","msg":"Uncaught exception on ${thread.name}","error":"${error.javaClass.name}: ${error.message?.replace("\"", "'")}"}"""
            Log.e("Sopifo/Crash", line, error)
            AppLog.flushBlocking(line)
            AppLog.flushBlocking(Log.getStackTraceString(error))
            WorkScheduler(appContext).scheduleCrashRecovery()
        } catch (_: Throwable) {
        }
        // Let the platform terminate the process; START_STICKY + WorkManager bring it back.
        previous?.uncaughtException(thread, error)
    }

    companion object {
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, current))
        }
    }
}
