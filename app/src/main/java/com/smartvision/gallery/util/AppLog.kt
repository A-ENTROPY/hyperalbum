package com.smartvision.gallery.util

import android.content.Context
import android.util.Log
import com.smartvision.gallery.BuildConfig
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Dual-sink logging: Timber (Android Logcat) + append-only file for on-device diagnostics.
 * File mode: append-only (no ring buffer) for simplicity and reliability.
 */
object AppLog {

    @Volatile
    private var appContext: Context? = null

    private val logFile: File? by lazy {
        val ctx = appContext ?: return@lazy null
        try {
            val dir = File(ctx.filesDir, "logs")
            dir.mkdirs()
            val f = File(dir, "applog.txt")
            // Delete old file on each boot
            f.delete()
            f
        } catch (t: Throwable) {
            Log.w("AppLog", "logFile unavailable", t)
            null
        }
    }

    private val writer: BufferedWriter? by lazy {
        logFile?.let { f ->
            try {
                BufferedWriter(FileWriter(f, true), 8192)
            } catch (t: Throwable) {
                Log.w("AppLog", "BufferedWriter unavailable", t)
                null
            }
        }
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.DEBUG) {
            Timber.plant(LogcatTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        Log.i("AppLog", "install: file=${logFile?.absolutePath}")
    }

    fun d(tag: String, msg: String) = Timber.tag(tag).d(msg)
    fun i(tag: String, msg: String) = Timber.tag(tag).i(msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = Timber.tag(tag).w(t, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = Timber.tag(tag).e(t, msg)

    private class LogcatTree : Timber.DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            super.log(priority, tag, message, t)
            appendToFile(priority, tag, message, t)
        }
    }

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, msg: String, t: Throwable?) {
            if (priority < Log.WARN) return
            appendToFile(priority, tag, msg, t)
        }
    }

    @Synchronized
    private fun appendToFile(priority: Int, tag: String?, msg: String, t: Throwable?) {
        val w = writer ?: return
        try {
            val priorityChar = when (priority) {
                Log.VERBOSE -> 'V'
                Log.DEBUG -> 'D'
                Log.INFO -> 'I'
                Log.WARN -> 'W'
                Log.ERROR -> 'E'
                Log.ASSERT -> 'A'
                else -> '?'
            }
            val timestamp = System.currentTimeMillis()
            w.write("[$timestamp] $priorityChar ${tag ?: "?"}: $msg")
            if (t != null) {
                w.write(" | ${t.message ?: t.javaClass.name}")
            }
            w.newLine()
            w.flush()
        } catch (_: Throwable) { /* silent */ }
    }
}
