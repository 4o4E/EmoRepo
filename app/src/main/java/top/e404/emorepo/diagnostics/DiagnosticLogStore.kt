package top.e404.emorepo.diagnostics

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

internal data class DiagnosticLogEvent(
    val schemaVersion: Int = 1,
    val timestamp: String,
    val level: String,
    val process: String,
    val thread: String,
    val component: String,
    val event: String,
    val message: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val stackTrace: String? = null,
)

internal class DiagnosticLogStore(
    private val directory: File,
    private val maximumFileBytes: Long = DEFAULT_MAXIMUM_FILE_BYTES,
    private val historyCount: Int = DEFAULT_HISTORY_COUNT,
    private val gson: Gson = Gson(),
) {
    init {
        require(maximumFileBytes > 0L) { "日志文件上限必须大于 0" }
        require(historyCount >= 0) { "历史日志数量不能小于 0" }
    }

    @Synchronized
    fun append(event: DiagnosticLogEvent) {
        ensureDirectory()
        val bytes = (gson.toJson(event) + "\n").toByteArray(StandardCharsets.UTF_8)
        val current = currentFile()
        if (current.isFile && current.length() + bytes.size > maximumFileBytes) rotate()
        FileOutputStream(current, true).use { output ->
            output.write(bytes)
            output.flush()
        }
    }

    @Synchronized
    fun snapshot(destination: File): List<File> {
        if (!destination.mkdirs() && !destination.isDirectory) {
            error("无法创建日志快照目录")
        }
        return logFiles().map { source ->
            File(destination, source.name).also { target -> source.copyTo(target, overwrite = true) }
        }
    }

    @Synchronized
    fun logFiles(): List<File> = buildList {
        val current = currentFile()
        if (current.isFile) add(current)
        for (index in 1..historyCount) {
            val history = historyFile(index)
            if (history.isFile) add(history)
        }
    }

    private fun rotate() {
        if (historyCount == 0) {
            currentFile().delete()
            return
        }
        val oldest = historyFile(historyCount)
        if (oldest.exists() && !oldest.delete()) error("无法清理最旧诊断日志")
        for (index in historyCount downTo 2) {
            val source = historyFile(index - 1)
            if (source.exists() && !source.renameTo(historyFile(index))) {
                error("无法轮转诊断日志：${source.name}")
            }
        }
        val current = currentFile()
        if (current.exists() && !current.renameTo(historyFile(1))) error("无法轮转当前诊断日志")
    }

    private fun ensureDirectory() {
        if (!directory.mkdirs() && !directory.isDirectory) error("无法创建诊断日志目录")
    }

    private fun currentFile(): File = File(directory, CURRENT_FILE_NAME)

    private fun historyFile(index: Int): File = File(directory, "$CURRENT_FILE_NAME.$index")

    companion object {
        const val CURRENT_FILE_NAME = "emorepo.log"
        const val DEFAULT_MAXIMUM_FILE_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_HISTORY_COUNT = 4
    }
}
