package top.e404.emorepo.experiment.lsposed

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean
import top.e404.emorepo.diagnostics.DiagnosticSanitizer

/** EmoRepo 独立 LSPosed 入口，只在 QQ 主进程安装当前产品能力。 */
class EmoRepoXposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != QQ_PACKAGE || param.processName != QQ_PACKAGE) return
        if (!initialized.compareAndSet(false, true)) return

        log("进入 QQ 主进程，安装 EmoRepo 适配")
        QqPanelIntegration.scheduleInstall(param.classLoader)
        runCatching { QqRkeyStore.install(param.classLoader) }
            .onFailure { log("监听 QQ 富媒体 rkey 失败", it) }
        EmoRepoMessageMenuHook.schedulePrewarm(param.classLoader)
        runCatching { EmoRepoMessageMenuHook.install(param.classLoader) }
            .onFailure { log("Hook QQ 图片消息菜单失败", it) }
    }

    private companion object {
        const val TAG = "EmoRepo-LSPosed"
        const val QQ_PACKAGE = "com.tencent.mobileqq"
        val initialized = AtomicBoolean(false)

        fun log(message: String, error: Throwable? = null) {
            val safeMessage = DiagnosticSanitizer.sanitize(message).orEmpty()
            val safeStack = DiagnosticSanitizer.sanitize(error?.stackTraceToString())
            if (safeStack == null) Log.i(TAG, safeMessage) else Log.e(TAG, "$safeMessage\n$safeStack")
            QqDiagnosticBridge.forward(TAG, safeMessage, error)
            if (error == null) {
                XposedBridge.log("[$TAG] $safeMessage")
            } else {
                XposedBridge.log("[$TAG] $safeMessage\n$safeStack")
            }
        }
    }
}
