package top.e404.emorepo.ipc

import top.e404.emorepo.BuildConfig

object EmoRepoIpcContract {
    val AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.provider"
    const val PATH_REVISION = "revision"
    const val PATH_PACKS = "packs"
    const val PATH_ITEMS = "items"
    const val PATH_ITEM = "item"

    const val METHOD_RECORD_USE = "record_use"
    const val EXTRA_PACK_ID = "pack_id"
    const val EXTRA_ITEM_ID = "item_id"
    const val EXTRA_USED_AT = "used_at"

    const val COLUMN_REVISION = "revision"
    const val COLUMN_ID = "id"
    const val COLUMN_DISPLAY_NAME = "display_name"
    const val COLUMN_COVER_ITEM_ID = "cover_item_id"
    const val COLUMN_ITEM_COUNT = "item_count"
    const val COLUMN_FILE_NAME = "file_name"
    const val COLUMN_MIME_TYPE = "mime_type"
    const val COLUMN_ANIMATED = "animated"
    const val COLUMN_ORDER = "item_order"

    const val QUERY_OFFSET = "offset"
    const val QUERY_LIMIT = "limit"
    const val MAXIMUM_PAGE_SIZE = 200
}
