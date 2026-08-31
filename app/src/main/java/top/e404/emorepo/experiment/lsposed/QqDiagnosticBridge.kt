package top.e404.emorepo.experiment.lsposed

import android.os.Bundle
import android.util.Log
import android.app.AndroidAppHelper
import java.util.concurrent.atomic.AtomicBoolean
import top.e404.emorepo.diagnostics.DiagnosticLogLevel
import top.e404.emorepo.diagnostics.DiagnosticLogger
import top.e404.emorepo.diagnostics.DiagnosticSanitizer
import top.e404.emorepo.ipc.EmoRepoIpcContract

/** 只转发 Hook 诊断事件，不接收聊天、联系人或会话对象。 */
internal object QqDiagnosticBridge {
    private val failureReported = AtomicBoolean(false)

    fun forward(component: String, message: String, error: Throwable? = null) {
        val context = AndroidAppHelper.currentApplication()?.applicationContext ?: return
        val safeMessage = DiagnosticSanitizer.sanitize(message)?.take(DiagnosticLogger.MAXIMUM_MESSAGE_CHARS)
        val safeExceptionMessage = DiagnosticSanitizer.sanitize(error?.message)
            ?.take(DiagnosticLogger.MAXIMUM_MESSAGE_CHARS)
        val safeStack = DiagnosticSanitizer.sanitize(error?.stackTraceToString())
            ?.take(DiagnosticLogger.MAXIMUM_STACK_CHARS)
        runCatching {
            context.contentResolver.call(
                EmoRepoIpcContract.BASE_URI,
                EmoRepoIpcContract.METHOD_APPEND_DIAGNOSTIC_LOG,
                null,
                Bundle().apply {
                    putString(
                        EmoRepoIpcContract.EXTRA_LOG_LEVEL,
                        if (error == null) DiagnosticLogLevel.INFO.name else DiagnosticLogLevel.ERROR.name,
                    )
                    putString(EmoRepoIpcContract.EXTRA_LOG_COMPONENT, component.take(80))
                    putString(EmoRepoIpcContract.EXTRA_LOG_EVENT, "hook_log")
                    putString(EmoRepoIpcContract.EXTRA_LOG_MESSAGE, safeMessage)
                    putString(EmoRepoIpcContract.EXTRA_LOG_EXCEPTION_TYPE, error?.javaClass?.name?.take(240))
                    putString(EmoRepoIpcContract.EXTRA_LOG_EXCEPTION_MESSAGE, safeExceptionMessage)
                    putString(EmoRepoIpcContract.EXTRA_LOG_STACK_TRACE, safeStack)
                },
            )
        }.onFailure { bridgeError ->
            if (failureReported.compareAndSet(false, true)) {
                Log.w(TAG, "QQ Hook 日志无法写入 EmoRepo 文件", bridgeError)
            }
        }
    }

    private const val TAG = "EmoRepo-QQ-Log"
}
