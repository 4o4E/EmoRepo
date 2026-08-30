package top.e404.emorepo.repository

import top.e404.emorepo.protocol.index.EmoticonRecord

data class EmoticonPack(
    val name: String,
    val records: List<EmoticonRecord>,
    val collapsed: Boolean = false,
)

data class ImportCandidate(
    val sourceName: String,
    val bytes: ByteArray,
)

enum class ManagementStatus {
    SUCCESS,
    DUPLICATE,
    FAILED,
}

data class ManagementItemResult(
    val source: String,
    val status: ManagementStatus,
    val record: EmoticonRecord? = null,
    val deduplicated: Boolean = false,
    val message: String? = null,
)

data class ManagementBatchResult(
    val items: List<ManagementItemResult>,
) {
    val succeeded: Int get() = items.count { it.status == ManagementStatus.SUCCESS }
    val duplicated: Int get() = items.count { it.status == ManagementStatus.DUPLICATE }
    val failed: Int get() = items.count { it.status == ManagementStatus.FAILED }
}
