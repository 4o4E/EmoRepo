package top.e404.emorepo.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.Json

internal class GitHubUpdateClient(
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun latestRelease(): Pair<GitHubRelease, ReleaseIndex> {
        val release = json.decodeFromString<GitHubRelease>(
            readBytes(LATEST_RELEASE_API, GITHUB_JSON, MAXIMUM_RELEASE_JSON_BYTES).decodeToString(),
        )
        val indexAsset = release.assets.firstOrNull { it.name == RELEASE_INDEX_NAME }
            ?: error("GitHub Release 缺少 $RELEASE_INDEX_NAME")
        val indexBytes = readBytes(indexAsset.url, BINARY_CONTENT, MAXIMUM_INDEX_BYTES)
        verifyOptionalDigest(indexBytes, indexAsset.digest, RELEASE_INDEX_NAME)
        val index = json.decodeFromString<ReleaseIndex>(indexBytes.decodeToString())
        return release to index
    }

    fun clearCachedUpdates(targetDirectory: File) {
        targetDirectory.mkdirs()
        if (!targetDirectory.isDirectory) return
        targetDirectory.listFiles().orEmpty().filter { file ->
            file.isFile && (file.extension == "part" || file.extension == "apk")
        }.forEach(File::delete)
    }

    fun download(
        candidate: UpdateCandidate,
        targetDirectory: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        prepareTargetDirectory(targetDirectory)
        val finalFile = File(targetDirectory, candidate.artifact.fileName).canonicalFile
        require(finalFile.parentFile == targetDirectory.canonicalFile) { "更新文件路径越界" }
        val partialFile = File(targetDirectory, "${candidate.artifact.fileName}.part").canonicalFile
        require(partialFile.parentFile == targetDirectory.canonicalFile) { "更新临时文件路径越界" }
        partialFile.delete()
        finalFile.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var downloaded = 0L
        try {
            open(candidate.assetApiUrl, BINARY_CONTENT).use { response ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = response.input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        require(downloaded <= candidate.artifact.size && downloaded <= MAXIMUM_APK_BYTES) {
                            "更新 APK 超过声明大小"
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(downloaded, candidate.artifact.size)
                    }
                    output.fd.sync()
                }
            }
            require(downloaded == candidate.artifact.size) { "更新 APK 下载大小不完整" }
            val actualSha256 = digest.digest().toHex()
            require(actualSha256 == candidate.artifact.sha256) { "更新 APK SHA-256 校验失败" }
            require(partialFile.renameTo(finalFile)) { "无法完成更新 APK 原子写入" }
            return finalFile
        } catch (error: Throwable) {
            partialFile.delete()
            finalFile.delete()
            throw error
        }
    }

    private fun readBytes(url: String, accept: String, maximum: Int): ByteArray =
        open(url, accept).use { response ->
            val output = ByteArrayOutputStream(minOf(response.contentLength.coerceAtLeast(0), maximum))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = response.input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maximum) { "GitHub 更新元数据过大" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }

    private fun open(url: String, accept: String): HttpResponse {
        var current = URI(url).toURL()
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            require(current.protocol == "https" || current.host in LOCAL_TEST_HOSTS) {
                "更新请求只允许 HTTPS"
            }
            val connection = connectionFactory(current)
            require(connection is HttpsURLConnection || current.host in LOCAL_TEST_HOSTS) {
                "更新请求必须使用 HTTPS 连接"
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "EmoRepo-Android-Updater")
            if (current.host.equals(GITHUB_API_HOST, ignoreCase = true)) {
                connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(redirectCount < MAXIMUM_REDIRECTS && !location.isNullOrBlank()) {
                    "GitHub 更新下载重定向无效"
                }
                current = current.toURI().resolve(location).toURL()
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IllegalStateException(
                    when (code) {
                        401, 403, 404 -> "GitHub 更新源不可用、请求受限或最新 Release 不存在"
                        else -> "GitHub 更新请求失败：HTTP $code"
                    },
                )
            }
            return HttpResponse(connection)
        }
        error("GitHub 更新下载重定向次数过多")
    }

    private fun prepareTargetDirectory(directory: File) {
        directory.mkdirs()
        require(directory.isDirectory) { "无法创建更新缓存目录" }
        clearCachedUpdates(directory)
    }

    private fun verifyOptionalDigest(bytes: ByteArray, digest: String?, name: String) {
        val expected = digest?.removePrefix("sha256:")?.takeIf(String::isNotBlank) ?: return
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        require(actual.equals(expected, ignoreCase = true)) { "$name 的 GitHub 摘要校验失败" }
    }

    private class HttpResponse(private val connection: HttpURLConnection) : AutoCloseable {
        val input = connection.inputStream
        val contentLength: Int = connection.contentLength
        override fun close() {
            try {
                input.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/4o4E/EmoRepo/releases/latest"
        const val RELEASE_INDEX_NAME = "release-index.json"
        const val GITHUB_API_HOST = "api.github.com"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val GITHUB_JSON = "application/vnd.github+json"
        const val BINARY_CONTENT = "application/octet-stream"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAXIMUM_REDIRECTS = 5
        const val MAXIMUM_RELEASE_JSON_BYTES = 512 * 1024
        const val MAXIMUM_INDEX_BYTES = 128 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val LOCAL_TEST_HOSTS = setOf("localhost", "127.0.0.1")
    }
}
