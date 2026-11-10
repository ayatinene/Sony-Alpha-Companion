package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream

class DiagnosticLogTest {
    @Test fun minimumLevelFiltersAndExceptionsKeepTheirCauses()=runBlocking {
        val directory=Files.createTempDirectory("alpha-level").toFile()
        val logger=DiagnosticLog(directory)
        try {
            logger.minimum=LogLevel.WARN
            assertNull(logger.record(LogLevel.DEBUG,"HTTP","hidden"))
            logger.record(LogLevel.ERROR,"TRANSFER","broken","JPEG",IOException("outer",EOFException("short body")))
            val text=logger.snapshot().files.joinToString { it.bytes.toString(Charsets.UTF_8) }
            assertFalse(text.contains("hidden"));assertTrue(text.contains("ERROR [TRANSFER]"))
            assertTrue(text.contains("mode=JPEG"));assertTrue(text.contains("Caused by: java.io.EOFException: short body"))
        } finally { logger.close();directory.deleteRecursively() }
    }
    @Test fun rotationBoundsStorageAndKeepsNewestRecords()=runBlocking {
        val directory=Files.createTempDirectory("alpha-rotate").toFile();val logger=DiagnosticLog(directory,maxBytes=512)
        try {
            repeat(30) { logger.record(LogLevel.INFO,"TEST","record-$it "+"x".repeat(150)) }
            val result=logger.snapshot()
            assertEquals(3,result.files.size);assertTrue(result.files.all { it.bytes.size<=512 })
            val text=result.files.joinToString { it.bytes.toString(Charsets.UTF_8) }
            assertTrue(text.contains("record-29"));assertFalse(text.contains("record-0 "))
        } finally { logger.close();directory.deleteRecursively() }
    }
    @Test fun truncationPreservesUtf8AndTerminatesTheRecord()=runBlocking {
        val directory=Files.createTempDirectory("alpha-unicode").toFile();val logger=DiagnosticLog(directory,maxBytes=257)
        try {
            logger.record(LogLevel.WARN,"TEST","相机断开".repeat(200))
            val bytes=logger.snapshot().files.single().bytes
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            assertTrue(bytes.size<=257);assertTrue(text.endsWith("[truncated]\n"))
        } finally { logger.close();directory.deleteRecursively() }
    }
    @Test fun logsSurviveLoggerRestart()=runBlocking {
        val directory=Files.createTempDirectory("alpha-restart").toFile()
        val first=DiagnosticLog(directory);first.record(LogLevel.INFO,"APP","before restart");first.close()
        val second=DiagnosticLog(directory)
        try {
            second.record(LogLevel.INFO,"APP","after restart")
            val text=second.snapshot().files.joinToString { it.bytes.toString(Charsets.UTF_8) }
            assertTrue(text.contains("before restart"));assertTrue(text.contains("after restart"))
            assertNotEquals(first.session,second.session)
        } finally { second.close();directory.deleteRecursively() }
    }
    @Test fun concurrentWritersAreFlushedBeforeSnapshot()=runBlocking {
        val directory=Files.createTempDirectory("alpha-concurrent").toFile();val logger=DiagnosticLog(directory,capacity=512)
        try {
            coroutineScope { repeat(4) { worker -> launch(Dispatchers.Default) { repeat(50) { logger.record(LogLevel.DEBUG,"TEST","msg=$worker:$it;") } } } }
            val result=logger.snapshot();val text=result.files.joinToString { it.bytes.toString(Charsets.UTF_8) }
            assertEquals(0L,result.dropped);assertEquals(0L,result.writeFailures)
            repeat(4) { w->repeat(50) { assertEquals(1,Regex("msg=$w:$it;").findAll(text).count()) } }
        } finally { logger.close();directory.deleteRecursively() }
    }
    @Test fun diskFailureIsReportedWithoutKillingTheWriter()=runBlocking {
        val path=Files.createTempFile("alpha-not-directory",".tmp").toFile();val logger=DiagnosticLog(path)
        try {
            logger.record(LogLevel.ERROR,"APP","must not crash")
            val result=logger.snapshot();assertTrue(result.writeFailures>0);assertTrue(result.files.isEmpty())
        } finally { logger.close();path.delete() }
    }
    @Test fun bundleContainsReadableLogsSnapshotAndCrash() {
        val out=ByteArrayOutputStream()
        DiagnosticBundle.write(out,"{\"app\":\"test\"}",LogSnapshot(listOf(LogFile("app.log","INFO connected\n".toByteArray())),0,0),"fatal stack")
        val entries=linkedMapOf<String,String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip->while(true) { val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes().toString(Charsets.UTF_8) } }
        assertEquals(setOf("diagnostics.json","logs/app.log","last-crash.txt"),entries.keys)
        assertEquals("INFO connected\n",entries["logs/app.log"]);assertEquals("fatal stack",entries["last-crash.txt"])
    }
    @Test fun httpReportsStatusTimingAndFailureStack()=runBlocking {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setResponseCode(404))
            val reports=ConcurrentLinkedQueue<Triple<LogLevel,String,Throwable?>>()
            CameraHttp(report={level,text,error->reports.add(Triple(level,text,error))}).use { http->
                try { http.getText(server.url("/missing").toString());fail() } catch(_:IOException) { }
            }
            assertTrue(reports.any { it.first==LogLevel.DEBUG && it.second.contains("/missing") })
            assertTrue(reports.any { it.first==LogLevel.WARN && it.second.contains("total=") && it.third is IOException })
        }
    }
}
