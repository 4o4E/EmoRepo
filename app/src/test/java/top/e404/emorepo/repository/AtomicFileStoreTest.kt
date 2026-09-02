package top.e404.emorepo.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writeReplacesTargetAndRemovesArtifacts() {
        val target = temporaryFolder.newFile("index.jsonl").apply { writeText("old") }

        AtomicFileStore.writeText(target, "new")

        assertEquals("new", target.readText())
        assertFalse(File(target.parentFile, ".index.jsonl.emorepo-new").exists())
        assertFalse(File(target.parentFile, ".index.jsonl.emorepo-backup").exists())
    }

    @Test
    fun recoverCompletesStagedReplacementWhenTargetIsMissing() {
        val target = File(temporaryFolder.root, "index.jsonl")
        File(temporaryFolder.root, ".index.jsonl.emorepo-new").writeText("new")
        File(temporaryFolder.root, ".index.jsonl.emorepo-backup").writeText("old")

        AtomicFileStore.recover(target)

        assertEquals("new", target.readText())
        assertFalse(File(temporaryFolder.root, ".index.jsonl.emorepo-backup").exists())
    }

    @Test
    fun recoverRestoresBackupWhenNoStagedFileExists() {
        val target = File(temporaryFolder.root, "index.jsonl")
        File(temporaryFolder.root, ".index.jsonl.emorepo-backup").writeText("old")

        AtomicFileStore.recover(target)

        assertEquals("old", target.readText())
    }

    @Test
    fun snapshotReadUsesCompleteBackupWithoutMutatingReplacementWindow() {
        val target = File(temporaryFolder.root, "index.jsonl")
        val staged = File(temporaryFolder.root, ".index.jsonl.emorepo-new").apply { writeText("new") }
        val backup = File(temporaryFolder.root, ".index.jsonl.emorepo-backup").apply { writeText("old") }

        assertEquals("old", AtomicFileStore.readSnapshotText(target))
        assertFalse(target.exists())
        assertEquals("new", staged.readText())
        assertEquals("old", backup.readText())
    }
}
