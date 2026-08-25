package top.e404.emorepo.protocol.recent

data class RecentUsageRecord(
    val packageName: String,
    val name: String,
    val time: Long,
)
