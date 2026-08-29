package top.e404.emorepo.experiment.lsposed

import android.content.Context
import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 安装 QQ 表情按钮入口、会话跟踪和面板调用。 */
internal object QqPanelIntegration {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EmoRepo-Panel-Hook")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduled = AtomicBoolean(false)
    private val installRequested = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)

    fun scheduleInstall(hostClassLoader: ClassLoader) {
        if (!scheduled.compareAndSet(false, true)) return
        log("开始调度 QQ 面板 Hook")
        runCatching {
            XposedBridge.hookAllMethods(
                Instrumentation::class.java,
                "callActivityOnResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.args.firstOrNull() as? Activity ?: return
                        ensureInstalled(activity, hostClassLoader)
                    }
                },
            )
        }.onFailure { error -> log("监听 QQ Activity 恢复失败", error) }
        runCatching {
            val fragmentClass = Class.forName("androidx.fragment.app.Fragment", false, hostClassLoader)
            XposedBridge.hookAllMethods(
                fragmentClass,
                "performResume",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val fragment = param.thisObject ?: return
                        val context = fragment.javaClass.methods.firstOrNull { method ->
                            method.name == "getContext" && method.parameterTypes.isEmpty()
                        }?.invoke(fragment) as? Context ?: return
                        ensureInstalled(context, hostClassLoader)
                    }
                },
            )
        }.onFailure { error -> log("监听 QQ Fragment 恢复失败", error) }
        runCatching {
            val mobileQqClass = Class.forName("mqq.app.MobileQQ", false, hostClassLoader)
            XposedBridge.hookAllMethods(
                mobileQqClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context ?: return
                        ensureInstalled(context, hostClassLoader)
                    }
                },
            )
        }.onFailure { error -> log("监听 QQ Application.onCreate 失败", error) }
        worker.execute {
            repeat(MAXIMUM_APPLICATION_ATTEMPTS) {
                val context = currentHostContext(hostClassLoader)
                if (context != null) {
                    if (installRequested.compareAndSet(false, true)) {
                        install(context.applicationContext ?: context, hostClassLoader)
                    }
                    return@execute
                }
                Thread.sleep(APPLICATION_RETRY_MILLIS)
            }
            log("QQ Application 未建立，无法安装 EmoRepo 面板")
        }
    }

    private fun currentHostContext(hostClassLoader: ClassLoader): Context? {
        val application = runCatching {
            Class.forName("mqq.app.MobileQQ", false, hostClassLoader)
                .getDeclaredField("sMobileQQ")
                .apply { isAccessible = true }
                .get(null) as? android.app.Application
        }.getOrNull()
        if (application != null) return application
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activities = activityThreadClass.getDeclaredField("mActivities")
                .apply { isAccessible = true }
                .get(activityThread) as Map<*, *>
            activities.values.asSequence().mapNotNull { record ->
                record ?: return@mapNotNull null
                generateSequence(record.javaClass) { current -> current.superclass }
                    .mapNotNull { current ->
                        current.declaredFields.firstOrNull { field ->
                            Activity::class.java.isAssignableFrom(field.type)
                        }
                    }
                    .firstOrNull()
                    ?.apply { isAccessible = true }
                    ?.get(record) as? Activity
            }.firstOrNull { activity -> !activity.isFinishing }
        }.getOrNull()
    }


    fun ensureInstalled(context: Context, hostClassLoader: ClassLoader) {
        if (!installRequested.compareAndSet(false, true)) return
        val locatorContext = context.applicationContext ?: context
        worker.execute {
            install(locatorContext, hostClassLoader)
            mainHandler.post { attachExistingEmojiButton(context, hostClassLoader) }
        }
    }

    private fun install(context: Context, hostClassLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        installSessionTracker(context, hostClassLoader)
        installEntryTarget(context, hostClassLoader, QqSymbolLocator.MethodTarget.CHAT_PANEL_INIT)
        installEntryTarget(context, hostClassLoader, QqSymbolLocator.MethodTarget.GUILD_EMOJI_BUTTON_CREATE)
        installEntryTarget(context, hostClassLoader, QqSymbolLocator.MethodTarget.PANEL_ICON_LAYOUT_UPDATE)
    }

    private fun installSessionTracker(context: Context, hostClassLoader: ClassLoader) {
        runCatching {
            val create = QqSymbolLocator.resolveMethod(
                context,
                hostClassLoader,
                QqSymbolLocator.MethodTarget.AIO_CREATE,
                ::log,
            )
            val destroy = QqSymbolLocator.resolveMethod(
                context,
                hostClassLoader,
                QqSymbolLocator.MethodTarget.AIO_DESTROY,
                ::log,
            )
            QqSessionTracker.install(create, destroy)
            log("已安装 QQ 会话跟踪")
        }.onFailure { error -> log("安装 QQ 会话跟踪失败", error) }
    }

    private fun installEntryTarget(
        context: Context,
        hostClassLoader: ClassLoader,
        target: QqSymbolLocator.MethodTarget,
    ) {
        runCatching {
            val method = QqSymbolLocator.resolveMethod(context, hostClassLoader, target, ::log)
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        when (target) {
                            QqSymbolLocator.MethodTarget.CHAT_PANEL_INIT -> {
                                param.thisObject?.let { owner ->
                                    fields(owner).mapNotNull { field ->
                                        runCatching { field.get(owner) as? View }.getOrNull()
                                    }.forEach { view -> attachIfEmojiButton(view, hostClassLoader) }
                                }
                            }
                            QqSymbolLocator.MethodTarget.GUILD_EMOJI_BUTTON_CREATE -> {
                                (param.result as? ViewGroup)?.let { root ->
                                    findViews(root).filterIsInstance<ImageView>()
                                        .forEach { view -> attachIfEmojiButton(view, hostClassLoader) }
                                }
                            }
                            QqSymbolLocator.MethodTarget.PANEL_ICON_LAYOUT_UPDATE -> {
                                (param.thisObject as? ViewGroup)?.let { root ->
                                    findViews(root).filterIsInstance<ImageView>()
                                        .forEach { view -> attachIfEmojiButton(view, hostClassLoader) }
                                }
                            }
                            else -> Unit
                        }
                    }
                },
            )
            log("已安装 QQ 表情按钮入口：${target.locatorId}")
        }.onFailure { error -> log("安装 QQ 表情按钮入口失败：${target.locatorId}", error) }
    }

    private fun attachIfEmojiButton(view: View, hostClassLoader: ClassLoader) {
        val resourceName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        val isEmoji = view.contentDescription?.toString() == EMOJI_CONTENT_DESCRIPTION ||
            resourceName == EMOJI_RESOURCE_NAME ||
            view is ImageButton && resourceName?.contains("emo", ignoreCase = true) == true
        if (!isEmoji) return
        view.setOnLongClickListener { clicked ->
            val contact = QqSessionTracker.currentContact(clicked.context)
            if (contact == null) {
                Toast.makeText(clicked.context, "尚未取得当前 QQ 会话，请重新进入聊天", Toast.LENGTH_SHORT).show()
            } else {
                EmoRepoPanelDialog.show(clicked.context, hostClassLoader, contact)
            }
            true
        }
        log("已绑定 QQ 表情按钮长按入口：${view.javaClass.name} id=$resourceName")
    }

    private fun attachExistingEmojiButton(context: Context, hostClassLoader: ClassLoader) {
        val activity = generateSequence(context) { current ->
            (current as? ContextWrapper)?.baseContext?.takeIf { it !== current }
        }.filterIsInstance<Activity>().firstOrNull() ?: return
        findViews(activity.window.decorView)
            .filterIsInstance<ImageView>()
            .forEach { view -> attachIfEmojiButton(view, hostClassLoader) }
    }

    private fun fields(target: Any): Sequence<Field> =
        generateSequence(target.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .onEach { field -> field.isAccessible = true }

    private fun findViews(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(findViews(root.getChildAt(index)))
        }
    }

    fun log(message: String, error: Throwable? = null) {
        Log.i(TAG, message, error)
        if (error == null) {
            XposedBridge.log("[$TAG] $message")
        } else {
            XposedBridge.log("[$TAG] $message\n${Log.getStackTraceString(error)}")
        }
    }

    private const val TAG = "EmoRepo-QQ-Panel"
    private const val EMOJI_CONTENT_DESCRIPTION = "表情"
    private const val EMOJI_RESOURCE_NAME = "emo_btn"
    private const val MAXIMUM_APPLICATION_ATTEMPTS = 50
    private const val APPLICATION_RETRY_MILLIS = 200L
}
