package io.github.ayatinene.sonycompanion.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.OutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }
data class LogFile(val name: String, val bytes: ByteArray)
data class LogSnapshot(val files: List<LogFile>, val dropped: Long, val writeFailures: Long)

/** Bounded, persistent logs. Disk writes and rotation are owned by one IO coroutine. */
class DiagnosticLog(private val directory: File, private val maxBytes: Long = 512*1024,
    private val backups: Int = 2, capacity: Int = 256) {
    init { require(maxBytes in 128..Int.MAX_VALUE.toLong());require(backups>=0 && capacity>0) }
    @Volatile var minimum = LogLevel.DEBUG
    val session = UUID.randomUUID().toString().take(8)
    private val dropped = AtomicLong()
    private val pendingDropped = AtomicLong()
    private var writeFailures = 0L
    private sealed interface Command {
        data class Line(val text: String): Command
        data class Snapshot(val reply: CompletableDeferred<LogSnapshot>): Command
    }
    private val commands=Channel<Command>(capacity)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val writer=scope.launch {
        for(command in commands) {
            val missing=pendingDropped.getAndSet(0)
            if(missing>0) runCatching { append("${OffsetDateTime.now()} WARN [LOGGER] session=$session dropped=$missing records (queue full)\n") }.onFailure { writeFailures++ }
            when(command) {
                is Command.Line -> runCatching { append(command.text) }.onFailure { writeFailures++ }
                is Command.Snapshot -> {
                    val files=(backups downTo 0).mapNotNull { index ->
                        val file=file(index)
                        if(!file.isFile) null else runCatching { LogFile(file.name,file.readBytes()) }.getOrElse { writeFailures++;null }
                    }
                    command.reply.complete(LogSnapshot(files,dropped.get(),writeFailures))
                }
            }
        }
    }
    fun record(level: LogLevel, tag: String, message: String, mode: String = "OFFLINE", error: Throwable? = null): String? {
        if(level<minimum) return null
        val prefix="${OffsetDateTime.now()} ${level.name.padEnd(5)} [$tag] [${Thread.currentThread().name}] session=$session mode=$mode "
        val details=error?.stackTraceToString()?.take(24000)?.let { "\n"+it.lineSequence().joinToString("\n") { line->"    $line" } }.orEmpty()
        val line=prefix+message.take(24000).replace('\r',' ').replace("\n","\n    ")+details+"\n"
        if(commands.trySend(Command.Line(line)).isFailure) { dropped.incrementAndGet();pendingDropped.incrementAndGet() }
        return line.trimEnd()
    }
    private fun file(index: Int)=File(directory,if(index==0)"app.log" else "app.$index.log")
    private fun append(text: String) {
        directory.mkdirs()
        // A single unusually large record must not exceed a whole log segment.
        val bytes=text.toByteArray(Charsets.UTF_8).let {
            if(it.size<=maxBytes) it else {
                val suffix=" [truncated]\n".toByteArray()
                var end=maxBytes.toInt()-suffix.size
                while(end>0 && (it[end].toInt() and 0xc0)==0x80) end--
                it.copyOf(end)+suffix
            }
        }
        if(file(0).length()+bytes.size>maxBytes) {
            if(backups==0) file(0).delete()
            else {
                file(backups).delete()
                for(index in backups-1 downTo 0) if(file(index).exists()) {
                    check(file(index).renameTo(file(index+1))) { "日志轮转失败" }
                }
            }
        }
        file(0).appendBytes(bytes)
    }
    suspend fun snapshot(): LogSnapshot = withTimeout(5000) {
        val reply=CompletableDeferred<LogSnapshot>()
        commands.send(Command.Snapshot(reply));reply.await()
    }
    suspend fun close() { commands.close();writer.join();scope.cancel() }
}

object DiagnosticBundle {
    fun write(output: OutputStream, json: String, logs: LogSnapshot, crash: String?) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() }
            entry("diagnostics.json",json.toByteArray(Charsets.UTF_8))
            if(logs.files.isEmpty()) entry("logs/app.log","No retained log records. See diagnostics.json logging statistics.\n".toByteArray())
            logs.files.forEach { entry("logs/${it.name}",it.bytes) }
            if(crash!=null) entry("last-crash.txt",crash.toByteArray(Charsets.UTF_8))
        }
    }
}
