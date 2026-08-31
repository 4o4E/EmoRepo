package top.e404.emorepo.diagnostics

import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `rotates files and every retained line remains valid json`() {
        val store = DiagnosticLogStore(temporaryFolder.newFolder("logs"), maximumFileBytes = 320, historyCount = 2)

        repeat(8) { index -> store.append(event(index)) }

        val files = store.logFiles()
        assertEquals(3, files.size)
        files.flatMap(File::readLines).forEach { line -> assertTrue(JsonParser.parseString(line).isJsonObject) }
    }

    @Test
    fun `serializes concurrent writes without broken json lines`() {
        val store = DiagnosticLogStore(
            temporaryFolder.newFolder("concurrent"),
            maximumFileBytes = 4L * 1024L * 1024L,
        )
        val executor = Executors.newFixedThreadPool(8)
        repeat(400) { index -> executor.execute { store.append(event(index)) } }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        val lines = store.logFiles().flatMap(File::readLines)

        assertEquals(400, lines.size)
        lines.forEach { line -> assertTrue(JsonParser.parseString(line).isJsonObject) }
    }

    private fun event(index: Int) = DiagnosticLogEvent(
        timestamp = "2026-09-01T00:00:00Z",
        level = "INFO",
        process = "test",
        thread = "test-thread",
        component = "test",
        event = "event-$index",
        message = "diagnostic message $index",
    )
}
