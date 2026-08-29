package top.e404.emorepo.repository

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.StringReader
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.index.EmoticonRecord
import top.e404.emorepo.protocol.index.IndexJsonlCodec
import top.e404.emorepo.protocol.pack.PackIndexRecord
import top.e404.emorepo.protocol.pack.RootIndexJsonlCodec

/** 把当前已发布的 order 字段协议一次性迁移为 JSONL 行序协议。 */
internal object LegacyOrderMigration {
    private val integerPattern = Regex("-?(?:0|[1-9][0-9]*)")
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create()

    fun migratePack(file: File): Boolean = migrate(file, "index.jsonl") { ordered ->
        val records = ordered
            .map { entry ->
                entry.order to IndexJsonlCodec.decode(gson.toJson(entry.value) + "\n").single()
            }
            .sortedWith(compareBy<Pair<Long, EmoticonRecord>> { it.first }
                .thenBy { it.second.md5 })
            .map { it.second }
        IndexJsonlCodec.encode(records)
    }

    fun migrateRoot(file: File): Boolean = migrate(file, "root index.jsonl") { ordered ->
        val records = ordered
            .map { entry ->
                entry.order to RootIndexJsonlCodec.decode(gson.toJson(entry.value) + "\n").single()
            }
            .sortedWith(compareBy<Pair<Long, PackIndexRecord>> { it.first }
                .thenBy { it.second.name })
            .map { it.second }
        RootIndexJsonlCodec.encode(records)
    }

    private fun migrate(
        file: File,
        label: String,
        encode: (List<OrderedObject>) -> String,
    ): Boolean {
        if (!file.isFile) return false
        val lines = AtomicFileStore.readText(file).lineSequence()
            .mapIndexedNotNull { index, raw ->
                val line = raw.removeSuffix("\r")
                if (line.isBlank()) null else index + 1 to line
            }
            .toList()
        if (lines.isEmpty()) return false
        val objects = lines.map { (lineNumber, line) -> parseObject(line, lineNumber, label) }
        val legacyCount = objects.count { it.has("order") }
        if (legacyCount == 0) return false
        if (legacyCount != objects.size) {
            throw ProtocolException("$label mixes legacy order records with line-order records")
        }
        val ordered = objects.mapIndexed { index, value ->
            val orderValue = value.remove("order")
            if (orderValue == null || !orderValue.isJsonPrimitive || !orderValue.asJsonPrimitive.isNumber) {
                throw ProtocolException("$label line ${lines[index].first} order must be an integer")
            }
            val token = orderValue.asString
            if (!integerPattern.matches(token)) {
                throw ProtocolException("$label line ${lines[index].first} order must be an integer")
            }
            val order = token.toLongOrNull()
                ?: throw ProtocolException("$label line ${lines[index].first} order is outside int64 range")
            if (order <= 0) throw ProtocolException("$label line ${lines[index].first} order must be positive")
            OrderedObject(order, value)
        }
        AtomicFileStore.writeText(file, encode(ordered))
        return true
    }

    private fun parseObject(line: String, lineNumber: Int, label: String): JsonObject {
        val element = try {
            val reader = JsonReader(StringReader(line)).apply { strictness = Strictness.STRICT }
            val parsed = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw ProtocolException("$label line $lineNumber contains trailing data")
            }
            parsed
        } catch (error: ProtocolException) {
            throw error
        } catch (error: Exception) {
            throw ProtocolException("$label line $lineNumber is not valid JSON", error)
        }
        if (!element.isJsonObject) throw ProtocolException("$label line $lineNumber must be a JSON object")
        return element.asJsonObject
    }

    private data class OrderedObject(
        val order: Long,
        val value: JsonObject,
    )
}
