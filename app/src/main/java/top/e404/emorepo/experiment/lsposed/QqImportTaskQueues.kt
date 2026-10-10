package top.e404.emorepo.experiment.lsposed

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 目标查询不能排在原图下载或等待仓库写入锁的导入之后。 */
internal class QqImportTaskQueues : Closeable {
    val preparation: ExecutorService = singleThread("EmoRepo-QQ-Import-Prepare")
    val preview: ExecutorService = singleThread("EmoRepo-QQ-Import-Preview")
    val imports: ExecutorService = singleThread("EmoRepo-QQ-Import")

    override fun close() {
        preparation.shutdownNow()
        preview.shutdownNow()
        imports.shutdownNow()
    }

    private fun singleThread(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
}
