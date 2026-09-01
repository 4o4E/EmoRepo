package top.e404.emorepo.update

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubUpdateClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `anonymous download follows cross-host redirect and verifies bytes`() {
        val bytes = "verified apk bytes".toByteArray()
        var firstAuthorization: String? = null
        var redirectedAuthorization: String? = null
        val assetServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/asset.apk") { exchange ->
                redirectedAuthorization = exchange.requestHeaders.getFirst("Authorization")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val apiServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/asset") { exchange ->
                firstAuthorization = exchange.requestHeaders.getFirst("Authorization")
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${assetServer.address.port}/asset.apk",
                )
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }

        try {
            val artifact = ReleaseArtifact(
                abi = "universal",
                fileName = "EmoRepo-test-universal.apk",
                size = bytes.size.toLong(),
                sha256 = sha256(bytes),
                downloadUrl = "https://example.invalid/test.apk",
            )
            val candidate = UpdateCandidate(
                versionName = "9.9.9",
                versionCode = 9_009_009,
                releaseUrl = "https://example.invalid/release",
                artifact = artifact,
                assetApiUrl = "http://localhost:${apiServer.address.port}/asset",
            )
            val target = temporaryFolder.newFolder("updates")

            val file = GitHubUpdateClient().download(
                candidate,
                targetDirectory = target,
            ) { _, _ -> }

            assertEquals(null, firstAuthorization)
            assertEquals(null, redirectedAuthorization)
            assertEquals(bytes.toList(), file.readBytes().toList())
            assertFalse(target.listFiles().orEmpty().any { it.extension == "part" })
        } finally {
            apiServer.stop(0)
            assetServer.stop(0)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
