package top.e404.emorepo.git

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.protocol.index.IndexJsonlCodec
import top.e404.emorepo.protocol.pack.PackIndexRecord
import top.e404.emorepo.protocol.pack.RootIndexJsonlCodec
import top.e404.emorepo.protocol.recent.RecentCsvCodec
import top.e404.emorepo.protocol.recent.RecentUsageRecord

class ProtocolConflictResolverTest {
    @Test
    fun `root index merges additions and uses local order`() {
        val base = listOf(PackIndexRecord("base"))
        val local = listOf(
            PackIndexRecord("local"),
            PackIndexRecord("base"),
        )
        val remote = listOf(
            PackIndexRecord("base"),
            PackIndexRecord("remote"),
        )

        val result = ProtocolConflictResolver.resolve(
            "index.jsonl",
            RootIndexJsonlCodec.encode(base).bytes(),
            RootIndexJsonlCodec.encode(local).bytes(),
            RootIndexJsonlCodec.encode(remote).bytes(),
        )
        val merged = RootIndexJsonlCodec.decode(result.text())

        assertEquals(listOf("local", "base", "remote"), merged.map { it.name })
    }

    @Test
    fun `merges different additions with local line order first`() {
        val local = listOf(record("a", 10))
        val remote = listOf(record("b", 20))

        val merged = resolveIndex(emptyList(), local, remote)

        assertEquals(listOf("a", "b"), merged.map { it.md5.first().toString() })
    }

    @Test
    fun `keeps modification when the other side deletes`() {
        val base = listOf(record("a", 10))
        val remote = listOf(record("a", 30))

        val resolution = ProtocolConflictResolver.resolve(
            path = "pack/index.jsonl",
            base = encodeIndex(base),
            local = null,
            remote = encodeIndex(remote),
        )

        assertEquals(30L, IndexJsonlCodec.decode(resolution.text()).single().time)
        assertEquals(1, resolution.warnings.size)
    }

    @Test
    fun `uses local order and larger time for concurrent changes`() {
        val base = listOf(record("a", 10), record("b", 10))
        val local = listOf(record("b", 10), record("a", 10))
        val remote = listOf(record("a", 40), record("b", 30))

        val merged = resolveIndex(base, local, remote)

        assertEquals(listOf("b", "a"), merged.map { it.md5.first().toString() })
        assertEquals(listOf(30L, 40L), merged.map { it.time })
    }

    @Test
    fun `merges recent records from both sides using newest time`() {
        val local = RecentCsvCodec.encode(listOf(RecentUsageRecord("pack", "a.png", 10)))
        val remote = RecentCsvCodec.encode(
            listOf(
                RecentUsageRecord("pack", "a.png", 20),
                RecentUsageRecord("pack", "b.png", 15),
            ),
        )
        val resolution = ProtocolConflictResolver.resolve(
            "recent/device.csv",
            RecentCsvCodec.encode(emptyList()).bytes(),
            local.bytes(),
            remote.bytes(),
        )

        val merged = RecentCsvCodec.decode(resolution.text())
        assertEquals(listOf(20L, 15L), merged.map { it.time })
    }

    @Test
    fun `rejects different bytes for the same image path`() {
        assertThrows(ProtocolException::class.java) {
            ProtocolConflictResolver.resolve(
                "pack/hash.png",
                byteArrayOf(1),
                byteArrayOf(2),
                byteArrayOf(3),
            )
        }
    }

    private fun resolveIndex(
        base: List<EmoticonRecord>,
        local: List<EmoticonRecord>,
        remote: List<EmoticonRecord>,
    ): List<EmoticonRecord> {
        val result = ProtocolConflictResolver.resolve(
            "pack/index.jsonl",
            encodeIndex(base),
            encodeIndex(local),
            encodeIndex(remote),
        )
        assertTrue(result.warnings.isEmpty())
        return IndexJsonlCodec.decode(result.text())
    }

    private fun record(id: String, time: Long) = EmoticonRecord(
        name = id.repeat(32) + ".png",
        md5 = id.repeat(32),
        ext = "png",
        time = time,
    )

    private fun encodeIndex(records: List<EmoticonRecord>): ByteArray =
        IndexJsonlCodec.encode(records).bytes()

    private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private fun ConflictResolution.text(): String =
        String(requireNotNull(content), StandardCharsets.UTF_8)
}
