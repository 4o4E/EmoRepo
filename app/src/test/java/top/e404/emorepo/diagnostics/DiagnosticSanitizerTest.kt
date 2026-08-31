package top.e404.emorepo.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun `sanitizes credentials email qq identifiers and url parameters`() {
        val secret = "github-secret-value"
        val source = "token=$secret author=user@example.com uin=123456 " +
            "url=https://name:password@example.com/repo.git?access_token=$secret#part"

        val sanitized = requireNotNull(DiagnosticSanitizer.sanitize(source, listOf(secret)))

        assertFalse(sanitized.contains(secret))
        assertFalse(sanitized.contains("user@example.com"))
        assertFalse(sanitized.contains("123456"))
        assertFalse(sanitized.contains("password@"))
        assertFalse(sanitized.contains("?"))
        assertTrue(sanitized.contains("https://example.com/repo.git"))
    }

    @Test
    fun `uses deepest non-empty cause for user error`() {
        val error = IllegalStateException("outer", IllegalArgumentException("actual reason"))

        val message = DiagnosticSanitizer.mostSpecificMessage(error)

        assertTrue(message.contains("IllegalArgumentException"))
        assertTrue(message.contains("actual reason"))
    }
}
