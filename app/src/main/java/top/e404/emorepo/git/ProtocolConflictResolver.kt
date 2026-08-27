package top.e404.emorepo.git

import java.nio.charset.StandardCharsets
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.protocol.index.IndexJsonlCodec
import top.e404.emorepo.protocol.pack.PackOrderRecord
import top.e404.emorepo.protocol.pack.RootIndexJsonlCodec
import top.e404.emorepo.protocol.recent.RecentCsvCodec

data class ConflictResolution(
    val content: ByteArray?,
    val warnings: List<String> = emptyList(),
)

object ProtocolConflictResolver {
    fun resolve(
        path: String,
        base: ByteArray?,
        local: ByteArray?,
        remote: ByteArray?,
    ): ConflictResolution = when {
        path == "index.jsonl" -> resolveRootIndex(path, base, local, remote)
        path.endsWith("/index.jsonl") -> resolveIndex(path, base, local, remote)
        path.startsWith("recent/") && path.endsWith(".csv") -> resolveRecent(base, local, remote)
        path.substringAfterLast('.').lowercase() in IndexJsonlCodec.supportedExtensions ->
            resolveImage(path, base, local, remote)
        else -> throw ProtocolException("无法自动解决非协议文件冲突: $path")
    }

    private fun resolveRootIndex(
        path: String,
        baseBytes: ByteArray?,
        localBytes: ByteArray?,
        remoteBytes: ByteArray?,
    ): ConflictResolution {
        val base = decodeRootIndex(baseBytes).associateBy { it.name }
        val local = decodeRootIndex(localBytes).associateBy { it.name }
        val remote = decodeRootIndex(remoteBytes).associateBy { it.name }
        val warnings = mutableListOf<String>()
        val merged = (base.keys + local.keys + remote.keys).mapNotNull { name ->
            mergePackOrderRecord(
                base = base[name],
                local = local[name],
                remote = remote[name],
                onDeleteModify = { warnings += "$path: $name 删除与顺序修改冲突，保留修改并等待目录校验" },
            )
        }
        val normalized = merged
            .sortedWith(compareBy<PackOrderRecord> { it.order }.thenBy { it.name })
            .mapIndexed { index, record -> record.copy(order = (index + 1L) * ORDER_STEP) }
        return ConflictResolution(
            RootIndexJsonlCodec.encode(normalized).toByteArray(StandardCharsets.UTF_8),
            warnings,
        )
    }

    private fun mergePackOrderRecord(
        base: PackOrderRecord?,
        local: PackOrderRecord?,
        remote: PackOrderRecord?,
        onDeleteModify: () -> Unit,
    ): PackOrderRecord? {
        if (local == null && remote == null) return null
        if (local == null) {
            if (remote == base) return null
            if (base != null) onDeleteModify()
            return remote
        }
        if (remote == null) {
            if (local == base) return null
            if (base != null) onDeleteModify()
            return local
        }
        if (local == remote) return local
        if (local == base) return remote
        if (remote == base) return local
        return local
    }

    private fun resolveIndex(
        path: String,
        baseBytes: ByteArray?,
        localBytes: ByteArray?,
        remoteBytes: ByteArray?,
    ): ConflictResolution {
        val base = decodeIndex(baseBytes).associateBy { it.md5 }
        val local = decodeIndex(localBytes).associateBy { it.md5 }
        val remote = decodeIndex(remoteBytes).associateBy { it.md5 }
        val warnings = mutableListOf<String>()
        val merged = (base.keys + local.keys + remote.keys).mapNotNull { md5 ->
            mergeRecord(
                base = base[md5],
                local = local[md5],
                remote = remote[md5],
                onDeleteModify = { warnings += "$path: $md5 删除与修改冲突，保留修改" },
            )
        }
        val localIconChanges = merged.filter { record ->
            record.icon && local[record.md5]?.icon == true && base[record.md5]?.icon != true
        }
        val chosenIcon = (localIconChanges.ifEmpty { merged.filter { it.icon } })
            .minWithOrNull(compareBy<EmoticonRecord> { it.order }.thenBy { it.md5 })
            ?.md5
        val normalized = merged
            .sortedWith(compareBy<EmoticonRecord> { it.order }.thenBy { it.md5 })
            .mapIndexed { index, record ->
                record.copy(
                    icon = record.md5 == chosenIcon,
                    order = (index + 1L) * ORDER_STEP,
                )
            }
        return ConflictResolution(
            content = IndexJsonlCodec.encode(normalized).toByteArray(StandardCharsets.UTF_8),
            warnings = warnings,
        )
    }

    private fun mergeRecord(
        base: EmoticonRecord?,
        local: EmoticonRecord?,
        remote: EmoticonRecord?,
        onDeleteModify: () -> Unit,
    ): EmoticonRecord? {
        if (local == null && remote == null) return null
        if (local == null) {
            if (remote == base) return null
            if (base != null) onDeleteModify()
            return remote
        }
        if (remote == null) {
            if (local == base) return null
            if (base != null) onDeleteModify()
            return local
        }
        if (local == remote) return local
        if (local == base) return remote
        if (remote == base) return local

        return local.copy(
            name = chooseField(base?.name, local.name, remote.name),
            ext = chooseField(base?.ext, local.ext, remote.ext),
            time = maxOf(local.time, remote.time),
            icon = chooseField(base?.icon, local.icon, remote.icon),
            order = local.order,
        )
    }

    private fun <T> chooseField(base: T?, local: T, remote: T): T = when {
        local == remote -> local
        local == base -> remote
        else -> local
    }

    private fun resolveRecent(
        baseBytes: ByteArray?,
        localBytes: ByteArray?,
        remoteBytes: ByteArray?,
    ): ConflictResolution {
        // 读取 base 只用于严格校验冲突三方都仍是合法 CSV，结果按协议合并两侧。
        decodeRecent(baseBytes)
        val merged = RecentCsvCodec.merge(
            decodeRecent(localBytes) + decodeRecent(remoteBytes),
        )
        return ConflictResolution(RecentCsvCodec.encode(merged).toByteArray(StandardCharsets.UTF_8))
    }

    private fun resolveImage(
        path: String,
        base: ByteArray?,
        local: ByteArray?,
        remote: ByteArray?,
    ): ConflictResolution {
        if (local.contentEqualsNullable(remote)) return ConflictResolution(local)
        if (local.contentEqualsNullable(base)) return ConflictResolution(remote)
        if (remote.contentEqualsNullable(base)) return ConflictResolution(local)
        if (local == null && remote != null) return ConflictResolution(remote)
        if (remote == null && local != null) return ConflictResolution(local)
        throw ProtocolException("同一路径图片字节不同，无法自动解决: $path")
    }

    private fun decodeIndex(bytes: ByteArray?): List<EmoticonRecord> =
        if (bytes == null) emptyList()
        else IndexJsonlCodec.decode(String(bytes, StandardCharsets.UTF_8))

    private fun decodeRootIndex(bytes: ByteArray?): List<PackOrderRecord> =
        if (bytes == null) emptyList()
        else RootIndexJsonlCodec.decode(String(bytes, StandardCharsets.UTF_8))

    private fun decodeRecent(bytes: ByteArray?) =
        if (bytes == null) emptyList()
        else RecentCsvCodec.decode(String(bytes, StandardCharsets.UTF_8))

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }

    private const val ORDER_STEP = 1_024L
}
