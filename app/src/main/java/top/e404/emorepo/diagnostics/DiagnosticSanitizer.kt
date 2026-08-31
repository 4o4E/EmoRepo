package top.e404.emorepo.diagnostics

import java.net.URI

object DiagnosticSanitizer {
    private val emailPattern = Regex("(?i)(?<![\\w.+-])[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}(?![\\w.-])")
    private val bearerPattern = Regex("(?i)bearer\\s+[a-z0-9._~+/=-]+")
    private val credentialPattern = Regex(
        "(?i)(token|password|passwd|authorization|secret|access[_-]?token)(\\s*[:=]\\s*)([^\\s,;]+)",
    )
    private val qqIdentifierPattern = Regex("(?i)(uin|qq|group[_-]?id|peer[_-]?id)(\\s*[:=]\\s*)\\d+")
    private val httpsPattern = Regex("https://[^\\s\\\"'<>]+")

    fun sanitize(value: String?, secrets: Collection<String> = emptyList()): String? {
        if (value == null) return null
        var result: String = httpsPattern.replace(value) { match -> sanitizeHttpsUrl(match.value) }
        secrets.asSequence().filter(String::isNotBlank).distinct().forEach { secret ->
            result = result.replace(secret, "[已隐藏]")
        }
        result = bearerPattern.replace(result, "Bearer [已隐藏]")
        result = credentialPattern.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + "[已隐藏]"
        }
        result = qqIdentifierPattern.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + "[已隐藏]"
        }
        return emailPattern.replace(result, "[邮箱已隐藏]")
    }

    fun mostSpecificMessage(error: Throwable, secrets: Collection<String> = emptyList()): String {
        val chain = generateSequence(error) { current -> current.cause }
            .take(16)
            .toList()
        val root = chain.lastOrNull { !it.message.isNullOrBlank() } ?: error
        val type = root::class.java.simpleName.takeIf(String::isNotBlank) ?: "异常"
        val message = sanitize(root.message, secrets)?.takeIf(String::isNotBlank)
        return if (message == null) type else "$type: $message"
    }

    private fun sanitizeHttpsUrl(value: String): String = runCatching {
        val uri = URI(value)
        URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }.getOrElse {
        value.substringBefore('?').substringBefore('#').replace(Regex("//[^/@]+@"), "//")
    }
}
