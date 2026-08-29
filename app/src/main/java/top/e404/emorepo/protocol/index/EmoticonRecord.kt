package top.e404.emorepo.protocol.index

data class EmoticonRecord(
    val name: String,
    val md5: String,
    val ext: String,
    val time: Long,
    val icon: Boolean = false,
)
