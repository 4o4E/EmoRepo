package top.e404.emorepo.protocol.index

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.ProtocolNames

object IndexJsonlCodec {
    val supportedExtensions: Set<String> = setOf("png", "jpg", "jpeg", "gif", "webp")

    private val fieldNames = setOf("name", "md5", "ext", "time", "icon", "order")
    private val md5Pattern = Regex("[0-9a-f]{32}")
    private val integerPattern = Regex("-?(?:0|[1-9][0-9]*)")
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setStrictness(Strictness.STRICT)
        .create()

    fun decode(content: String): List<EmoticonRecord> {
        val records = content.lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val line = rawLine.removeSuffix("\r")
                if (line.isBlank()) null else decodeLine(line, index + 1)
            }
            .toList()
        validateDocument(records)
        return records
    }

    fun encode(records: List<EmoticonRecord>): String {
        validateDocument(records)
        if (records.isEmpty()) return ""
        return records.joinToString(separator = "\n", postfix = "\n") { record ->
            gson.toJson(record.toJsonObject())
        }
    }

    private fun decodeLine(line: String, lineNumber: Int): EmoticonRecord {
        val element = try {
            val reader = JsonReader(StringReader(line)).apply {
                strictness = Strictness.STRICT
            }
            val parsed = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw ProtocolException("index.jsonl line $lineNumber contains trailing data")
            }
            parsed
        } catch (error: ProtocolException) {
            throw error
        } catch (error: Exception) {
            throw ProtocolException("index.jsonl line $lineNumber is not valid JSON", error)
        }
        if (!element.isJsonObject) {
            throw ProtocolException("index.jsonl line $lineNumber must be a JSON object")
        }
        val objectValue = element.asJsonObject
        val unknownFields = objectValue.keySet() - fieldNames
        if (unknownFields.isNotEmpty()) {
            throw ProtocolException(
                "index.jsonl line $lineNumber has unknown fields: ${unknownFields.sorted().joinToString()}",
            )
        }
        val record = EmoticonRecord(
            name = objectValue.requireString("name", lineNumber),
            md5 = objectValue.requireString("md5", lineNumber),
            ext = objectValue.requireString("ext", lineNumber),
            time = objectValue.requireLong("time", lineNumber),
            icon = objectValue.optionalBoolean("icon", lineNumber),
            order = objectValue.requireLong("order", lineNumber),
        )
        validateRecord(record, "index.jsonl line $lineNumber")
        return record
    }

    private fun validateDocument(records: List<EmoticonRecord>) {
        records.forEachIndexed { index, record ->
            validateRecord(record, "record ${index + 1}")
        }
        val duplicateMd5 = records.groupBy { it.md5 }.entries.firstOrNull { it.value.size > 1 }?.key
        if (duplicateMd5 != null) {
            throw ProtocolException("index.jsonl contains duplicate md5: $duplicateMd5")
        }
        if (records.count { it.icon } > 1) {
            throw ProtocolException("index.jsonl contains more than one explicit icon")
        }
    }

    private fun validateRecord(record: EmoticonRecord, location: String) {
        ProtocolNames.requireSafeSegment(record.name, "$location name")
        if (!md5Pattern.matches(record.md5)) {
            throw ProtocolException("$location md5 must contain 32 lowercase hexadecimal characters")
        }
        if (record.ext !in supportedExtensions) {
            throw ProtocolException("$location ext is not supported: ${record.ext}")
        }
        if (!record.name.endsWith(".${record.ext}", ignoreCase = true)) {
            throw ProtocolException("$location name extension does not match ext")
        }
        if (record.time < 0) {
            throw ProtocolException("$location time must not be negative")
        }
        if (record.order <= 0) {
            throw ProtocolException("$location order must be positive")
        }
    }

    private fun EmoticonRecord.toJsonObject(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("md5", md5)
        addProperty("ext", ext)
        addProperty("time", time)
        if (icon) addProperty("icon", true)
        addProperty("order", order)
    }

    private fun JsonObject.requireString(name: String, lineNumber: Int): String {
        val value = get(name) ?: throw ProtocolException("index.jsonl line $lineNumber is missing $name")
        if (!value.isStringPrimitive()) {
            throw ProtocolException("index.jsonl line $lineNumber $name must be a string")
        }
        return value.asString
    }

    private fun JsonObject.requireLong(name: String, lineNumber: Int): Long {
        val value = get(name) ?: throw ProtocolException("index.jsonl line $lineNumber is missing $name")
        if (!value.isNumberPrimitive() || !integerPattern.matches(value.asString)) {
            throw ProtocolException("index.jsonl line $lineNumber $name must be an integer")
        }
        return try {
            value.asString.toLong()
        } catch (error: NumberFormatException) {
            throw ProtocolException("index.jsonl line $lineNumber $name is outside int64 range", error)
        }
    }

    private fun JsonObject.optionalBoolean(name: String, lineNumber: Int): Boolean {
        val value = get(name) ?: return false
        if (!value.isBooleanPrimitive()) {
            throw ProtocolException("index.jsonl line $lineNumber $name must be a boolean")
        }
        if (!value.asBoolean) {
            throw ProtocolException("index.jsonl line $lineNumber $name must be true or omitted")
        }
        return true
    }

    private fun JsonElement.isStringPrimitive(): Boolean =
        isJsonPrimitive && asJsonPrimitive.isString

    private fun JsonElement.isNumberPrimitive(): Boolean =
        isJsonPrimitive && asJsonPrimitive.isNumber

    private fun JsonElement.isBooleanPrimitive(): Boolean =
        isJsonPrimitive && (asJsonPrimitive as JsonPrimitive).isBoolean
}
