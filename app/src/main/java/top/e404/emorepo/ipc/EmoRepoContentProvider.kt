package top.e404.emorepo.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import top.e404.emorepo.config.SettingsStore
import top.e404.emorepo.git.GitSyncScheduler
import top.e404.emorepo.repository.EmoticonRepository
import top.e404.emorepo.repository.RecentUsageRepository

class EmoRepoContentProvider : ContentProvider() {
    private lateinit var root: File
    private lateinit var repository: EmoticonRepository
    private lateinit var callerVerifier: CallerVerifier
    private lateinit var revisionTracker: RepositoryRevisionTracker

    override fun onCreate(): Boolean {
        val appContext = requireNotNull(context).applicationContext
        root = File(appContext.filesDir, "repository")
        repository = EmoticonRepository(root)
        callerVerifier = CallerVerifier(appContext)
        revisionTracker = RepositoryRevisionTracker(appContext)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        callerVerifier.enforceAllowedCaller()
        requireReadyRepository()
        return when (URI_MATCHER.match(uri)) {
            MATCH_REVISION -> queryRevision()
            MATCH_PACKS -> queryPacks()
            MATCH_ITEMS -> queryItems(uri)
            else -> throw IllegalArgumentException("不支持的 EmoRepo 查询 URI：$uri")
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        callerVerifier.enforceAllowedCaller()
        requireReadyRepository()
        require(mode == "r") { "EmoRepo Provider 只允许只读打开图片" }
        require(URI_MATCHER.match(uri) == MATCH_ITEM) { "不支持的 EmoRepo 图片 URI：$uri" }
        val packId = uri.pathSegments[1]
        val itemId = uri.pathSegments[2]
        val record = repository.getPack(packId).records.firstOrNull { it.md5 == itemId }
            ?: throw FileNotFoundException("表情不存在：$packId/$itemId")
        val file = repository.imageFile(packId, record.name)
        if (!file.isFile) throw FileNotFoundException("表情文件不存在：$packId/${record.name}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        callerVerifier.enforceAllowedCaller()
        requireReadyRepository()
        if (method != EmoRepoIpcContract.METHOD_RECORD_USE) {
            throw IllegalArgumentException("不支持的 EmoRepo Provider 调用：$method")
        }
        val values = requireNotNull(extras) { "record_use 缺少参数" }
        val packId = requireNotNull(values.getString(EmoRepoIpcContract.EXTRA_PACK_ID))
        val itemId = requireNotNull(values.getString(EmoRepoIpcContract.EXTRA_ITEM_ID))
        val usedAt = values.getLong(EmoRepoIpcContract.EXTRA_USED_AT, -1L)
        require(usedAt >= 0L) { "record_use 时间无效" }
        val record = repository.getPack(packId).records.firstOrNull { it.md5 == itemId }
            ?: throw IllegalArgumentException("表情不存在：$packId/$itemId")
        val appContext = requireNotNull(context).applicationContext
        val settings = SettingsStore(appContext).load()
        RecentUsageRepository(root, settings.deviceId, settings.recentMaximumRecords)
            .recordUse(packId, record.name, usedAt)
        GitSyncScheduler.requestRecentUsage(appContext, settings.recentSyncDelayMinutes)
        return Bundle.EMPTY
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        MATCH_ITEM -> "image/*"
        MATCH_REVISION, MATCH_PACKS, MATCH_ITEMS -> "vnd.android.cursor.dir/vnd.emorepo.emoticon"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("EmoRepo Provider 不支持 insert")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("EmoRepo Provider 不支持 delete")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("EmoRepo Provider 不支持 update")

    private fun queryRevision(): Cursor = MatrixCursor(
        arrayOf(EmoRepoIpcContract.COLUMN_REVISION),
        1,
    ).apply {
        addRow(arrayOf(revisionTracker.revision(root)))
    }

    private fun queryPacks(): Cursor = MatrixCursor(PACK_COLUMNS).apply {
        repository.listPacks().forEach { pack ->
            val records = pack.records.sortedBy { it.order }
            addRow(
                arrayOf<Any?>(
                    pack.name,
                    pack.name,
                    records.firstOrNull { it.icon }?.md5 ?: records.firstOrNull()?.md5,
                    records.size,
                    pack.order,
                ),
            )
        }
    }

    private fun queryItems(uri: Uri): Cursor {
        val packId = uri.pathSegments[1]
        val offset = uri.getQueryParameter(EmoRepoIpcContract.QUERY_OFFSET)?.toIntOrNull() ?: 0
        val limit = uri.getQueryParameter(EmoRepoIpcContract.QUERY_LIMIT)?.toIntOrNull()
            ?: EmoRepoIpcContract.MAXIMUM_PAGE_SIZE
        require(offset >= 0) { "offset 不能小于 0" }
        require(limit in 1..EmoRepoIpcContract.MAXIMUM_PAGE_SIZE) { "limit 必须为 1..200" }
        val pack = repository.getPack(packId)
        return MatrixCursor(ITEM_COLUMNS).apply {
            pack.records.sortedBy { it.order }.drop(offset).take(limit).forEach { record ->
                val file = repository.imageFile(pack.name, record.name)
                addRow(
                    arrayOf<Any?>(
                        record.md5,
                        record.name,
                        mimeType(record.ext),
                        if (isAnimated(file, record.ext)) 1 else 0,
                        record.order,
                    ),
                )
            }
        }
    }

    private fun requireReadyRepository() {
        check(File(root, ".git").isDirectory) { "EmoRepo 仓库尚未完成初始化" }
    }

    private fun mimeType(extension: String): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"

    private fun isAnimated(file: File, extension: String): Boolean = when (extension.lowercase()) {
        "gif" -> true
        "webp" -> runCatching {
            RandomAccessFile(file, "r").use { input ->
                val bytes = ByteArray(minOf(input.length(), WEBP_ANIMATION_SCAN_BYTES.toLong()).toInt())
                input.readFully(bytes)
                bytes.toString(Charsets.ISO_8859_1).contains("ANIM")
            }
        }.getOrDefault(false)
        else -> false
    }

    private companion object {
        const val MATCH_REVISION = 1
        const val MATCH_PACKS = 2
        const val MATCH_ITEMS = 3
        const val MATCH_ITEM = 4
        const val WEBP_ANIMATION_SCAN_BYTES = 64 * 1024

        val PACK_COLUMNS = arrayOf(
            EmoRepoIpcContract.COLUMN_ID,
            EmoRepoIpcContract.COLUMN_DISPLAY_NAME,
            EmoRepoIpcContract.COLUMN_COVER_ITEM_ID,
            EmoRepoIpcContract.COLUMN_ITEM_COUNT,
            EmoRepoIpcContract.COLUMN_ORDER,
        )
        val ITEM_COLUMNS = arrayOf(
            EmoRepoIpcContract.COLUMN_ID,
            EmoRepoIpcContract.COLUMN_FILE_NAME,
            EmoRepoIpcContract.COLUMN_MIME_TYPE,
            EmoRepoIpcContract.COLUMN_ANIMATED,
            EmoRepoIpcContract.COLUMN_ORDER,
        )
        val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(EmoRepoIpcContract.AUTHORITY, EmoRepoIpcContract.PATH_REVISION, MATCH_REVISION)
            addURI(EmoRepoIpcContract.AUTHORITY, EmoRepoIpcContract.PATH_PACKS, MATCH_PACKS)
            addURI(EmoRepoIpcContract.AUTHORITY, "${EmoRepoIpcContract.PATH_ITEMS}/*", MATCH_ITEMS)
            addURI(EmoRepoIpcContract.AUTHORITY, "${EmoRepoIpcContract.PATH_ITEM}/*/*", MATCH_ITEM)
        }
    }
}
