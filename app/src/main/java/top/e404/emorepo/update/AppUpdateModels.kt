package top.e404.emorepo.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    @SerialName("html_url") val htmlUrl: String,
    val assets: List<GitHubReleaseAsset>,
)

@Serializable
internal data class GitHubReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val digest: String? = null,
    val url: String,
)

@Serializable
internal data class ReleaseIndex(
    val schemaVersion: Int,
    val applicationId: String,
    val channel: String,
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val minimumSdk: Int,
    val commit: String,
    val signingCertificateSha256: String,
    val artifacts: List<ReleaseArtifact>,
)

@Serializable
internal data class ReleaseArtifact(
    val abi: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val downloadUrl: String,
)

internal data class UpdateCandidate(
    val versionName: String,
    val versionCode: Int,
    val releaseUrl: String,
    val artifact: ReleaseArtifact,
    val assetApiUrl: String,
)

internal fun selectArtifactForDevice(
    supportedAbis: List<String>,
    artifacts: List<ReleaseArtifact>,
): ReleaseArtifact? {
    supportedAbis.forEach { abi -> artifacts.firstOrNull { it.abi == abi }?.let { return it } }
    return artifacts.firstOrNull { it.abi == UNIVERSAL_ABI }
}

internal fun validateUpdateCandidate(
    release: GitHubRelease,
    index: ReleaseIndex,
    supportedAbis: List<String>,
    currentVersionCode: Int,
    sdkInt: Int,
    expectedApplicationId: String,
    expectedCertificateSha256: String,
): UpdateCandidate? {
    require(!release.draft && !release.prerelease) { "最新 GitHub Release 不是稳定版本" }
    require(index.schemaVersion == 1) { "更新索引版本不受支持" }
    require(index.applicationId == expectedApplicationId) { "更新包应用 ID 不匹配" }
    require(index.channel == "release") { "更新索引不是正式渠道" }
    require(index.tag == release.tagName) { "Release 标签与更新索引不一致" }
    require(index.versionCode > 0 && index.versionName.isNotBlank()) { "更新版本无效" }
    require(index.minimumSdk <= sdkInt) { "新版本要求 Android ${index.minimumSdk} 或更高" }
    require(index.commit.matches(Regex("[0-9a-f]{40}"))) { "更新提交哈希无效" }
    require(index.signingCertificateSha256.equals(expectedCertificateSha256, ignoreCase = true)) {
        "更新索引中的签名证书不匹配"
    }
    if (index.versionCode <= currentVersionCode) return null
    val artifact = requireNotNull(selectArtifactForDevice(supportedAbis, index.artifacts)) {
        "没有适合当前设备架构的 APK"
    }
    require(artifact.fileName.endsWith(".apk") && artifact.size in 1..MAXIMUM_APK_BYTES) {
        "更新 APK 文件信息无效"
    }
    require(artifact.sha256.matches(Regex("[0-9a-f]{64}"))) { "更新 APK SHA-256 无效" }
    val asset = release.assets.firstOrNull { it.name == artifact.fileName }
        ?: error("GitHub Release 缺少 ${artifact.fileName}")
    require(asset.size == artifact.size) { "GitHub asset 大小与更新索引不一致" }
    asset.digest?.removePrefix("sha256:")?.takeIf(String::isNotBlank)?.let { digest ->
        require(digest.equals(artifact.sha256, ignoreCase = true)) {
            "GitHub asset 摘要与更新索引不一致"
        }
    }
    return UpdateCandidate(
        versionName = index.versionName,
        versionCode = index.versionCode,
        releaseUrl = release.htmlUrl,
        artifact = artifact,
        assetApiUrl = asset.url,
    )
}

internal const val MAXIMUM_APK_BYTES = 256L * 1024L * 1024L
private const val UNIVERSAL_ABI = "universal"
