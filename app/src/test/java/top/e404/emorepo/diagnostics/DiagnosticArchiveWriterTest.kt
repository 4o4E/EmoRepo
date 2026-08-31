package top.e404.emorepo.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticArchiveWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `archive contains only diagnostics and selected log files`() {
        val logs = temporaryFolder.newFolder("logs")
        val current = logs.resolve("emorepo.log").apply { writeText("{}\n") }
        val history = logs.resolve("emorepo.log.1").apply { writeText("{}\n") }
        logs.resolve("private.txt").writeText("must not export")
        val output = ByteArrayOutputStream()

        DiagnosticArchiveWriter.write(output, "{\"schemaVersion\":1}", listOf(current, history))

        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                entries += zip.nextEntry?.name ?: break
            }
        }
        assertEquals(listOf("diagnostics.json", "logs/emorepo.log", "logs/emorepo.log.1"), entries)
    }
}
