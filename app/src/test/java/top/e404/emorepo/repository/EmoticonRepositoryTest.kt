package top.e404.emorepo.repository

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.index.IndexJsonlCodec
import top.e404.emorepo.protocol.pack.PackIndexRecord
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
        assertFalse(File(temporaryFolder.root, "repository/index.jsonl").exists())
    }

    @Test
    fun rootIndexControlsPackOrderAndMustMatchDirectories() {
        createLegacyPack("alpha")
        createLegacyPack("zeta")
        val rootIndex = File(temporaryFolder.root, "repository/index.jsonl")
        rootIndex.writeText(
            RootIndexJsonlCodec.encode(
                listOf(PackIndexRecord("zeta"), PackIndexRecord("alpha")),
            ),
        )
        val repository = repository()

        assertEquals(listOf("zeta", "alpha"), repository.listPacks().map { it.name })

        rootIndex.writeText(RootIndexJsonlCodec.encode(listOf(PackIndexRecord("alpha"))))
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
            listOf(PackIndexRecord("zeta"), PackIndexRecord("alpha")),
            RootIndexJsonlCodec.decode(
                File(temporaryFolder.root, "repository/index.jsonl").readText(),
            ),
        )
    }

    @Test
    fun arrangementPersistsUserCollapsedChoiceAndReorderPreservesIt() {
        createLegacyPack("alpha")
        createLegacyPack("zeta")
        val repository = repository()
        repository.initializePackOrder()

        repository.updatePackArrangement(
            listOf(PackIndexRecord("alpha", collapsed = true), PackIndexRecord("zeta")),
        )
        val reordered = repository.reorderPacks(listOf("zeta", "alpha"))
        val updatedCollapsedPack = repository.setIcon("alpha", null)

        assertEquals(listOf("zeta", "alpha"), reordered.map { it.name })
        assertFalse(reordered.first().collapsed)
        assertTrue(reordered.last().collapsed)
        assertTrue(updatedCollapsedPack.collapsed)
        assertEquals(
            listOf(PackIndexRecord("zeta"), PackIndexRecord("alpha", collapsed = true)),
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
    fun batchImportPrependsNewRecordsInCandidateOrderWithoutChangingExistingRecord() {
        val repository = repository()
        repository.createPack("cats")
        val existing = repository.import("cats", listOf(ImportCandidate("old.png", png(31))))
            .items.single().record!!
        val indexFile = File(temporaryFolder.root, "repository/cats/index.jsonl")
        val existingLine = indexFile.readLines().single()
        val firstBytes = png(32)
        val secondBytes = png(33)

        repository.import(
            "cats",
            listOf(
                ImportCandidate("first.png", firstBytes),
                ImportCandidate("second.png", secondBytes),
            ),
        )

        val records = repository.getPack("cats").records
        assertEquals(listOf(md5(firstBytes), md5(secondBytes), existing.md5), records.map { it.md5 })
        assertEquals(existing, records.last())
        assertEquals(existingLine, indexFile.readLines().last())
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
    fun movePreservesIdentityAndTimeAndPrependsTargetRecord() {
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
        assertFalse(moved.icon)
        assertTrue(repository.getPack("source").records.isEmpty())
        assertEquals(moved.md5, repository.getPack("target").records.first().md5)
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
    fun reorderWritesJsonlLineOrderWithoutChangingRecordFields() {
        val repository = repository()
        repository.createPack("cats")
        val first = repository.import("cats", listOf(ImportCandidate("a.png", png(8))))
            .items.single().record!!
        val second = repository.import("cats", listOf(ImportCandidate("b.png", png(9))))
            .items.single().record!!

        val reordered = repository.reorder("cats", listOf(first.md5, second.md5)).records

        assertEquals(listOf(first.md5, second.md5), reordered.map { it.md5 })
        assertEquals(setOf(first, second), reordered.toSet())
    }

    @Test
    fun renamePackPreservesContentCollapsedStateAndAllRecentFiles() {
        val repository = repository()
        repository.createPack("old")
        val record = repository.import("old", listOf(ImportCandidate("a.png", png(41))))
            .items.single().record!!
        repository.updatePackArrangement(listOf(PackIndexRecord("old", collapsed = true)))
        RecentUsageRepository(File(temporaryFolder.root, "repository"), "phone").recordUse("old", record.name, 20)
        RecentUsageRepository(File(temporaryFolder.root, "repository"), "tablet").recordUse("old", record.name, 10)

        val renamed = repository.renamePack("old", "新名字")

        assertEquals("新名字", renamed.name)
        assertTrue(renamed.collapsed)
        assertTrue(File(temporaryFolder.root, "repository/新名字/${record.name}").isFile)
        assertFalse(File(temporaryFolder.root, "repository/old").exists())
        assertEquals(listOf(PackIndexRecord("新名字", collapsed = true)), rootRecords())
        assertEquals(
            setOf("新名字"),
            listOf("phone", "tablet").flatMap { device ->
                RecentUsageRepository(File(temporaryFolder.root, "repository"), device)
                    .readCurrentDevice().map { it.packageName }
            }.toSet(),
        )
    }

    @Test
    fun deletePackRemovesDirectoryRootRecordAndAllRecentReferences() {
        val repository = repository()
        repository.createPack("delete-me")
        val record = repository.import("delete-me", listOf(ImportCandidate("a.png", png(42))))
            .items.single().record!!
        RecentUsageRepository(File(temporaryFolder.root, "repository"), "phone")
            .recordUse("delete-me", record.name, 20)

        val deleted = repository.deletePack("delete-me")

        assertEquals("delete-me", deleted.name)
        assertFalse(File(temporaryFolder.root, "repository/delete-me").exists())
        assertTrue(rootRecords().isEmpty())
        assertTrue(
            RecentUsageRepository(File(temporaryFolder.root, "repository"), "phone")
                .readCurrentDevice().isEmpty(),
        )
    }

    @Test
    fun applyPackEditDeletesDraftItemsAndMovesSelectionToFrontOnce() {
        val repository = repository()
        repository.createPack("cats")
        repository.import(
            "cats",
            listOf(
                ImportCandidate("a.png", png(43)),
                ImportCandidate("b.png", png(44)),
                ImportCandidate("c.png", png(45)),
            ),
        )
        repository.updatePackArrangement(listOf(PackIndexRecord("cats", collapsed = true)))
        val original = repository.getPack("cats").records
        RecentUsageRepository(File(temporaryFolder.root, "repository"), "phone")
            .recordUse("cats", original[1].name, 20)
        val finalOrder = listOf(original[2].md5, original[0].md5)

        val edited = repository.applyPackEdit(
            packName = "cats",
            originalMd5Order = original.map { it.md5 },
            finalMd5Order = finalOrder,
            recentDeviceId = "phone",
            recentMaximumRecords = 30,
        )

        assertEquals(finalOrder, edited.records.map { it.md5 })
        assertTrue(edited.collapsed)
        assertFalse(File(temporaryFolder.root, "repository/cats/${original[1].name}").exists())
        assertTrue(
            RecentUsageRepository(File(temporaryFolder.root, "repository"), "phone")
                .readCurrentDevice().isEmpty(),
        )
    }

    @Test
    fun interruptedDeleteTransactionRollsBackOnRepositoryOpen() {
        val repository = repository()
        repository.createPack("cats")
        val root = File(temporaryFolder.root, "repository")
        val rootIndex = File(root, "index.jsonl")
        val transaction = File(root, ".emorepo-pack-transaction")
        assertTrue(transaction.mkdir())
        rootIndex.copyTo(File(transaction, "root-index.backup"))
        assertTrue(File(root, "cats").renameTo(File(transaction, "pack")))
        rootIndex.writeText("")
        File(transaction, "manifest.properties").outputStream().use { output ->
            Properties().apply {
                setProperty("operation", "DELETE")
                setProperty("source", "cats")
                setProperty("rootExists", "true")
            }.store(output, null)
        }

        val recovered = repository()

        assertEquals(listOf("cats"), recovered.listPacks().map { it.name })
        assertTrue(File(root, "cats/index.jsonl").isFile)
        assertFalse(transaction.exists())
    }

    @Test
    fun interruptedRenameTransactionRestoresDirectoryIndexAndRecentFiles() {
        val repository = repository()
        repository.createPack("old")
        val record = repository.import("old", listOf(ImportCandidate("a.png", png(46))))
            .items.single().record!!
        repository.updatePackArrangement(listOf(PackIndexRecord("old", collapsed = true)))
        val root = File(temporaryFolder.root, "repository")
        RecentUsageRepository(root, "phone").recordUse("old", record.name, 20)
        val transaction = File(root, ".emorepo-pack-transaction")
        assertTrue(transaction.mkdir())
        File(root, "index.jsonl").copyTo(File(transaction, "root-index.backup"))
        File(root, "recent").copyRecursively(File(transaction, "recent-backup"))
        writeTransactionManifest(transaction, "RENAME", "old", "new")
        assertTrue(File(root, "old").renameTo(File(root, "new")))
        File(root, "index.jsonl").writeText(RootIndexJsonlCodec.encode(listOf(PackIndexRecord("new", true))))
        RecentUsageRepository(root, "phone").renamePackageAcrossDevices("old", "new")

        val recovered = repository()
        recovered.listPacks()

        assertEquals(listOf(PackIndexRecord("old", collapsed = true)), rootRecords())
        assertTrue(File(root, "old/${record.name}").isFile)
        assertFalse(File(root, "new").exists())
        assertEquals("old", RecentUsageRepository(root, "phone").readCurrentDevice().single().packageName)
    }

    @Test
    fun interruptedPackEditRestoresFilesIndexAndRecentUsage() {
        val repository = repository()
        repository.createPack("cats")
        repository.import(
            "cats",
            listOf(ImportCandidate("a.png", png(47)), ImportCandidate("b.png", png(48))),
        )
        val root = File(temporaryFolder.root, "repository")
        val records = repository.getPack("cats").records
        RecentUsageRepository(root, "phone").recordUse("cats", records[1].name, 20)
        val transaction = File(root, ".emorepo-pack-transaction")
        assertTrue(transaction.mkdir())
        File(root, "index.jsonl").copyTo(File(transaction, "root-index.backup"))
        File(root, "cats/index.jsonl").copyTo(File(transaction, "pack-index.backup"))
        File(root, "recent").copyRecursively(File(transaction, "recent-backup"))
        writeTransactionManifest(transaction, "EDIT", "cats")
        val deleted = File(transaction, "deleted")
        assertTrue(deleted.mkdir())
        assertTrue(File(root, "cats/${records[1].name}").renameTo(File(deleted, records[1].name)))
        File(root, "cats/index.jsonl").writeText(IndexJsonlCodec.encode(listOf(records[0])))
        RecentUsageRepository(root, "phone").remove("cats", records[1].name)

        val recovered = repository()

        assertEquals(records.map { it.md5 }, recovered.getPack("cats").records.map { it.md5 })
        assertTrue(File(root, "cats/${records[1].name}").isFile)
        assertEquals(records[1].name, RecentUsageRepository(root, "phone").readCurrentDevice().single().name)
        assertFalse(transaction.exists())
    }

    @Test
    fun committedDeleteTransactionIsCleanedWithoutRollbackOnRepositoryOpen() {
        val repository = repository()
        repository.createPack("cats")
        val root = File(temporaryFolder.root, "repository")
        val rootIndex = File(root, "index.jsonl")
        val completed = File(root, ".emorepo-pack-transaction.completed")
        assertTrue(completed.mkdir())
        rootIndex.copyTo(File(completed, "root-index.backup"))
        assertTrue(File(root, "cats").renameTo(File(completed, "pack")))
        rootIndex.writeText("")

        val reopened = repository()

        assertTrue(reopened.listPacks().isEmpty())
        assertFalse(File(root, "cats").exists())
        assertFalse(completed.exists())
    }

    @Test
    fun imagePathResolutionDoesNotWaitForRepositoryMutationLock() {
        createLegacyPack("cats")
        val repositoryRoot = File(temporaryFolder.root, "repository")
        val repository = EmoticonRepository(repositoryRoot)
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = thread {
            RepositoryLocks.forMutation(repositoryRoot).withLock {
                lockAcquired.countDown()
                releaseLock.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(lockAcquired.await(1, TimeUnit.SECONDS))

        val executor = Executors.newSingleThreadExecutor()
        try {
            val resolved = executor.submit<File> { repository.imageFile("cats", "cat.png") }
                .get(1, TimeUnit.SECONDS)
            assertEquals(File(repositoryRoot, "cats/cat.png").canonicalFile, resolved)
        } finally {
            releaseLock.countDown()
            executor.shutdownNow()
            lockHolder.join(1_000)
        }
    }

    @Test
    fun repositoryConstructionDoesNotWaitForRepositoryMutationLock() {
        createLegacyPack("cats")
        val repositoryRoot = File(temporaryFolder.root, "repository")
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = thread {
            RepositoryLocks.forMutation(repositoryRoot).withLock {
                lockAcquired.countDown()
                releaseLock.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(lockAcquired.await(1, TimeUnit.SECONDS))

        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit<EmoticonRepository> { EmoticonRepository(repositoryRoot) }
                .get(1, TimeUnit.SECONDS)
        } finally {
            releaseLock.countDown()
            executor.shutdownNow()
            lockHolder.join(1_000)
        }
    }

    @Test
    fun repositoryReadsReturnLastValidSnapshotDuringFailedGitMutation() {
        val repository = repository()
        repository.createPack("cats")
        assertEquals(listOf("cats"), repository.listPacks().map { it.name })
        val rootIndex = File(temporaryFolder.root, "repository/index.jsonl")
        val validContent = rootIndex.readText()

        val failure = runCatching {
            repository.withGitMutation {
                rootIndex.writeText("{broken")
                assertEquals(listOf("cats"), repository.listPacks().map { it.name })
                error("模拟 Git 写入失败")
            }
        }

        assertTrue(failure.isFailure)
        assertEquals(listOf("cats"), repository.listPacks().map { it.name })
        rootIndex.writeText(validContent)
        assertEquals(listOf("cats"), repository.validateLivePacks().map { it.name })
    }

    private fun repository(): EmoticonRepository = EmoticonRepository(
        File(temporaryFolder.root, "repository"),
        currentTimeMillis = { 1000L },
    )

    private fun rootRecords(): List<PackIndexRecord> = RootIndexJsonlCodec.decode(
        File(temporaryFolder.root, "repository/index.jsonl").readText(),
    )

    private fun writeTransactionManifest(
        directory: File,
        operation: String,
        source: String,
        target: String? = null,
    ) {
        File(directory, "manifest.properties").outputStream().use { output ->
            Properties().apply {
                setProperty("operation", operation)
                setProperty("source", source)
                target?.let { setProperty("target", it) }
                setProperty("rootExists", "true")
            }.store(output, null)
        }
    }

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
