package top.e404.emorepo.integration.qaux

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.qauxv.chainloader.api.emoticon.EmoticonItemInfo
import io.github.qauxv.chainloader.api.emoticon.EmoticonPackInfo
import io.github.qauxv.chainloader.api.emoticon.EmoticonProviderException
import io.github.qauxv.chainloader.api.emoticon.IEmoticonProvider
import top.e404.emorepo.ipc.EmoRepoIpcContract

internal class EmoRepoQAuxProvider(context: Context) : IEmoticonProvider {
    private val resolver = context.contentResolver

    @Volatile
    private var revisionCache = RevisionCache(0L, 0L)

    override fun getProviderId(): String = PROVIDER_ID

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun getRevision(): Long {
        val now = System.currentTimeMillis()
        val cached = revisionCache
        if (now - cached.loadedAtMillis < REVISION_CACHE_MILLIS && cached.value > 0L) {
            return cached.value
        }
        return ipc {
            resolver.query(uri(EmoRepoIpcContract.PATH_REVISION), null, null, null, null).use { cursor ->
                requireNotNull(cursor) { "EmoRepo 未返回仓库版本" }
                check(cursor.moveToFirst()) { "EmoRepo 仓库版本为空" }
                cursor.getLong(cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_REVISION)).also {
                    revisionCache = RevisionCache(it, now)
                }
            }
        }
    }

    override fun listPacks(): List<EmoticonPackInfo> = ipc {
        resolver.query(uri(EmoRepoIpcContract.PATH_PACKS), null, null, null, null).use { cursor ->
            requireNotNull(cursor) { "EmoRepo 未返回表情包" }
            buildList {
                while (cursor.moveToNext()) {
                    val coverIndex = cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_COVER_ITEM_ID)
                    add(
                        EmoticonPackInfo(
                            cursor.getString(cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ID)),
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_DISPLAY_NAME),
                            ),
                            if (cursor.isNull(coverIndex)) null else cursor.getString(coverIndex),
                            cursor.getInt(
                                cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ITEM_COUNT),
                            ),
                            cursor.getLong(cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ORDER)),
                        ),
                    )
                }
            }
        }
    }

    override fun listItems(packId: String, offset: Int, limit: Int): List<EmoticonItemInfo> {
        require(offset >= 0) { "offset 不能小于 0" }
        require(limit in 1..EmoRepoIpcContract.MAXIMUM_PAGE_SIZE) { "limit 必须为 1..200" }
        return ipc {
            val target = uri(EmoRepoIpcContract.PATH_ITEMS, packId).buildUpon()
                .appendQueryParameter(EmoRepoIpcContract.QUERY_OFFSET, offset.toString())
                .appendQueryParameter(EmoRepoIpcContract.QUERY_LIMIT, limit.toString())
                .build()
            resolver.query(target, null, null, null, null).use { cursor ->
                requireNotNull(cursor) { "EmoRepo 未返回表情列表" }
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            EmoticonItemInfo(
                                cursor.getString(cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ID)),
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_FILE_NAME),
                                ),
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_MIME_TYPE),
                                ),
                                cursor.getInt(
                                    cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ANIMATED),
                                ) != 0,
                                cursor.getLong(
                                    cursor.getColumnIndexOrThrow(EmoRepoIpcContract.COLUMN_ORDER),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun openItem(packId: String, itemId: String): ParcelFileDescriptor = ipc {
        requireNotNull(
            resolver.openFileDescriptor(uri(EmoRepoIpcContract.PATH_ITEM, packId, itemId), "r"),
        ) { "EmoRepo 未返回表情文件" }
    }

    override fun recordUse(packId: String, itemId: String, usedAtMillis: Long) {
        require(usedAtMillis >= 0L) { "使用时间不能小于 0" }
        ipc {
            resolver.call(
                EmoRepoIpcContract.AUTHORITY,
                EmoRepoIpcContract.METHOD_RECORD_USE,
                null,
                Bundle().apply {
                    putString(EmoRepoIpcContract.EXTRA_PACK_ID, packId)
                    putString(EmoRepoIpcContract.EXTRA_ITEM_ID, itemId)
                    putLong(EmoRepoIpcContract.EXTRA_USED_AT, usedAtMillis)
                },
            )
        }
    }

    private fun uri(vararg segments: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(EmoRepoIpcContract.AUTHORITY)
        .apply { segments.forEach(::appendPath) }
        .build()

    private inline fun <T> ipc(block: () -> T): T = try {
        block()
    } catch (error: EmoticonProviderException) {
        throw error
    } catch (error: SecurityException) {
        throw EmoticonProviderException(
            EmoticonProviderException.CODE_SECURITY,
            "EmoRepo 拒绝了 QQ 的访问，请检查应用版本",
            error,
        )
    } catch (error: IllegalArgumentException) {
        throw EmoticonProviderException(
            EmoticonProviderException.CODE_INVALID_ARGUMENT,
            error.message ?: "EmoRepo Provider 参数无效",
            error,
        )
    } catch (error: Throwable) {
        throw EmoticonProviderException(
            EmoticonProviderException.CODE_UNAVAILABLE,
            "EmoRepo 暂时不可用，请稍后重试",
            error,
        )
    }

    private data class RevisionCache(val value: Long, val loadedAtMillis: Long)

    private companion object {
        const val PROVIDER_ID = "top.e404.emorepo"
        const val DISPLAY_NAME = "EmoRepo"
        const val REVISION_CACHE_MILLIS = 2_000L
    }
}
