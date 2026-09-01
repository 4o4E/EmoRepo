package top.e404.emorepo.ipc

import android.net.Uri
import top.e404.emorepo.BuildConfig

object EmoRepoIpcContract {
    const val VIRTUAL_RECENT_PACK_ID = "recent"
    const val VIRTUAL_RECENT_PACK_NAME = "最近使用"
    val AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.provider"
    val BASE_URI: Uri = Uri.Builder().scheme("content").authority(AUTHORITY).build()
    const val PATH_REVISION = "revision"
    const val PATH_PANEL_CONFIGURATION = "panel_configuration"
    const val PATH_PACKS = "packs"
    const val PATH_ITEMS = "items"
    const val PATH_ITEM = "item"
    val REVISION_URI: Uri = BASE_URI.buildUpon().appendPath(PATH_REVISION).build()

    const val METHOD_RECORD_USE = "record_use"
    const val METHOD_IMPORT_ITEM = "import_item"
    const val METHOD_IMPORT_ITEMS = "import_items"
    const val METHOD_GET_QQ_LOCATOR_CACHE = "get_qq_locator_cache"
    const val METHOD_PUT_QQ_LOCATOR_CACHE = "put_qq_locator_cache"
    const val METHOD_APPEND_DIAGNOSTIC_LOG = "append_diagnostic_log"
    const val EXTRA_PACK_ID = "pack_id"
    const val EXTRA_ITEM_ID = "item_id"
    const val EXTRA_USED_AT = "used_at"
    const val EXTRA_SOURCE_NAME = "source_name"
    const val EXTRA_SOURCE_DESCRIPTOR = "source_descriptor"
    const val EXTRA_SOURCE_NAMES = "source_names"
    const val EXTRA_SOURCE_DESCRIPTORS = "source_descriptors"
    const val EXTRA_LOCATOR_ID = "locator_id"
    const val EXTRA_LOCATOR_SCHEMA_VERSION = "locator_schema_version"
    const val EXTRA_HOST_VERSION_CODE = "host_version_code"
    const val EXTRA_HOST_APK_LAST_MODIFIED = "host_apk_last_modified"
    const val EXTRA_HOST_APK_LENGTH = "host_apk_length"
    const val EXTRA_LOCATOR_CLASS_NAME = "locator_class_name"
    const val EXTRA_LOG_LEVEL = "log_level"
    const val EXTRA_LOG_COMPONENT = "log_component"
    const val EXTRA_LOG_EVENT = "log_event"
    const val EXTRA_LOG_MESSAGE = "log_message"
    const val EXTRA_LOG_EXCEPTION_TYPE = "log_exception_type"
    const val EXTRA_LOG_EXCEPTION_MESSAGE = "log_exception_message"
    const val EXTRA_LOG_STACK_TRACE = "log_stack_trace"

    const val RESULT_STATUS = "result_status"
    const val RESULT_MESSAGE = "result_message"
    const val RESULT_ITEM_ID = "result_item_id"
    const val RESULT_FILE_NAME = "result_file_name"
    const val RESULT_SUCCESS_COUNT = "result_success_count"
    const val RESULT_DUPLICATE_COUNT = "result_duplicate_count"
    const val RESULT_FAILED_COUNT = "result_failed_count"
    const val RESULT_LOCATOR_CLASS_NAME = "result_locator_class_name"

    const val COLUMN_REVISION = "revision"
    const val COLUMN_ID = "id"
    const val COLUMN_DISPLAY_NAME = "display_name"
    const val COLUMN_COVER_ITEM_ID = "cover_item_id"
    const val COLUMN_COVER_PACK_ID = "cover_pack_id"
    const val COLUMN_ITEM_COUNT = "item_count"
    const val COLUMN_FILE_NAME = "file_name"
    const val COLUMN_MIME_TYPE = "mime_type"
    const val COLUMN_ANIMATED = "animated"
    const val COLUMN_SOURCE_PACK_ID = "source_pack_id"
    const val COLUMN_PANEL_COLUMNS = "panel_columns"
    const val COLUMN_WRITABLE = "writable"
    const val COLUMN_COLLAPSED = "collapsed"

    const val QUERY_OFFSET = "offset"
    const val QUERY_LIMIT = "limit"
    const val MAXIMUM_PAGE_SIZE = 200
}
