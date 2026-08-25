package top.e404.emorepo.protocol

internal object ProtocolNames {
    fun requireSafeSegment(value: String, field: String) {
        if (
            value.isEmpty() ||
            value == "." ||
            value == ".." ||
            value.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }
        ) {
            throw ProtocolException("$field must be a safe path segment")
        }
    }
}
