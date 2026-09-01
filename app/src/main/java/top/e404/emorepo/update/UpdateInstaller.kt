package top.e404.emorepo.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import top.e404.emorepo.BuildConfig

internal object UpdateInstaller {
    fun currentSignatureMatchesRelease(context: Context): Boolean {
        val info = context.packageManager.getPackageInfoCompat(context.packageName)
        return info.signerDigests().contains(BuildConfig.RELEASE_SIGNING_CERTIFICATE_SHA256)
    }

    @Suppress("DEPRECATION")
    fun verifyDownloadedApk(context: Context, apk: File) {
        require(apk.isFile && apk.length() > 0L) { "更新 APK 不存在" }
        val info = requireNotNull(
            context.packageManager.getPackageArchiveInfoCompat(apk.absolutePath),
        ) { "Android 无法解析更新 APK" }
        require(info.packageName == BuildConfig.APPLICATION_ID) { "更新 APK 包名不匹配" }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(archiveVersionCode > BuildConfig.VERSION_CODE) { "更新 APK 版本没有提高" }
        require(info.signerDigests().contains(BuildConfig.RELEASE_SIGNING_CERTIFICATE_SHA256)) {
            "更新 APK 签名证书不匹配"
        }
    }

    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    @Suppress("DEPRECATION")
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.files",
            apk,
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= 28) {
            getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageArchiveInfoCompat(path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= 28) {
            getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerDigests(): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            val details = signingInfo ?: return emptySet()
            if (details.hasMultipleSigners()) details.apkContentsSigners else details.signingCertificateHistory
        } else {
            signatures.orEmpty()
        }
        return signers.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
