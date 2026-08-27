package top.e404.emorepo.repository

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.pack.PackOrderRecord
import top.e404.emorepo.protocol.pack.RootIndexJsonlCodec

class EmoticonRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingRootIndexUsesDirectoryOrderWithoutWriting() {
        createLegacyPack("zeta")
        createLegacyPack("alpha")
        val repository = repository()

        val packs = repository.listPacks()

        assertEquals(listOf("alpha", "zeta"), packs.map { it.name })
        assertEquals(listOf(1_024L, 2_048L), packs.map { it.order })
        assertFalse(File(temporaryFolder.root, "repository/index.jsonl").exists())
    }

    @Test
    fun rootIndexControlsPackOrderAndMustMatchDirectories() {
        createLegacyPack("alpha")
        createLegacyPack("zeta")
        val rootIndex = File(temporaryFolder.root, "repository/index.jsonl")
        rootIndex.writeText(
            RootIndexJsonlCodec.encode(
                listOf(PackOrderRecord("zeta", 1_024), PackOrderRecord("alpha", 2_048)),
            ),
        )
        val repository = repository()

        assertEquals(listOf("zeta", "alpha"), repository.listPacks().map { it.name })

        rootIndex.writeText(RootIndexJsonlCodec.encode(listOf(PackOrderRecord("alpha", 1_024))))
        assertThrows(ProtocolException::class.java) { repository.listPacks() }
    }

    @Test
    fun initializeAndReorderPacksWriteCanonicalRootIndex() {
        createLegacyPack("zeta")
        createLegacyPack("alpha")
        val repository = repository()

        repository.initializePackOrder()
        val reordered = repository.reorderPacks(listOf("zeta", "alpha"))

        assertEquals(listOf("zeta", "alpha"), reordered.map { it.name })
        assertEquals(
            listOf(PackOrderRecord("zeta", 1_024), PackOrderRecord("alpha", 2_048)),
            RootIndexJsonlCodec.decode(
                File(temporaryFolder.root, "repository/index.jsonl").readText(),
            ),
        )
    }

    @Test
    fun createPackAndImportUseContentAddressedName() {
        val repository = repository()
        repository.createPack("cats")
        val bytes = png(1)

        val result = repository.import("cats", listOf(ImportCandidate("source.bin", bytes)))
        val record = result.items.single().record!!

        assertEquals(ManagementStatus.SUCCESS, result.items.single().status)
        assertEquals("${md5(bytes)}.png", record.name)
        assertEquals(1000, record.time)
        assertEquals(1024, record.order)
        assertArrayEquals(bytes, File(temporaryFolder.root, "repository/cats/${record.name}").readBytes())
        assertEquals(listOf(record), repository.getPack("cats").records)
    }

    @Test
    fun duplicateImportDoesNotChangeExistingMetadata() {
        val repository = repository()
        repository.createPack("cats")
        val candidate = ImportCandidate("cat.png", png(2))
        val first = repository.import("cats", listOf(candidate)).items.single().record!!

        val duplicate = repository.import("cats", listOf(candidate)).items.single()

        assertEquals(ManagementStatus.DUPLICATE, duplicate.status)
        assertEquals(first, duplicate.record)
        assertEquals(listOf(first), repository.getPack("cats").records)
    }

    @Test
    fun duplicateMd5WithDifferentStoredBytesIsRejected() {
        val repository = repository()
        repository.createPack("cats")
        val candidate = ImportCandidate("cat.png", png(21))
        val record = repository.import("cats", listOf(candidate)).items.single().record!!
        File(temporaryFolder.root, "repository/cats/${record.name}").writeBytes(png(22))

        val result = repository.import("cats", listOf(candidate)).items.single()

        assertEquals(ManagementStatus.FAILED, result.status)
        assertTrue(result.message.orEmpty().contains("different image bytes"))
    }

    @Test
    fun readingPackRestoresInterruptedIndexReplacement() {
        val repository = repository()
        repository.createPack("cats")
        val index = File(temporaryFolder.root, "repository/cats/index.jsonl")
        val backup = File(index.parentFile, ".index.jsonl.emorepo-backup")
        assertTrue(index.renameTo(backup))

        val pack = repository.getPack("cats")

        assertTrue(pack.records.isEmpty())
        assertTrue(index.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun batchImportKeepsSuccessfulItemsWhenAnotherItemFails() {
        val repository = repository()
        repository.createPack("cats")

        val result = repository.import(
            "cats",
            listOf(
                ImportCandidate("cat.png", png(3)),
                ImportCandidate("text.txt", "not an image".toByteArray()),
            ),
        )

        assertEquals(1, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(1, repository.getPack("cats").records.size)
    }

    @Test
    fun deleteRemovesImageAndExplicitIcon() {
        val repository = repository()
        repository.createPack("cats")
        val record = repository.import("cats", listOf(ImportCandidate("cat.png", png(4))))
            .items.single().record!!
        repository.setIcon("cats", record.md5)

        val result = repository.delete("cats", listOf(record.md5))

        assertEquals(1, result.succeeded)
        assertTrue(repository.getPack("cats").records.isEmpty())
        assertFalse(File(temporaryFolder.root, "repository/cats/${record.name}").exists())
    }

    @Test
    fun movePreservesIdentityAndTimeButAllocatesTargetOrderAndClearsIcon() {
        val repository = repository()
        repository.createPack("source")
        repository.createPack("target")
        repository.import("target", listOf(ImportCandidate("existing.png", png(5))))
        val source = repository.import("source", listOf(ImportCandidate("moved.png", png(6))))
            .items.single().record!!
        repository.setIcon("source", source.md5)

        val result = repository.move("source", "target", listOf(source.md5))
        val moved = result.items.single().record!!

        assertEquals(ManagementStatus.SUCCESS, result.items.single().status)
        assertFalse(result.items.single().deduplicated)
        assertEquals(source.md5, moved.md5)
        assertEquals(source.time, moved.time)
        assertEquals(2048, moved.order)
        assertFalse(moved.icon)
        assertTrue(repository.getPack("source").records.isEmpty())
        assertEquals(2, repository.getPack("target").records.size)
    }

    @Test
    fun moveDeduplicatesAndPreservesTargetRecord() {
        val repository = repository()
        repository.createPack("source")
        repository.createPack("target")
        val bytes = png(7)
        val source = repository.import("source", listOf(ImportCandidate("source.png", bytes)))
            .items.single().record!!
        val target = repository.import("target", listOf(ImportCandidate("target.png", bytes)))
            .items.single().record!!
        repository.setIcon("target", target.md5)
        val expectedTarget = repository.getPack("target").records.single()

        val result = repository.move("source", "target", listOf(source.md5)).items.single()

        assertEquals(ManagementStatus.SUCCESS, result.status)
        assertTrue(result.deduplicated)
        assertEquals(expectedTarget, result.record)
        assertTrue(repository.getPack("source").records.isEmpty())
        assertEquals(listOf(expectedTarget), repository.getPack("target").records)
        assertFalse(File(temporaryFolder.root, "repository/source/${source.name}").exists())
    }

    @Test
    fun reorderWritesCanonicalOrder() {
        val repository = repository()
        repository.createPack("cats")
        val first = repository.import("cats", listOf(ImportCandidate("a.png", png(8))))
            .items.single().record!!
        val second = repository.import("cats", listOf(ImportCandidate("b.png", png(9))))
            .items.single().record!!

        val reordered = repository.reorder("cats", listOf(second.md5, first.md5)).records

        assertEquals(listOf(second.md5, first.md5), reordered.map { it.md5 })
        assertEquals(listOf(1024L, 2048L), reordered.map { it.order })
    }

    private fun repository(): EmoticonRepository = EmoticonRepository(
        File(temporaryFolder.root, "repository"),
        currentTimeMillis = { 1000L },
    )

    private fun createLegacyPack(name: String) {
        val directory = File(temporaryFolder.root, "repository/$name")
        assertTrue(directory.mkdirs())
        File(directory, "index.jsonl").writeText("")
    }

    private fun png(marker: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, marker.toByte(),
    )

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
