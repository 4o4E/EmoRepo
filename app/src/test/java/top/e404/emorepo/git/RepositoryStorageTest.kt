package top.e404.emorepo.git

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepositoryStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `storage inspection separates worktree and git bytes`() {
        val root = temporaryFolder.newFolder("repository")
        File(root, "pack").mkdirs()
        File(root, "pack/image.png").writeBytes(ByteArray(11))
        File(root, "index.jsonl").writeBytes(ByteArray(7))
        File(root, ".git/objects").mkdirs()
        File(root, ".git/objects/pack").writeBytes(ByteArray(23))
        File(root, ".git/shallow").writeText("abc")

        val stats = inspectRepositoryStorage(root)

        assertEquals(18L, stats.worktreeBytes)
        assertEquals(26L, stats.gitBytes)
        assertTrue(stats.shallow)
    }

    @Test
    fun `full repository always needs maintenance`() {
        assertTrue(RepositoryStorageStats(100, 1, shallow = false).needsAutomaticMaintenance)
    }

    @Test
    fun `small shallow repository stays below minimum threshold`() {
        assertFalse(
            RepositoryStorageStats(
                worktreeBytes = 200L * 1024L * 1024L,
                gitBytes = 300L * 1024L * 1024L,
                shallow = true,
            ).needsAutomaticMaintenance,
        )
    }

    @Test
    fun `large shallow repository uses worktree ratio threshold`() {
        val stats = RepositoryStorageStats(
            worktreeBytes = 600L * 1024L * 1024L,
            gitBytes = 901L * 1024L * 1024L,
            shallow = true,
        )
        assertTrue(stats.needsAutomaticMaintenance)
    }
}
