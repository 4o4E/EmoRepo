package top.e404.emorepo.protocol.pack

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.ProtocolNames

object RootIndexJsonlCodec {
    private val fieldNames = setOf("name", "order")
    private val integerPattern = Regex("-?(?:0|[1-9][0-9]*)")
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setStrictness(Strictness.STRICT)
        .create()

    fun decode(content: String): List<PackOrderRecord> {
        val records = content.lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val line = rawLine.removeSuffix("\r")
                if (line.isBlank()) null else decodeLine(line, index + 1)
            }
            .toList()
        validateDocument(records)
        return records
    }

    fun encode(records: List<PackOrderRecord>): String {
        validateDocument(records)
        if (records.isEmpty()) return ""
        return records.joinToString(separator = "\n", postfix = "\n") { record ->
            gson.toJson(JsonObject().apply {
                addProperty("name", record.name)
                addProperty("order", record.order)
            })
        }
    }

    private fun decodeLine(line: String, lineNumber: Int): PackOrderRecord {
        val element = try {
            val reader = JsonReader(StringReader(line)).apply { strictness = Strictness.STRICT }
            val parsed = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw ProtocolException("root index.jsonl line $lineNumber contains trailing data")
            }
            parsed
        } catch (error: ProtocolException) {
            throw error
        } catch (error: Exception) {
            throw ProtocolException("root index.jsonl line $lineNumber is not valid JSON", error)
        }
        if (!element.isJsonObject) {
            throw ProtocolException("root index.jsonl line $lineNumber must be a JSON object")
        }
        val value = element.asJsonObject
        val unknownFields = value.keySet() - fieldNames
        if (unknownFields.isNotEmpty()) {
            throw ProtocolException(
                "root index.jsonl line $lineNumber has unknown fields: ${unknownFields.sorted().joinToString()}",
            )
        }
        return PackOrderRecord(
            name = value.requireString("name", lineNumber),
            order = value.requireLong("order", lineNumber),
        ).also { validateRecord(it, "root index.jsonl line $lineNumber") }
    }

    private fun validateDocument(records: List<PackOrderRecord>) {
        records.forEachIndexed { index, record -> validateRecord(record, "record ${index + 1}") }
        val duplicateName = records.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.key
        if (duplicateName != null) {
            throw ProtocolException("root index.jsonl contains duplicate pack name: $duplicateName")
        }
    }

    private fun validateRecord(record: PackOrderRecord, location: String) {
        ProtocolNames.requireSafeSegment(record.name, "$location name")
        if (record.name == "recent" || record.name == ".git" || record.name.startsWith(".")) {
            throw ProtocolException("$location name is reserved: ${record.name}")
        }
        if (record.order <= 0) {
            throw ProtocolException("$location order must be positive")
        }
    }

    private fun JsonObject.requireString(name: String, lineNumber: Int): String {
        val value = get(name)
            ?: throw ProtocolException("root index.jsonl line $lineNumber is missing $name")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw ProtocolException("root index.jsonl line $lineNumber $name must be a string")
        }
        return value.asString
    }

    private fun JsonObject.requireLong(name: String, lineNumber: Int): Long {
        val value = get(name)
            ?: throw ProtocolException("root index.jsonl line $lineNumber is missing $name")
        if (!value.isNumberPrimitive() || !integerPattern.matches(value.asString)) {
            throw ProtocolException("root index.jsonl line $lineNumber $name must be an integer")
        }
        return try {
            value.asString.toLong()
        } catch (error: NumberFormatException) {
            throw ProtocolException("root index.jsonl line $lineNumber $name is outside int64 range", error)
        }
    }

    private fun JsonElement.isNumberPrimitive(): Boolean =
        isJsonPrimitive && asJsonPrimitive.isNumber
}
