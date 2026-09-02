package top.e404.emorepo.experiment.lsposed

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import top.e404.emorepo.ipc.EmoRepoIpcContract

/** QQ 进程使用的只读仓库客户端，所有调用仍由 EmoRepo Provider 校验调用 UID。 */
internal object QqPanelRepository {
    fun configuration(context: Context): PanelConfiguration {
        val uri = contentUri(EmoRepoIpcContract.PATH_PANEL_CONFIGURATION)
        return query(context, uri) { cursor ->
            check(cursor.moveToFirst()) { "EmoRepo QQ 面板设置为空" }
            val columns = cursor.getInt(
                cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_PANEL_COLUMNS),
            )
            require(columns in 3..8) { "EmoRepo QQ 面板列数无效：$columns" }
            PanelConfiguration(columns)
        }
    }

    fun revision(context: Context): Long {
        val uri = contentUri(EmoRepoIpcContract.PATH_REVISION)
        return query(context, uri) { cursor ->
            check(cursor.moveToFirst()) { "EmoRepo 仓库版本为空" }
            cursor.getLong(cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_REVISION))
        }
    }

    fun listPacks(context: Context): List<PanelPack> {
        val uri = contentUri(EmoRepoIpcContract.PATH_PACKS)
        return query(context, uri) { cursor ->
            val id = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ID)
            val name = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_DISPLAY_NAME)
            val cover = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_COVER_ITEM_ID)
            val coverPack = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_COVER_PACK_ID)
            val coverAnimated = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_COVER_ANIMATED)
            val count = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ITEM_COUNT)
            val writable = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_WRITABLE)
            val collapsed = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_COLLAPSED)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PanelPack(
                            id = cursor.getString(id),
                            displayName = cursor.getString(name),
                            coverItemId = cursor.getString(cover),
                            coverPackId = cursor.getString(coverPack),
                            coverAnimated = cursor.getInt(coverAnimated) != 0,
                            itemCount = cursor.getInt(count),
                            writable = cursor.getInt(writable) != 0,
                            collapsed = cursor.getInt(collapsed) != 0,
                        ),
                    )
                }
            }
        }
    }

    fun listItems(context: Context, pack: PanelPack): List<PanelItem> {
        val result = ArrayList<PanelItem>(pack.itemCount)
        var offset = 0
        while (offset < pack.itemCount) {
            val limit = minOf(EmoRepoIpcContract.MAXIMUM_PAGE_SIZE, pack.itemCount - offset)
            val uri = contentUri(EmoRepoIpcContract.PATH_ITEMS, pack.id).buildUpon()
                .appendQueryParameter(EmoRepoIpcContract.QUERY_OFFSET, offset.toString())
                .appendQueryParameter(EmoRepoIpcContract.QUERY_LIMIT, limit.toString())
                .build()
            val page = query(context, uri) { cursor ->
                val id = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ID)
                val fileName = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_FILE_NAME)
                val mimeType = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_MIME_TYPE)
                val animated = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ANIMATED)
                val sourcePack = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_SOURCE_PACK_ID)
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            PanelItem(
                                id = cursor.getString(id),
                                fileName = cursor.getString(fileName),
                                mimeType = cursor.getString(mimeType),
                                animated = cursor.getInt(animated) != 0,
                                packId = cursor.getString(sourcePack),
                            ),
                        )
                    }
                }
            }
            if (page.isEmpty()) break
            result += page
            offset += page.size
        }
        return result
    }

    fun itemUri(packId: String, itemId: String): Uri =
        contentUri(EmoRepoIpcContract.PATH_ITEM, packId, itemId)

    fun recordUse(context: Context, packId: String, itemId: String, usedAt: Long) {
        runIpc("记录最近使用") {
            context.contentResolver.call(
                EmoRepoIpcContract.BASE_URI,
                EmoRepoIpcContract.METHOD_RECORD_USE,
                null,
                Bundle().apply {
                    putString(EmoRepoIpcContract.EXTRA_PACK_ID, packId)
                    putString(EmoRepoIpcContract.EXTRA_ITEM_ID, itemId)
                    putLong(EmoRepoIpcContract.EXTRA_USED_AT, usedAt)
                },
            )
        }
    }

    private fun contentUri(vararg segments: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(EmoRepoIpcContract.AUTHORITY)
        .apply { segments.forEach(::appendPath) }
        .build()

    private fun <T> query(context: Context, uri: Uri, read: (Cursor) -> T): T {
        val cancellation = CancellationSignal()
        val cursor = runIpc("查询 $uri", cancellation::cancel) {
            context.contentResolver.query(uri, null, null, null, null, cancellation)
        }
        return requireNotNull(cursor) { "EmoRepo 未返回查询结果：$uri" }.use(read)
    }

    private fun <T> runIpc(
        operation: String,
        cancel: () -> Unit = {},
        block: () -> T,
    ): T {
        val future = ipcExecutor.submit<T>(block)
        return try {
            future.get(IPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            cancel()
            future.cancel(true)
            throw IOException("EmoRepo IPC 超时或失败：$operation", error)
        }
    }

    private val ipcExecutor = Executors.newFixedThreadPool(IPC_MAXIMUM_THREADS) { runnable ->
        Thread(runnable, "EmoRepo-Panel-IPC").apply { isDaemon = true }
    }

    private const val IPC_TIMEOUT_MILLIS = 3_000L
    private const val IPC_MAXIMUM_THREADS = 4
}

internal data class PanelPack(
    val id: String,
    val displayName: String,
    val coverItemId: String?,
    val coverPackId: String?,
    val coverAnimated: Boolean,
    val itemCount: Int,
    val writable: Boolean,
    val collapsed: Boolean,
)

internal data class PanelItem(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val animated: Boolean,
    val packId: String,
)

internal data class PanelConfiguration(
    val columns: Int,
)
