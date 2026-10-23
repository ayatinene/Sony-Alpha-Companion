package io.github.ayatinene.sonycompanion.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticFiles {
    fun name(mode: CameraMode?, now: Long = System.currentTimeMillis()): String =
        "AlphaCompanion-${mode?.name?.lowercase(Locale.ROOT) ?: "offline"}-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(Date(now))}.json"
}

/** Local-only last-crash record; included in the next user-requested diagnostic export. */
object CrashLog {
    fun install(directory: File) {
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            runCatching { record(directory,thread.name,error) }
            if(previous!=null) previous.uncaughtException(thread,error) else kotlin.system.exitProcess(1)
        }
    }
    fun record(directory: File, thread: String, error: Throwable) {
        val trace=StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(directory,"last-crash.txt").writeText("${java.time.OffsetDateTime.now()} thread=$thread\n${trace.take(100_000)}")
    }
    fun read(directory: File): String? = runCatching {
        File(directory,"last-crash.txt").takeIf(File::isFile)?.inputStream()?.bufferedReader()?.use { it.readText().take(110_000) }
    }.getOrNull()
}
