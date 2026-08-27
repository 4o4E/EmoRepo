package top.e404.emorepo.git

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.e404.emorepo.config.AppSettings
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.protocol.index.IndexJsonlCodec
import top.e404.emorepo.protocol.pack.PackOrderRecord
import top.e404.emorepo.protocol.pack.RootIndexJsonlCodec

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
    fun `sync resolves index conflict with local order and newest time`() {
        val initial = listOf(record("a", 10, 1_024), record("b", 10, 2_048))
        val remote = createRemoteWithInitialCommit { root ->
            File(root, "pack").mkdirs()
            File(root, "pack/index.jsonl").writeText(IndexJsonlCodec.encode(initial))
        }
        val local = clone(remote, "conflict-local")
        val other = clone(remote, "conflict-other")
        File(local, "pack/index.jsonl").writeText(
            IndexJsonlCodec.encode(listOf(record("b", 10, 1_024), record("a", 10, 2_048))),
        )
        File(other, "pack/index.jsonl").writeText(
            IndexJsonlCodec.encode(listOf(record("a", 40, 1_024), record("b", 10, 2_048))),
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
        val initialOrder = listOf(PackOrderRecord("a", 1_024), PackOrderRecord("b", 2_048))
        val remote = createRemoteWithInitialCommit { root ->
            createEmptyPack(root, "a")
            createEmptyPack(root, "b")
            File(root, "index.jsonl").writeText(RootIndexJsonlCodec.encode(initialOrder))
        }
        val local = clone(remote, "root-index-local")
        val other = clone(remote, "root-index-other")
        File(local, "index.jsonl").writeText(
            RootIndexJsonlCodec.encode(
                listOf(PackOrderRecord("b", 1_024), PackOrderRecord("a", 2_048)),
            ),
        )
        createEmptyPack(other, "c")
        File(other, "index.jsonl").writeText(
            RootIndexJsonlCodec.encode(initialOrder + PackOrderRecord("c", 3_072)),
        )
        commitAndPush(other, "remote adds pack")

        JGitRepositoryService().sync(local, settings(), token = null)

        val verification = clone(remote, "root-index-verification")
        val records = RootIndexJsonlCodec.decode(File(verification, "index.jsonl").readText())
        assertEquals(listOf("b", "a", "c"), records.map { it.name })
        assertEquals(listOf(1_024L, 2_048L, 3_072L), records.map { it.order })
        assertTrue(File(verification, "c/index.jsonl").isFile)
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

    private fun record(id: String, time: Long, order: Long) = EmoticonRecord(
        name = id.repeat(32) + ".png",
        md5 = id.repeat(32),
        ext = "png",
        time = time,
        order = order,
    )

    private fun settings() = AppSettings(
        setupComplete = true,
        remoteUrl = "https://example.com/repository.git",
        authorName = "Test",
        authorEmail = "test@example.com",
        deviceId = "android-test",
    )
}
