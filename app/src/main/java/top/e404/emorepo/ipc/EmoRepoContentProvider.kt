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
import top.e404.emorepo.repository.EmoticonPack
import top.e404.emorepo.repository.ImportCandidate
import top.e404.emorepo.repository.ImportLimits
import top.e404.emorepo.repository.ManagementBatchResult
import top.e404.emorepo.repository.ManagementItemResult
import top.e404.emorepo.repository.ManagementStatus
import top.e404.emorepo.repository.RecentUsageRepository
import top.e404.emorepo.repository.readImportBytes
import top.e404.emorepo.protocol.index.EmoticonRecord

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
            MATCH_PANEL_CONFIGURATION -> queryPanelConfiguration()
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
        when (method) {
            EmoRepoIpcContract.METHOD_GET_QQ_LOCATOR_CACHE -> return getQqLocatorCache(extras)
            EmoRepoIpcContract.METHOD_PUT_QQ_LOCATOR_CACHE -> return putQqLocatorCache(extras)
        }
        requireReadyRepository()
        return when (method) {
            EmoRepoIpcContract.METHOD_RECORD_USE -> recordUse(extras)
            EmoRepoIpcContract.METHOD_IMPORT_ITEM -> importItem(extras)
            EmoRepoIpcContract.METHOD_IMPORT_ITEMS -> importItems(extras)
            else -> throw IllegalArgumentException("不支持的 EmoRepo Provider 调用：$method")
        }
    }

    private fun getQqLocatorCache(extras: Bundle?): Bundle {
        val request = parseLocatorCacheRequest(extras, requireClassName = false)
        val preferences = requireNotNull(context).getSharedPreferences(
            QQ_LOCATOR_CACHE_PREFERENCES,
            0,
        )
        val prefix = "${request.locatorId}."
        val matches = preferences.getInt(prefix + CACHE_SCHEMA_VERSION, -1) == request.schemaVersion &&
            preferences.getLong(prefix + CACHE_HOST_VERSION, -1L) == request.hostVersionCode &&
            preferences.getLong(prefix + CACHE_APK_LAST_MODIFIED, -1L) == request.apkLastModified &&
            preferences.getLong(prefix + CACHE_APK_LENGTH, -1L) == request.apkLength
        return Bundle().apply {
            if (matches) {
                putString(
                    EmoRepoIpcContract.RESULT_LOCATOR_CLASS_NAME,
                    preferences.getString(prefix + CACHE_CLASS_NAME, null),
                )
            }
        }
    }

    private fun putQqLocatorCache(extras: Bundle?): Bundle {
        val request = parseLocatorCacheRequest(extras, requireClassName = true)
        val className = requireNotNull(request.className)
        val prefix = "${request.locatorId}."
        val saved = requireNotNull(context).getSharedPreferences(QQ_LOCATOR_CACHE_PREFERENCES, 0)
            .edit()
            .putInt(prefix + CACHE_SCHEMA_VERSION, request.schemaVersion)
            .putLong(prefix + CACHE_HOST_VERSION, request.hostVersionCode)
            .putLong(prefix + CACHE_APK_LAST_MODIFIED, request.apkLastModified)
            .putLong(prefix + CACHE_APK_LENGTH, request.apkLength)
            .putString(prefix + CACHE_CLASS_NAME, className)
            .commit()
        check(saved) { "QQ 定位缓存保存失败" }
        return Bundle.EMPTY
    }

    private fun parseLocatorCacheRequest(
        extras: Bundle?,
        requireClassName: Boolean,
    ): LocatorCacheRequest {
        val values = requireNotNull(extras) { "QQ 定位缓存缺少参数" }
        val locatorId = requireNotNull(values.getString(EmoRepoIpcContract.EXTRA_LOCATOR_ID))
        require(locatorId in ALLOWED_QQ_LOCATOR_IDS) { "不支持的 QQ 定位目标：$locatorId" }
        val schemaVersion = values.getInt(EmoRepoIpcContract.EXTRA_LOCATOR_SCHEMA_VERSION, -1)
        val hostVersionCode = values.getLong(EmoRepoIpcContract.EXTRA_HOST_VERSION_CODE, -1L)
        val apkLastModified = values.getLong(EmoRepoIpcContract.EXTRA_HOST_APK_LAST_MODIFIED, -1L)
        val apkLength = values.getLong(EmoRepoIpcContract.EXTRA_HOST_APK_LENGTH, -1L)
        require(schemaVersion > 0) { "QQ 定位规则版本无效" }
        require(hostVersionCode >= 0L) { "QQ 版本号无效" }
        require(apkLastModified >= 0L && apkLength > 0L) { "QQ APK 指纹无效" }
        val className = values.getString(EmoRepoIpcContract.EXTRA_LOCATOR_CLASS_NAME)
        if (requireClassName) {
            require(!className.isNullOrBlank() && isValidLocatorValue(locatorId, className)) {
                "QQ 定位结果无效"
            }
        }
        return LocatorCacheRequest(
            locatorId = locatorId,
            schemaVersion = schemaVersion,
            hostVersionCode = hostVersionCode,
            apkLastModified = apkLastModified,
            apkLength = apkLength,
            className = className,
        )
    }

    private fun isValidLocatorValue(locatorId: String, value: String): Boolean = when (locatorId) {
        "abstract_qq_custom_menu_item" -> QQ_CLASS_NAME.matches(value)
        else -> value.length <= MAXIMUM_LOCATOR_VALUE_LENGTH &&
            value.startsWith('L') && value.contains(";->") && value.contains('(')
    }

    private fun recordUse(extras: Bundle?): Bundle {
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

    @Suppress("DEPRECATION")
    private fun importItem(extras: Bundle?): Bundle {
        val values = requireNotNull(extras) { "import_item 缺少参数" }
        val packId = requireNotNull(values.getString(EmoRepoIpcContract.EXTRA_PACK_ID))
        val sourceName = values.getString(EmoRepoIpcContract.EXTRA_SOURCE_NAME)
            ?.takeIf(String::isNotBlank) ?: "qq-image"
        val descriptor = requireNotNull(
            values.getParcelable(EmoRepoIpcContract.EXTRA_SOURCE_DESCRIPTOR) as? ParcelFileDescriptor,
        ) { "import_item 缺少原图文件描述符" }
        val result = importCandidates(
            packId,
            listOf(
                ImportCandidate(
                    sourceName,
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        input.readImportBytes(ImportLimits.MAXIMUM_BATCH_BYTES).bytes
                    },
                ),
            ),
        ).items.single()
        return Bundle().apply {
            putString(EmoRepoIpcContract.RESULT_STATUS, result.status.name)
            putString(EmoRepoIpcContract.RESULT_MESSAGE, result.message)
            putString(EmoRepoIpcContract.RESULT_ITEM_ID, result.record?.md5)
            putString(EmoRepoIpcContract.RESULT_FILE_NAME, result.record?.name)
        }
    }

    @Suppress("DEPRECATION")
    private fun importItems(extras: Bundle?): Bundle {
        val values = requireNotNull(extras) { "import_items 缺少参数" }
        val packId = requireNotNull(values.getString(EmoRepoIpcContract.EXTRA_PACK_ID))
        val names = requireNotNull(
            values.getStringArrayList(EmoRepoIpcContract.EXTRA_SOURCE_NAMES),
        ) { "import_items 缺少文件名" }
        val descriptors = requireNotNull(
            values.getParcelableArrayList<ParcelFileDescriptor>(
                EmoRepoIpcContract.EXTRA_SOURCE_DESCRIPTORS,
            ),
        ) { "import_items 缺少原图文件描述符" }
        require(names.isNotEmpty() && names.size == descriptors.size) { "import_items 参数数量不一致" }
        require(names.size <= ImportLimits.MAXIMUM_ITEMS) {
            "单次最多导入 ${ImportLimits.MAXIMUM_ITEMS} 张图片"
        }
        var remainingBytes = ImportLimits.MAXIMUM_BATCH_BYTES
        val itemResults = ArrayList<ManagementItemResult>(names.size)
        try {
            names.indices.forEach { index ->
                val sourceName = names[index].takeIf(String::isNotBlank) ?: "qq-image-$index"
                val item = runCatching {
                    val imported = ParcelFileDescriptor.AutoCloseInputStream(descriptors[index]).use { input ->
                        input.readImportBytes(remainingBytes)
                    }
                    remainingBytes -= imported.size
                    repository.import(packId, listOf(ImportCandidate(sourceName, imported.bytes))).items.single()
                }.getOrElse { error ->
                    ManagementItemResult(
                        source = sourceName,
                        status = ManagementStatus.FAILED,
                        message = error.message,
                    )
                }
                itemResults += item
            }
        } finally {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
        }
        val result = ManagementBatchResult(itemResults).also(::scheduleImportSyncIfNeeded)
        return Bundle().apply {
            putInt(
                EmoRepoIpcContract.RESULT_SUCCESS_COUNT,
                result.items.count { it.status == ManagementStatus.SUCCESS },
            )
            putInt(
                EmoRepoIpcContract.RESULT_DUPLICATE_COUNT,
                result.items.count { it.status == ManagementStatus.DUPLICATE },
            )
            putInt(
                EmoRepoIpcContract.RESULT_FAILED_COUNT,
                result.items.count { it.status == ManagementStatus.FAILED },
            )
        }
    }

    private fun importCandidates(packId: String, candidates: List<ImportCandidate>) =
        repository.import(packId, candidates).also(::scheduleImportSyncIfNeeded)

    private fun scheduleImportSyncIfNeeded(result: ManagementBatchResult) {
        val appContext = requireNotNull(context).applicationContext
        val settings = SettingsStore(appContext).load()
        if (result.items.any { it.status == ManagementStatus.SUCCESS }) {
            GitSyncScheduler.requestAfterModification(appContext)
        }
    }

    override fun getType(uri: Uri): String? = when (URI_MATCHER.match(uri)) {
        MATCH_ITEM -> "image/*"
        MATCH_REVISION, MATCH_PANEL_CONFIGURATION, MATCH_PACKS, MATCH_ITEMS ->
            "vnd.android.cursor.dir/vnd.emorepo.emoticon"
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

    private fun queryPanelConfiguration(): Cursor = MatrixCursor(
        arrayOf(EmoRepoIpcContract.COLUMN_PANEL_COLUMNS),
        1,
    ).apply {
        val settings = SettingsStore(requireNotNull(context).applicationContext).load()
        addRow(arrayOf(settings.qqPanelColumns))
    }

    private fun queryPacks(): Cursor = MatrixCursor(PACK_COLUMNS).apply {
        val packs = repository.listPacks()
        val recentItems = resolveRecentItems(packs)
        // 最近使用是面板虚拟包，不写入根索引，也不改变真实表情包排序。
        addRow(
            arrayOf<Any?>(
                EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID,
                EmoRepoIpcContract.VIRTUAL_RECENT_PACK_NAME,
                recentItems.firstOrNull()?.record?.md5,
                recentItems.firstOrNull()?.packId,
                recentItems.size,
                Long.MIN_VALUE,
                0,
            ),
        )
        packs.forEach { pack ->
            val records = pack.records.sortedBy { it.order }
            addRow(
                arrayOf<Any?>(
                    pack.name,
                    pack.name,
                    records.firstOrNull { it.icon }?.md5 ?: records.firstOrNull()?.md5,
                    pack.name,
                    records.size,
                    pack.order,
                    1,
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
        return MatrixCursor(ITEM_COLUMNS).apply {
            val items = if (packId == EmoRepoIpcContract.VIRTUAL_RECENT_PACK_ID) {
                resolveRecentItems(repository.listPacks()).mapIndexed { index, item ->
                    ProviderItem(item.packId, item.record, (index + 1L) * RECENT_ORDER_STEP)
                }
            } else {
                repository.getPack(packId).records.sortedBy { it.order }.map { record ->
                    ProviderItem(packId, record, record.order)
                }
            }
            items.drop(offset).take(limit).forEach { item ->
                val record = item.record
                val file = repository.imageFile(item.packId, record.name)
                addRow(
                    arrayOf<Any?>(
                        record.md5,
                        record.name,
                        mimeType(record.ext),
                        if (isAnimated(file, record.ext)) 1 else 0,
                        item.order,
                        item.packId,
                    ),
                )
            }
        }
    }

    private fun resolveRecentItems(packs: List<EmoticonPack>): List<RecentItem> {
        val settings = SettingsStore(requireNotNull(context).applicationContext).load()
        val byName = packs.associateBy { it.name }
        return RecentUsageRepository(root, settings.deviceId, settings.recentMaximumRecords)
            .readMerged()
            .mapNotNull { usage ->
                val record = byName[usage.packageName]?.records?.firstOrNull { it.name == usage.name }
                    ?: return@mapNotNull null
                if (!repository.imageFile(usage.packageName, record.name).isFile) return@mapNotNull null
                RecentItem(usage.packageName, record)
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

    private data class LocatorCacheRequest(
        val locatorId: String,
        val schemaVersion: Int,
        val hostVersionCode: Long,
        val apkLastModified: Long,
        val apkLength: Long,
        val className: String?,
    )

    private data class RecentItem(
        val packId: String,
        val record: EmoticonRecord,
    )

    private data class ProviderItem(
        val packId: String,
        val record: EmoticonRecord,
        val order: Long,
    )

    private companion object {
        const val MATCH_REVISION = 1
        const val MATCH_PANEL_CONFIGURATION = 2
        const val MATCH_PACKS = 3
        const val MATCH_ITEMS = 4
        const val MATCH_ITEM = 5
        const val WEBP_ANIMATION_SCAN_BYTES = 64 * 1024
        const val RECENT_ORDER_STEP = 1024L
        const val QQ_LOCATOR_CACHE_PREFERENCES = "qq_locator_cache"
        const val CACHE_SCHEMA_VERSION = "schema_version"
        const val CACHE_HOST_VERSION = "host_version"
        const val CACHE_APK_LAST_MODIFIED = "apk_last_modified"
        const val CACHE_APK_LENGTH = "apk_length"
        const val CACHE_CLASS_NAME = "class_name"

        const val MAXIMUM_LOCATOR_VALUE_LENGTH = 1024
        val ALLOWED_QQ_LOCATOR_IDS = setOf(
            "abstract_qq_custom_menu_item",
            "chat_panel_init",
            "guild_emoji_button_create",
            "panel_icon_layout_update",
            "aio_create",
            "aio_destroy",
        )
        val QQ_CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

        val PACK_COLUMNS = arrayOf(
            EmoRepoIpcContract.COLUMN_ID,
            EmoRepoIpcContract.COLUMN_DISPLAY_NAME,
            EmoRepoIpcContract.COLUMN_COVER_ITEM_ID,
            EmoRepoIpcContract.COLUMN_COVER_PACK_ID,
            EmoRepoIpcContract.COLUMN_ITEM_COUNT,
            EmoRepoIpcContract.COLUMN_ORDER,
            EmoRepoIpcContract.COLUMN_WRITABLE,
        )
        val ITEM_COLUMNS = arrayOf(
            EmoRepoIpcContract.COLUMN_ID,
            EmoRepoIpcContract.COLUMN_FILE_NAME,
            EmoRepoIpcContract.COLUMN_MIME_TYPE,
            EmoRepoIpcContract.COLUMN_ANIMATED,
            EmoRepoIpcContract.COLUMN_ORDER,
            EmoRepoIpcContract.COLUMN_SOURCE_PACK_ID,
        )
        val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(EmoRepoIpcContract.AUTHORITY, EmoRepoIpcContract.PATH_REVISION, MATCH_REVISION)
            addURI(
                EmoRepoIpcContract.AUTHORITY,
                EmoRepoIpcContract.PATH_PANEL_CONFIGURATION,
                MATCH_PANEL_CONFIGURATION,
            )
            addURI(EmoRepoIpcContract.AUTHORITY, EmoRepoIpcContract.PATH_PACKS, MATCH_PACKS)
            addURI(EmoRepoIpcContract.AUTHORITY, "${EmoRepoIpcContract.PATH_ITEMS}/*", MATCH_ITEMS)
            addURI(EmoRepoIpcContract.AUTHORITY, "${EmoRepoIpcContract.PATH_ITEM}/*/*", MATCH_ITEM)
        }
    }
}
