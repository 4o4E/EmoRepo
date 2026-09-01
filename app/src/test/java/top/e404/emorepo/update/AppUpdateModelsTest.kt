package top.e404.emorepo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateModelsTest {
    @Test
    fun `selects first supported ABI and falls back to universal`() {
        val artifacts = listOf(
            artifact("universal"),
            artifact("arm64-v8a"),
            artifact("x86_64"),
        )

        assertEquals("arm64-v8a", selectArtifactForDevice(listOf("arm64-v8a", "armeabi-v7a"), artifacts)?.abi)
        assertEquals("x86_64", selectArtifactForDevice(listOf("x86_64", "x86"), artifacts)?.abi)
        assertEquals("universal", selectArtifactForDevice(listOf("riscv64"), artifacts)?.abi)
    }

    @Test
    fun `validates release metadata and returns matching asset API`() {
        val artifact = artifact("arm64-v8a")
        val candidate = validateUpdateCandidate(
            release = release(artifact),
            index = index(artifact),
            supportedAbis = listOf("arm64-v8a"),
            currentVersionCode = 3999,
            sdkInt = 36,
            expectedApplicationId = "top.e404.emorepo",
            expectedCertificateSha256 = CERTIFICATE,
        )

        requireNotNull(candidate)
        assertEquals("0.4.0", candidate.versionName)
        assertEquals("https://api.github.com/assets/1", candidate.assetApiUrl)
    }

    @Test
    fun `does not offer release with non-increasing version code`() {
        val artifact = artifact("universal")
        assertNull(
            validateUpdateCandidate(
                release(artifact),
                index(artifact),
                listOf("unknown"),
                currentVersionCode = 4000,
                sdkInt = 36,
                expectedApplicationId = "top.e404.emorepo",
                expectedCertificateSha256 = CERTIFICATE,
            ),
        )
    }

    @Test
    fun `rejects mismatched release asset digest`() {
        val artifact = artifact("universal")
        val release = release(artifact).copy(
            assets = listOf(release(artifact).assets.single().copy(digest = "sha256:${"f".repeat(64)}")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateUpdateCandidate(
                release,
                index(artifact),
                listOf("unknown"),
                currentVersionCode = 3000,
                sdkInt = 36,
                expectedApplicationId = "top.e404.emorepo",
                expectedCertificateSha256 = CERTIFICATE,
            )
        }
    }

    private fun artifact(abi: String) = ReleaseArtifact(
        abi = abi,
        fileName = "EmoRepo-0.4.0-$abi.apk",
        size = 1024,
        sha256 = "a".repeat(64),
        downloadUrl = "https://example.invalid/$abi.apk",
    )

    private fun release(artifact: ReleaseArtifact) = GitHubRelease(
        tagName = "v0.4.0",
        draft = false,
        prerelease = false,
        htmlUrl = "https://github.com/4o4E/EmoRepo/releases/tag/v0.4.0",
        assets = listOf(
            GitHubReleaseAsset(
                id = 1,
                name = artifact.fileName,
                size = artifact.size,
                digest = "sha256:${artifact.sha256}",
                url = "https://api.github.com/assets/1",
            ),
        ),
    )

    private fun index(artifact: ReleaseArtifact) = ReleaseIndex(
        schemaVersion = 1,
        applicationId = "top.e404.emorepo",
        channel = "release",
        tag = "v0.4.0",
        versionName = "0.4.0",
        versionCode = 4000,
        minimumSdk = 24,
        commit = "b".repeat(40),
        signingCertificateSha256 = CERTIFICATE,
        artifacts = listOf(artifact),
    )

    private companion object {
        const val CERTIFICATE = "95aea64497d6e79e56a29d77624f876d27e5ad1c7d0fc867932cc7f556268022"
    }
}
