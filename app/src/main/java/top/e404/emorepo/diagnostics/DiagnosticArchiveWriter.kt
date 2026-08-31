package top.e404.emorepo.diagnostics

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticArchiveWriter {
    fun write(output: OutputStream, diagnosticsJson: String, logFiles: List<File>) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.json"))
            zip.write(diagnosticsJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            logFiles.forEach { file ->
                require(file.isFile && file.name.startsWith(DiagnosticLogStore.CURRENT_FILE_NAME)) {
                    "诊断包包含非法日志文件"
                }
                zip.putNextEntry(ZipEntry("logs/${file.name}"))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
