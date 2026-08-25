package top.e404.emorepo.protocol.recent

import top.e404.emorepo.protocol.ProtocolException
import top.e404.emorepo.protocol.ProtocolNames

object RecentCsvCodec {
    private val header = listOf("package", "name", "time")
    private val integerPattern = Regex("(?:0|[1-9][0-9]*)")

    fun decode(content: String): List<RecentUsageRecord> {
        val rows = parseRows(content)
        if (rows.isEmpty() || rows.first() != header) {
            throw ProtocolException("recent CSV must start with package,name,time")
        }
        val records = rows.drop(1).mapIndexed { index, fields ->
            val rowNumber = index + 2
            if (fields.size != 3) {
                throw ProtocolException("recent CSV row $rowNumber must contain exactly 3 fields")
            }
            val packageName = fields[0]
            val name = fields[1]
            ProtocolNames.requireSafeSegment(packageName, "recent CSV row $rowNumber package")
            ProtocolNames.requireSafeSegment(name, "recent CSV row $rowNumber name")
            if (!integerPattern.matches(fields[2])) {
                throw ProtocolException("recent CSV row $rowNumber time must be a non-negative integer")
            }
            val time = try {
                fields[2].toLong()
            } catch (error: NumberFormatException) {
                throw ProtocolException("recent CSV row $rowNumber time is outside int64 range", error)
            }
            RecentUsageRecord(packageName, name, time)
        }
        return merge(records)
    }

    fun encode(records: Collection<RecentUsageRecord>): String {
        val rows = buildList {
            add(header)
            merge(records).forEach { record ->
                add(listOf(record.packageName, record.name, record.time.toString()))
            }
        }
        return rows.joinToString(separator = "\n", postfix = "\n") { fields ->
            fields.joinToString(",", transform = ::encodeField)
        }
    }

    fun merge(records: Collection<RecentUsageRecord>): List<RecentUsageRecord> {
        val newestByIdentity = mutableMapOf<Pair<String, String>, RecentUsageRecord>()
        records.forEach { record ->
            validateRecord(record)
            val key = record.packageName to record.name
            val current = newestByIdentity[key]
            if (current == null || record.time > current.time) {
                newestByIdentity[key] = record
            }
        }
        return newestByIdentity.values.sortedWith(
            compareByDescending<RecentUsageRecord> { it.time }
                .thenBy { it.packageName }
                .thenBy { it.name },
        )
    }

    private fun validateRecord(record: RecentUsageRecord) {
        ProtocolNames.requireSafeSegment(record.packageName, "recent package")
        ProtocolNames.requireSafeSegment(record.name, "recent name")
        if (record.time < 0) {
            throw ProtocolException("recent time must not be negative")
        }
    }

    private fun encodeField(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            return value
        }
        return buildString {
            append('"')
            value.forEach { character ->
                if (character == '"') append('"')
                append(character)
            }
            append('"')
        }
    }

    private fun parseRows(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var quoteClosed = false
        var index = 0

        fun finishField() {
            row += field.toString()
            field.setLength(0)
            quoteClosed = false
        }

        fun finishRow() {
            finishField()
            rows += row.toList()
            row.clear()
        }

        while (index < content.length) {
            val character = content[index]
            if (inQuotes) {
                if (character == '"') {
                    if (index + 1 < content.length && content[index + 1] == '"') {
                        field.append('"')
                        index += 2
                        continue
                    }
                    inQuotes = false
                    quoteClosed = true
                } else {
                    field.append(character)
                }
                index++
                continue
            }

            if (quoteClosed && character != ',' && character != '\r' && character != '\n') {
                throw ProtocolException("recent CSV has data after a closing quote")
            }

            when (character) {
                '"' -> {
                    if (field.isNotEmpty()) {
                        throw ProtocolException("recent CSV quote must start at the beginning of a field")
                    }
                    inQuotes = true
                }
                ',' -> finishField()
                '\n' -> finishRow()
                '\r' -> {
                    if (index + 1 < content.length && content[index + 1] == '\n') {
                        index++
                    }
                    finishRow()
                }
                else -> field.append(character)
            }
            index++
        }

        if (inQuotes) {
            throw ProtocolException("recent CSV has an unterminated quoted field")
        }
        if (field.isNotEmpty() || row.isNotEmpty() || quoteClosed) {
            finishRow()
        }
        return rows
    }
}
