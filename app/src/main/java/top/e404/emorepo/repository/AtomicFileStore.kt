package top.e404.emorepo.repository

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object AtomicFileStore {
    fun readBytes(target: File): ByteArray {
        recover(target)
        return target.readBytes()
    }

    fun readText(target: File): String = readBytes(target).toString(Charsets.UTF_8)

    /** 只读路径不参与恢复，按“正式文件→旧备份→已落盘新文件”取得完整字节。 */
    fun readSnapshotBytes(target: File): ByteArray {
        val source = sequenceOf(target, backupFile(target), stagedFile(target))
            .firstOrNull(File::isFile)
            ?: throw IOException("atomic file does not exist: ${target.name}")
        return source.readBytes()
    }

    fun readSnapshotText(target: File): String = readSnapshotBytes(target).toString(Charsets.UTF_8)

    fun hasRecoveryArtifacts(target: File): Boolean =
        stagedFile(target).exists() || backupFile(target).exists()

    fun writeText(target: File, content: String) {
        writeBytes(target, content.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(target: File, content: ByteArray) {
        require(target.parentFile != null) { "target must have a parent directory" }
        target.parentFile!!.mkdirs()
        recover(target)
        val staged = stagedFile(target)
        val backup = backupFile(target)
        if (staged.exists() && !staged.delete()) {
            throw IOException("cannot remove stale staged file: ${staged.name}")
        }
        FileOutputStream(staged).use { output ->
            output.write(content)
            output.flush()
            output.fd.sync()
        }
        if (target.exists()) {
            if (backup.exists() && !backup.delete()) {
                staged.delete()
                throw IOException("cannot remove stale backup file: ${backup.name}")
            }
            if (!target.renameTo(backup)) {
                staged.delete()
                throw IOException("cannot back up target file: ${target.name}")
            }
        }
        if (!staged.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            staged.delete()
            throw IOException("cannot replace target file: ${target.name}")
        }
        backup.delete()
    }

    fun recover(target: File) {
        val staged = stagedFile(target)
        val backup = backupFile(target)
        if (target.exists()) {
            staged.delete()
            backup.delete()
            return
        }
        if (staged.exists()) {
            if (!staged.renameTo(target)) {
                throw IOException("cannot complete staged file: ${target.name}")
            }
            backup.delete()
            return
        }
        if (backup.exists() && !backup.renameTo(target)) {
            throw IOException("cannot restore backup file: ${target.name}")
        }
    }

    private fun stagedFile(target: File): File =
        File(target.parentFile, ".${target.name}.emorepo-new")

    private fun backupFile(target: File): File =
        File(target.parentFile, ".${target.name}.emorepo-backup")
}
