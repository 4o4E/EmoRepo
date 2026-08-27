package top.e404.emorepo.ipc

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import java.security.MessageDigest

internal class CallerVerifier(private val context: Context) {
    fun enforceAllowedCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        val allowed = packages.any { packageName ->
            packageName == QQ_PACKAGE_NAME && hasAllowedSignature(packageName)
        }
        if (!allowed) {
            throw SecurityException("拒绝未授权的 EmoRepo Provider 调用，uid=$uid")
        }
    }

    private fun hasAllowedSignature(packageName: String): Boolean {
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val certificates = (if (Build.VERSION.SDK_INT >= 28) {
            val signingInfo = packageInfo.signingInfo ?: return false
            if (signingInfo.hasPastSigningCertificates()) {
                signingInfo.signingCertificateHistory
            } else {
                signingInfo.apkContentsSigners
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }).orEmpty()
        return certificates.any { signature -> sha256(signature.toByteArray()) in QQ_CERTIFICATES }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val QQ_PACKAGE_NAME = "com.tencent.mobileqq"
        val QQ_CERTIFICATES = setOf(
            "ea6e97ad6c34f7039a9c6daba732c97d0e098e83ede2b4d52c76eb0184ac7a38",
        )
    }
}
