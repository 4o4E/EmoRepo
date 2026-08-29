package top.e404.emorepo.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.e404.emorepo.protocol.index.IndexJsonlCodec
import top.e404.emorepo.protocol.pack.RootIndexJsonlCodec

class LegacyOrderMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pack migration preserves legacy visible order and removes order field`() {
        val file = temporaryFolder.newFile("index.jsonl")
        file.writeText(
            """{"name":"b.png","md5":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","ext":"png","time":2,"order":2048}
{"name":"a.png","md5":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ext":"png","time":1,"order":1024}
""",
        )

        LegacyOrderMigration.migratePack(file)

        assertEquals(listOf("a.png", "b.png"), IndexJsonlCodec.decode(file.readText()).map { it.name })
        assertFalse(file.readText().contains("\"order\""))
    }

    @Test
    fun `root migration preserves legacy visible order and removes order field`() {
        val file = temporaryFolder.newFile("index.jsonl")
        file.writeText("""{"name":"b","order":2048}
{"name":"a","order":1024}
""")

        LegacyOrderMigration.migrateRoot(file)

        assertEquals(listOf("a", "b"), RootIndexJsonlCodec.decode(file.readText()).map { it.name })
        assertFalse(file.readText().contains("\"order\""))
    }
}
