package top.e404.emorepo.diagnostics

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class DiagnosticLogLevel { DEBUG, INFO, WARN, ERROR }

object DiagnosticLogger {
    private const val TAG = "EmoRepo"
    private val initializationLock = Any()
    private val fileFailureReported = AtomicBoolean(false)

    @Volatile
    private var store: DiagnosticLogStore? = null

    @Volatile
    private var processName: String = "unknown"

    fun initialize(context: Context) {
        if (store != null) return
        synchronized(initializationLock) {
            if (store != null) return
            val appContext = context.applicationContext
            processName = resolveProcessName(appContext)
            store = DiagnosticLogStore(File(appContext.filesDir, "logs"))
            installUncaughtExceptionHandler()
        }
        info(
            component = "application",
            event = "logger_initialized",
            fields = mapOf("sdk" to Build.VERSION.SDK_INT.toString()),
        )
    }

    fun debug(component: String, event: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) =
        write(DiagnosticLogLevel.DEBUG, component, event, message, fields)

    fun info(component: String, event: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) =
        write(DiagnosticLogLevel.INFO, component, event, message, fields)

    fun warn(
        component: String,
        event: String,
        message: String? = null,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
        secrets: Collection<String> = emptyList(),
    ) = write(DiagnosticLogLevel.WARN, component, event, message, fields, error, secrets)

    fun error(
        component: String,
        event: String,
        message: String? = null,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
        secrets: Collection<String> = emptyList(),
    ) = write(DiagnosticLogLevel.ERROR, component, event, message, fields, error, secrets)

    fun external(
        level: DiagnosticLogLevel,
        component: String,
        event: String,
        message: String?,
        exceptionType: String?,
        exceptionMessage: String?,
        stackTrace: String?,
    ) {
        writeEvent(
            level = level,
            component = component,
            event = event,
            message = message,
            fields = emptyMap(),
            exceptionType = exceptionType,
            exceptionMessage = exceptionMessage,
            stackTrace = stackTrace,
            sourceProcess = "qq",
        )
    }

    fun snapshot(destination: File): List<File> = requireNotNull(store) {
        "诊断日志尚未初始化"
    }.snapshot(destination)

    private fun write(
        level: DiagnosticLogLevel,
        component: String,
        event: String,
        message: String?,
        fields: Map<String, Any?>,
        error: Throwable? = null,
        secrets: Collection<String> = emptyList(),
    ) {
        val sanitizedStack = error?.stackTraceToString()?.let { DiagnosticSanitizer.sanitize(it, secrets) }
        writeEvent(
            level = level,
            component = component,
            event = event,
            message = DiagnosticSanitizer.sanitize(message, secrets),
            fields = fields.mapValues { (_, value) ->
                DiagnosticSanitizer.sanitize(value?.toString(), secrets).orEmpty()
            },
            exceptionType = error?.javaClass?.name,
            exceptionMessage = DiagnosticSanitizer.sanitize(error?.message, secrets),
            stackTrace = sanitizedStack,
            sourceProcess = processName,
        )
    }

    private fun writeEvent(
        level: DiagnosticLogLevel,
        component: String,
        event: String,
        message: String?,
        fields: Map<String, String>,
        exceptionType: String?,
        exceptionMessage: String?,
        stackTrace: String?,
        sourceProcess: String,
    ) {
        val safeComponent = DiagnosticSanitizer.sanitize(component)?.take(80).orEmpty()
        val safeEvent = DiagnosticSanitizer.sanitize(event)?.take(80).orEmpty()
        val safeMessage = DiagnosticSanitizer.sanitize(message)?.take(MAXIMUM_MESSAGE_CHARS)
        val safeExceptionMessage = DiagnosticSanitizer.sanitize(exceptionMessage)?.take(MAXIMUM_MESSAGE_CHARS)
        val safeStack = DiagnosticSanitizer.sanitize(stackTrace)?.take(MAXIMUM_STACK_CHARS)
        val summary = buildString {
            append("[$safeComponent/$safeEvent]")
            if (!safeMessage.isNullOrBlank()) append(' ').append(safeMessage)
            if (!safeExceptionMessage.isNullOrBlank()) append(" | ").append(safeExceptionMessage)
        }
        Log.println(level.logPriority(), TAG, summary)
        val eventValue = DiagnosticLogEvent(
            timestamp = diagnosticTimestamp(),
            level = level.name,
            process = sourceProcess,
            thread = Thread.currentThread().name.take(120),
            component = safeComponent,
            event = safeEvent,
            message = safeMessage,
            fields = fields,
            exceptionType = exceptionType?.take(240),
            exceptionMessage = safeExceptionMessage,
            stackTrace = safeStack,
        )
        runCatching { store?.append(eventValue) }
            .onFailure { fileError ->
                if (fileFailureReported.compareAndSet(false, true)) {
                    Log.e(TAG, "诊断日志文件写入失败，后续仅保留 logcat", fileError)
                }
            }
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is LoggingUncaughtExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(LoggingUncaughtExceptionHandler(previous))
    }

    private fun resolveProcessName(context: Context): String = if (Build.VERSION.SDK_INT >= 28) {
        Application.getProcessName()
    } else {
        runCatching { File("/proc/${Process.myPid()}/cmdline").readText().trimEnd('\u0000') }
            .getOrDefault(context.packageName)
    }

    private class LoggingUncaughtExceptionHandler(
        private val delegate: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            DiagnosticLogger.error(
                component = "application",
                event = "uncaught_exception",
                message = "进程发生未捕获异常",
                fields = mapOf("crashedThread" to thread.name),
                error = error,
            )
            delegate?.uncaughtException(thread, error)
        }
    }

    private fun DiagnosticLogLevel.logPriority(): Int = when (this) {
        DiagnosticLogLevel.DEBUG -> Log.DEBUG
        DiagnosticLogLevel.INFO -> Log.INFO
        DiagnosticLogLevel.WARN -> Log.WARN
        DiagnosticLogLevel.ERROR -> Log.ERROR
    }

    const val MAXIMUM_MESSAGE_CHARS = 4_000
    const val MAXIMUM_STACK_CHARS = 32_000
}
