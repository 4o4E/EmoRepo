package top.e404.emorepo.git

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.protocol.index.IndexJsonlCodec
import top.e404.emorepo.protocol.pack.PackIndexRecord
import top.e404.emorepo.protocol.pack.RootIndexJsonlCodec
import top.e404.emorepo.repository.RepositoryLocks

class JGitRepositoryServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `sync commits local change rebases and pushes`() {
        val remote = createRemoteWithInitialCommit()
        val local = File(temporaryFolder.root, "local")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(local).call().close()
        File(local, "local-change.txt").writeText("local")

        val result = JGitRepositoryService().sync(local, settings(), token = null)

        assertTrue(result.committed)
        val verification = File(temporaryFolder.root, "verification")
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(verification).call().close()
        assertEquals("local", File(verification, "local-change.txt").readText())
    }

    @Test
    fun `network fetch releases content lock and captures concurrent local change`() {
        val remote = createRemoteWithInitialCommit()
        val local = clone(remote, "fetch-concurrent-local")
        val lockProbe = Executors.newSingleThreadExecutor()
        var wroteDuringFetch = false

        try {
            val result = JGitRepositoryService().sync(local, settings(), token = null) { event ->
                if (event.stage == GitSyncStage.FETCH && event.outcome == GitSyncStageOutcome.STARTED) {
                    val readable = lockProbe.submit(Callable {
                        val lock = RepositoryLocks.forRoot(local)
                        if (!lock.tryLock(1, TimeUnit.SECONDS)) return@Callable false
                        try {
                            File(local, "during-fetch.txt").writeText("available")
                            true
                        } finally {
                            lock.unlock()
                        }
                    }).get(2, TimeUnit.SECONDS)
                    assertTrue("fetch 不应占用仓库内容锁", readable)
                    wroteDuringFetch = true
                }
            }

            assertTrue(wroteDuringFetch)
            assertTrue(result.committed)
            val verification = clone(remote, "fetch-concurrent-verification")
            assertEquals("available", File(verification, "during-fetch.txt").readText())
        } finally {
            lockProbe.shutdownNow()
        }
    }

    @Test
    fun `sync resolves index conflict with local order and newest time`() {
        val initial = listOf(record("a", 10), record("b", 10))
        val remote = createRemoteWithInitialCommit { root ->
            File(root, "pack").mkdirs()
            File(root, "pack/index.jsonl").writeText(IndexJsonlCodec.encode(initial))
        }
        val local = clone(remote, "conflict-local")
        val other = clone(remote, "conflict-other")
        File(local, "pack/index.jsonl").writeText(
            IndexJsonlCodec.encode(listOf(record("b", 10), record("a", 10))),
        )
        File(other, "pack/index.jsonl").writeText(
            IndexJsonlCodec.encode(listOf(record("a", 40), record("b", 10))),
        )
        commitAndPush(other, "remote change")

        JGitRepositoryService().sync(local, settings(), token = null)

        val verification = clone(remote, "conflict-verification")
        val records = IndexJsonlCodec.decode(File(verification, "pack/index.jsonl").readText())
        assertEquals(listOf("b", "a"), records.map { it.md5.first().toString() })
        assertEquals(listOf(10L, 40L), records.map { it.time })
    }

    @Test
    fun `sync resolves root index conflict and validates pack directories`() {
        val initialOrder = listOf(PackIndexRecord("a"), PackIndexRecord("b"))
        val remote = createRemoteWithInitialCommit { root ->
            createEmptyPack(root, "a")
            createEmptyPack(root, "b")
            File(root, "index.jsonl").writeText(RootIndexJsonlCodec.encode(initialOrder))
        }
        val local = clone(remote, "root-index-local")
        val other = clone(remote, "root-index-other")
        File(local, "index.jsonl").writeText(
            RootIndexJsonlCodec.encode(
                listOf(PackIndexRecord("b"), PackIndexRecord("a")),
            ),
        )
        createEmptyPack(other, "c")
        File(other, "index.jsonl").writeText(
            RootIndexJsonlCodec.encode(initialOrder + PackIndexRecord("c")),
        )
        commitAndPush(other, "remote adds pack")

        JGitRepositoryService().sync(local, settings(), token = null)

        val verification = clone(remote, "root-index-verification")
        val records = RootIndexJsonlCodec.decode(File(verification, "index.jsonl").readText())
        assertEquals(listOf("b", "c", "a"), records.map { it.name })
        assertTrue(File(verification, "c/index.jsonl").isFile)
    }

    @Test
    fun `sync recovers stale index lock after validating existing index`() {
        val remote = createRemoteWithInitialCommit()
        val local = clone(remote, "stale-lock-local")
        File(local, "local-change.txt").writeText("local")
        val lock = File(local, ".git/index.lock").apply { writeText("") }
        val events = mutableListOf<GitSyncStageEvent>()

        JGitRepositoryService().sync(local, settings(), token = null) { event -> events += event }

        assertFalse(lock.exists())
        assertTrue(
            events.any { event ->
                event.stage == GitSyncStage.PRECHECK &&
                    event.outcome == GitSyncStageOutcome.WARNING &&
                    event.fields["recoveredStaleIndexLock"] == "true"
            },
        )
    }

    @Test
    fun `sync keeps index lock when existing index cannot be validated`() {
        val remote = createRemoteWithInitialCommit()
        val local = clone(remote, "broken-index-local")
        File(local, ".git/index").writeBytes(byteArrayOf(1, 2, 3, 4))
        val lock = File(local, ".git/index.lock").apply { writeText("") }

        val result = runCatching { JGitRepositoryService().sync(local, settings(), token = null) }

        assertTrue(result.isFailure)
        assertTrue(lock.exists())
    }

    private fun createRemoteWithInitialCommit(
        prepare: (File) -> Unit = { root -> File(root, "initial.txt").writeText("initial") },
    ): File {
        val seed = temporaryFolder.newFolder("seed")
        Git.init().setDirectory(seed).call().use { git ->
            prepare(seed)
            git.add().addFilepattern(".").call()
            val identity = PersonIdent("Test", "test@example.com")
            git.commit().setMessage("initial").setAuthor(identity).setCommitter(identity).call()
        }
        val remote = File(temporaryFolder.root, "remote.git")
        Git.cloneRepository()
            .setURI(seed.toURI().toString())
            .setDirectory(remote)
            .setBare(true)
            .call()
            .close()
        return remote
    }

    private fun clone(remote: File, name: String): File = File(temporaryFolder.root, name).also { target ->
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(target).call().close()
    }

    private fun commitAndPush(root: File, message: String) {
        Git.open(root).use { git ->
            git.add().addFilepattern(".").call()
            val identity = PersonIdent("Test", "test@example.com")
            git.commit().setMessage(message).setAuthor(identity).setCommitter(identity).call()
            git.push().call()
        }
    }

    private fun createEmptyPack(root: File, name: String) {
        val directory = File(root, name)
        assertTrue(directory.mkdirs())
        File(directory, "index.jsonl").writeText("")
    }

    private fun record(id: String, time: Long) = EmoticonRecord(
        name = id.repeat(32) + ".png",
        md5 = id.repeat(32),
        ext = "png",
        time = time,
    )

    private fun settings() = AppSettings(
        setupComplete = true,
        remoteUrl = "https://example.com/repository.git",
        authorName = "Test",
        authorEmail = "test@example.com",
        deviceId = "android-test",
    )
}
