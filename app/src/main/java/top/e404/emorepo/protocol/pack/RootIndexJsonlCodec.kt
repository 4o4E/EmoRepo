package top.e404.emorepo.protocol.pack

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.ProtocolNames

object RootIndexJsonlCodec {
    private val fieldNames = setOf("name", "collapsed")
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setStrictness(Strictness.STRICT)
        .create()

    fun decode(content: String): List<PackIndexRecord> {
        val records = content.lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val line = rawLine.removeSuffix("\r")
                if (line.isBlank()) null else decodeLine(line, index + 1)
            }
            .toList()
        validateDocument(records)
        return records
    }

    fun encode(records: List<PackIndexRecord>): String {
        validateDocument(records)
        if (records.isEmpty()) return ""
        return records.joinToString(separator = "\n", postfix = "\n") { record ->
            gson.toJson(JsonObject().apply {
                addProperty("name", record.name)
                if (record.collapsed) addProperty("collapsed", true)
            })
        }
    }

    private fun decodeLine(line: String, lineNumber: Int): PackIndexRecord {
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
        return PackIndexRecord(
            name = value.requireString("name", lineNumber),
            collapsed = value.optionalBoolean("collapsed", lineNumber),
        ).also { validateRecord(it, "root index.jsonl line $lineNumber") }
    }

    private fun validateDocument(records: List<PackIndexRecord>) {
        records.forEachIndexed { index, record -> validateRecord(record, "record ${index + 1}") }
        val duplicateName = records.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.key
        if (duplicateName != null) {
            throw ProtocolException("root index.jsonl contains duplicate pack name: $duplicateName")
        }
    }

    private fun validateRecord(record: PackIndexRecord, location: String) {
        ProtocolNames.requireSafeSegment(record.name, "$location name")
        if (record.name == "recent" || record.name == ".git" || record.name.startsWith(".")) {
            throw ProtocolException("$location name is reserved: ${record.name}")
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

    private fun JsonObject.optionalBoolean(name: String, lineNumber: Int): Boolean {
        val value = get(name) ?: return false
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean || !value.asBoolean) {
            throw ProtocolException("root index.jsonl line $lineNumber $name must be true or omitted")
        }
        return true
    }

}
