package top.e404.emorepo.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID
import org.eclipse.jgit.api.Git
import top.e404.emorepo.BuildConfig
import top.e404.emorepo.config.SettingsStore

object DiagnosticExporter {
    fun export(context: Context, destination: Uri) {
        DiagnosticLogger.info("diagnostics", "export_started")
        val snapshot = File(context.cacheDir, "diagnostic-export-${UUID.randomUUID()}")
        try {
            val logs = DiagnosticLogger.snapshot(snapshot)
            val diagnostics = diagnostics(context)
            context.contentResolver.openOutputStream(destination, "w").use { output ->
                requireNotNull(output) { "无法打开日志导出位置" }
                DiagnosticArchiveWriter.write(output, diagnostics, logs)
            }
            DiagnosticLogger.info(
                component = "diagnostics",
                event = "export_succeeded",
                fields = mapOf("logFileCount" to logs.size),
            )
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = "diagnostics",
                event = "export_failed",
                message = "导出诊断日志失败",
                error = error,
            )
            throw error
        } finally {
            snapshot.deleteRecursively()
        }
    }

    private fun diagnostics(context: Context): String {
        val root = File(context.filesDir, "repository")
        val git = runCatching {
            Git.open(root).use { opened ->
                val repository = opened.repository
                mapOf(
                    "valid" to true,
                    "branch" to repository.branch,
                    "head" to repository.resolve("HEAD")?.name,
                    "state" to repository.repositoryState.name,
                    "indexLockExists" to File(repository.directory, "index.lock").isFile,
                )
            }
        }.getOrElse { error ->
            mapOf(
                "valid" to false,
                "error" to DiagnosticSanitizer.mostSpecificMessage(error),
            )
        }
        val sync = SettingsStore(context).loadSyncStatus()
        val value = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "exportedAt" to diagnosticTimestamp(),
            "applicationId" to BuildConfig.APPLICATION_ID,
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "androidSdk" to Build.VERSION.SDK_INT,
            "deviceManufacturer" to Build.MANUFACTURER,
            "deviceModel" to Build.MODEL,
            "syncPhase" to sync.phase.name,
            "lastSyncSuccessTime" to sync.lastSuccessTime,
            "lastSyncError" to DiagnosticSanitizer.sanitize(sync.lastError),
            "git" to git,
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(value)
    }
}
