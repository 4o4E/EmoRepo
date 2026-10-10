package top.e404.emorepo.experiment.lsposed

import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.repository.EmoticonRepository
import top.e404.emorepo.repository.ImportCandidate
import top.e404.emorepo.repository.ManagementBatchResult

class QqImportTaskQueuesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test(timeout = 10_000L)
    fun nextChooserReadsExistingContentWhileSecondImportWaitsForGitMutation() {
        val repository = repository()
        val first = repository.import("smoke", listOf(ImportCandidate("first.png", png))).items.single().record!!
        val gitWorker = Executors.newSingleThreadExecutor()
        val gitEntered = CountDownLatch(1)
        val releaseGit = CountDownLatch(1)
        val importEntered = CountDownLatch(1)
        val queues = QqImportTaskQueues()
        try {
            val git = gitWorker.submit {
                repository.withGitMutation {
                    gitEntered.countDown()
                    check(releaseGit.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(gitEntered.await(1, TimeUnit.SECONDS))
            val second = queues.imports.submit<ManagementBatchResult> {
                importEntered.countDown()
                repository.import("smoke", listOf(ImportCandidate("second.gif", gif)))
            }
            assertTrue(importEntered.await(1, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { second.get(100, TimeUnit.MILLISECONDS) }

            val packs = queues.preparation.submit<List<EmoticonPack>> { repository.listPacks() }
                .get(1, TimeUnit.SECONDS)
            assertEquals(listOf(first.md5), packs.single().records.map { it.md5 })

            releaseGit.countDown()
            git.get(1, TimeUnit.SECONDS)
            val result = second.get(1, TimeUnit.SECONDS)
            assertEquals(1, result.succeeded)
            assertEquals(0, result.failed)
            assertEquals(
                setOf(first.md5, result.items.single().record!!.md5),
                repository.getPack("smoke").records.map { it.md5 }.toSet(),
            )
        } finally {
            releaseGit.countDown()
            queues.close()
            gitWorker.shutdownNow()
            assertTrue(gitWorker.awaitTermination(1, TimeUnit.SECONDS))
            awaitQueues(queues)
        }
    }

    @Test(timeout = 10_000L)
    fun stalledPreviewDoesNotBlockAnotherChooserOrConfirmedLocalImport() {
        val repository = repository()
        val queues = QqImportTaskQueues()
        val previewEntered = CountDownLatch(1)
        val releasePreview = CountDownLatch(1)
        try {
            val preview = queues.preview.submit {
                previewEntered.countDown()
                check(releasePreview.await(5, TimeUnit.SECONDS))
            }
            assertTrue(previewEntered.await(1, TimeUnit.SECONDS))
            val packs = queues.preparation.submit<List<EmoticonPack>> { repository.listPacks() }
                .get(1, TimeUnit.SECONDS)
            assertEquals("smoke", packs.single().name)
            val imported = queues.imports.submit<ManagementBatchResult> {
                repository.import("smoke", listOf(ImportCandidate("confirmed.png", png)))
            }.get(1, TimeUnit.SECONDS)
            assertEquals(1, imported.succeeded)
            assertEquals(imported.items.single().record!!.md5, repository.getPack("smoke").records.single().md5)
            releasePreview.countDown()
            preview.get(1, TimeUnit.SECONDS)
        } finally {
            releasePreview.countDown()
            queues.close()
            awaitQueues(queues)
        }
    }

    private fun repository(): EmoticonRepository =
        EmoticonRepository(File(temporaryFolder.root, "repository")).also { it.createPack("smoke") }

    private fun awaitQueues(queues: QqImportTaskQueues) {
        assertTrue(queues.preparation.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(queues.preview.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(queues.imports.awaitTermination(1, TimeUnit.SECONDS))
    }

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jv1sAAAAASUVORK5CYII=")
    private val gif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
}
