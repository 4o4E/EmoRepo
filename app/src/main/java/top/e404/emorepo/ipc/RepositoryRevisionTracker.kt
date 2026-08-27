package top.e404.emorepo.ipc

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest

internal class RepositoryRevisionTracker(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun revision(root: File): Long {
        val signature = signature(root)
        val previousSignature = preferences.getString(KEY_SIGNATURE, null)
        val previousRevision = preferences.getLong(KEY_REVISION, 0L)
        if (signature == previousSignature && previousRevision > 0) return previousRevision
        val next = maxOf(previousRevision + 1L, System.currentTimeMillis())
        preferences.edit(commit = true) {
            putString(KEY_SIGNATURE, signature)
            putLong(KEY_REVISION, next)
        }
        return next
    }

    private fun signature(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(root, "index.jsonl").takeIf(File::isFile)?.let { index ->
            update(digest, index.name)
            update(digest, index.length().toString())
            update(digest, index.lastModified().toString())
        }
        root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name != ".git" && it.name != "recent" && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .forEach { pack ->
                update(digest, pack.name)
                pack.listFiles().orEmpty().filter(File::isFile).sortedBy(File::getName).forEach { file ->
                    update(digest, file.name)
                    update(digest, file.length().toString())
                    update(digest, file.lastModified().toString())
                }
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun update(digest: MessageDigest, value: String) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    private companion object {
        const val PREFERENCES_NAME = "emorepo_provider_revision"
        const val KEY_SIGNATURE = "signature"
        const val KEY_REVISION = "revision"
    }
}
