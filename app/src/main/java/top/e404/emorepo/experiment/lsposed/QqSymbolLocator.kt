package top.e404.emorepo.experiment.lsposed

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import top.e404.emorepo.BuildConfig
import top.e404.emorepo.ipc.EmoRepoIpcContract

/**
 * 独立执行 QQ 混淆符号定位；目标特征参考 QAux 已验证规则，但不依赖其运行时或代码。
 */
internal object QqSymbolLocator {
    private val resolveLock = Any()

    @Volatile
    private var abstractMenuItemClass: Class<*>? = null

    @Volatile
    private var isolatedDexKitClassLoader: ClassLoader? = null

    private val resolvedMethods = ConcurrentHashMap<MethodTarget, Method>()
    private val cacheWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EmoRepo-Locator-Cache")
    }
    private val cacheReader = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "EmoRepo-Locator-Read")
    }

    fun resolveAbstractMenuItem(
        context: Context,
        hostClassLoader: ClassLoader,
        log: (String, Throwable?) -> Unit,
    ): Class<*> {
        abstractMenuItemClass?.takeIf(::isValidAbstractMenuItem)?.let { return it }
        return synchronized(resolveLock) {
            abstractMenuItemClass?.takeIf(::isValidAbstractMenuItem)?.let { return@synchronized it }
            val fingerprint = HostFingerprint.create(context)

            loadClassOrNull(STABLE_ABSTRACT_MENU_ITEM_CLASS, hostClassLoader)
                ?.takeIf(::isValidAbstractMenuItem)
                ?.also { resolved ->
                    log("QQ 菜单抽象类命中稳定名称：${resolved.name}", null)
                    scheduleCacheWrite(context, fingerprint, LOCATOR_ID, resolved.name, log)
                    abstractMenuItemClass = resolved
                }
                ?.let { return@synchronized it }

            readCache(context, fingerprint, LOCATOR_ID, log)
                ?.let { loadClassOrNull(it, hostClassLoader) }
                ?.takeIf(::isValidAbstractMenuItem)
                ?.also { resolved ->
                    log("QQ 菜单抽象类命中持久缓存：${resolved.name}", null)
                    abstractMenuItemClass = resolved
                }
                ?.let { return@synchronized it }

            val apk = File(context.applicationInfo.sourceDir)
            check(apk.isFile) { "QQ 主 APK 不存在：${apk.absolutePath}" }
            val scanStartedAt = SystemClock.elapsedRealtime()
            log("QQ 菜单抽象类缓存未命中，开始按特征字符串扫描宿主 APK", null)
            val candidateNames = scanAbstractMenuItem(context, apk)
            val resolved = requireUniqueValidCandidate(
                targetName = "QQ 菜单抽象类",
                candidates = candidateNames.mapNotNull { loadClassOrNull(it, hostClassLoader) },
                isValid = ::isValidAbstractMenuItem,
            )
            val elapsed = SystemClock.elapsedRealtime() - scanStartedAt
            log("QQ 菜单抽象类通过 DexKit 定位：${resolved.name}，耗时=${elapsed}ms", null)
            scheduleCacheWrite(context, fingerprint, LOCATOR_ID, resolved.name, log)
            abstractMenuItemClass = resolved
            resolved
        }
    }

    fun resolvedAbstractMenuItemOrNull(): Class<*>? =
        abstractMenuItemClass?.takeIf(::isValidAbstractMenuItem)

    fun resolveMethod(
        context: Context,
        hostClassLoader: ClassLoader,
        target: MethodTarget,
        log: (String, Throwable?) -> Unit,
    ): Method {
        resolvedMethods[target]?.takeIf(target::isValid)?.let { return it }
        return synchronized(resolveLock) {
            resolvedMethods[target]?.takeIf(target::isValid)?.let { return@synchronized it }
            val fingerprint = HostFingerprint.create(context)
            readCache(context, fingerprint, target.locatorId, log)
                ?.let { cached -> runCatching { DexMethodDescriptor.parse(cached).resolve(hostClassLoader) }.getOrNull() }
                ?.takeIf(target::isValid)
                ?.also { method ->
                    resolvedMethods[target] = method
                    log("QQ 定位命中持久缓存：${target.locatorId} -> ${method.toGenericString()}", null)
                }
                ?.let { return@synchronized it }

            val startedAt = SystemClock.elapsedRealtime()
            val descriptors = scanMethods(context, target)
            if (descriptors.isEmpty()) {
                log("QQ 定位没有候选：${target.locatorId}", null)
            } else {
                log("QQ 定位候选：${target.locatorId} -> ${descriptors.joinToString()}", null)
            }
            val method = requireUniqueValidCandidate(
                targetName = target.displayName,
                candidates = descriptors.mapNotNull { value ->
                    runCatching { DexMethodDescriptor.parse(value).resolve(hostClassLoader) }.getOrNull()
                },
                isValid = target::isValid,
            )
            val descriptor = descriptors.single { value ->
                runCatching { DexMethodDescriptor.parse(value).resolve(hostClassLoader) == method }.getOrDefault(false)
            }
            resolvedMethods[target] = method
            log(
                "QQ 定位完成：${target.locatorId} -> ${method.toGenericString()}，" +
                    "耗时=${SystemClock.elapsedRealtime() - startedAt}ms",
                null,
            )
            scheduleCacheWrite(context, fingerprint, target.locatorId, descriptor, log)
            method
        }
    }

    fun resolvedMethodOrNull(target: MethodTarget): Method? =
        resolvedMethods[target]?.takeIf(target::isValid)

    private fun scanAbstractMenuItem(context: Context, apk: File): List<String> {
        val loader = isolatedDexKitClassLoader ?: synchronized(resolveLock) {
            isolatedDexKitClassLoader ?: createIsolatedDexKitClassLoader(context).also {
                isolatedDexKitClassLoader = it
            }
        }
        val runnerClass = Class.forName(ISOLATED_DEXKIT_RUNNER_CLASS, true, loader)
        val result = runnerClass.getMethod(
            "findClassesUsingString",
            String::class.java,
            String::class.java,
            String::class.java,
        ).invoke(
            null,
            apk.absolutePath,
            ABSTRACT_MENU_ITEM_TRAIT,
            ABSTRACT_MENU_ITEM_PACKAGE,
        ) as Array<*>
        return result.filterIsInstance<String>()
    }

    private fun scanMethods(context: Context, target: MethodTarget): List<String> {
        val loader = isolatedDexKitClassLoader ?: synchronized(resolveLock) {
            isolatedDexKitClassLoader ?: createIsolatedDexKitClassLoader(context).also {
                isolatedDexKitClassLoader = it
            }
        }
        val apk = File(context.applicationInfo.sourceDir)
        check(apk.isFile) { "QQ 主 APK 不存在：${apk.absolutePath}" }
        val runnerClass = Class.forName(ISOLATED_DEXKIT_RUNNER_CLASS, true, loader)
        val result = runnerClass.getMethod(
            "findMethodDescriptorsUsingStrings",
            String::class.java,
            Array<String>::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).invoke(
            null,
            apk.absolutePath,
            target.traitStrings,
            target.packagePrefix,
            target.matchAll,
        ) as Array<*>
        return result.filterIsInstance<String>()
    }

    @Suppress("DEPRECATION")
    private fun createIsolatedDexKitClassLoader(context: Context): ClassLoader {
        val moduleInfo = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        return PathClassLoader(
            moduleInfo.sourceDir,
            moduleInfo.nativeLibraryDir,
            Context::class.java.classLoader,
        )
    }

    fun isValidAbstractMenuItem(candidate: Class<*>): Boolean {
        if (!candidate.name.startsWith(ABSTRACT_MENU_ITEM_PACKAGE)) return false
        if (!Modifier.isAbstract(candidate.modifiers)) return false
        val messageClass = loadClassOrNull(AIO_MESSAGE_CLASS, candidate.classLoader) ?: return false
        val hasUsableConstructor = candidate.declaredConstructors.any { constructor ->
            !Modifier.isPrivate(constructor.modifiers) &&
                constructor.parameterTypes.contentEquals(arrayOf(messageClass))
        }
        if (!hasUsableConstructor) return false
        val zeroArgumentMethods = candidate.methods.filter { method ->
            Modifier.isAbstract(method.modifiers) && method.parameterTypes.isEmpty()
        }
        val stringMethods = zeroArgumentMethods.count { it.returnType == String::class.java }
        val integerMethods = zeroArgumentMethods.count { it.returnType == Int::class.javaPrimitiveType }
        val clickMethods = zeroArgumentMethods.count { it.returnType == Void.TYPE }
        return stringMethods >= 1 && integerMethods in 1..2 && clickMethods == 1
    }

    private fun readCache(
        context: Context,
        fingerprint: HostFingerprint,
        locatorId: String,
        log: (String, Throwable?) -> Unit,
    ): String? {
        val future = cacheReader.submit<String?> {
            context.contentResolver.call(
                EmoRepoIpcContract.BASE_URI,
                EmoRepoIpcContract.METHOD_GET_QQ_LOCATOR_CACHE,
                null,
                fingerprint.toBundle(locatorId),
            )?.getString(EmoRepoIpcContract.RESULT_LOCATOR_CLASS_NAME)
        }
        return try {
            future.get(CACHE_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            future.cancel(true)
            log("读取 QQ 定位缓存超时或失败，将直接执行定位：$locatorId", error)
            null
        }
    }

    private fun writeCache(
        context: Context,
        fingerprint: HostFingerprint,
        locatorId: String,
        value: String,
        log: (String, Throwable?) -> Unit,
    ) {
        runCatching {
            context.contentResolver.call(
                EmoRepoIpcContract.BASE_URI,
                EmoRepoIpcContract.METHOD_PUT_QQ_LOCATOR_CACHE,
                null,
                fingerprint.toBundle(locatorId).apply {
                    putString(EmoRepoIpcContract.EXTRA_LOCATOR_CLASS_NAME, value)
                },
            )
        }.onFailure { error ->
            log("保存 QQ 定位缓存失败，本次定位结果仍可使用", error)
        }
    }

    private fun scheduleCacheWrite(
        context: Context,
        fingerprint: HostFingerprint,
        locatorId: String,
        value: String,
        log: (String, Throwable?) -> Unit,
    ) {
        val appContext = context.applicationContext ?: context
        cacheWriter.execute {
            writeCache(appContext, fingerprint, locatorId, value, log)
        }
    }

    private fun loadClassOrNull(name: String, classLoader: ClassLoader?): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrNull()

    private data class HostFingerprint(
        val versionCode: Long,
        val apkLastModified: Long,
        val apkLength: Long,
    ) {
        fun toBundle(locatorId: String) = Bundle().apply {
            putString(EmoRepoIpcContract.EXTRA_LOCATOR_ID, locatorId)
            putInt(EmoRepoIpcContract.EXTRA_LOCATOR_SCHEMA_VERSION, LOCATOR_SCHEMA_VERSION)
            putLong(EmoRepoIpcContract.EXTRA_HOST_VERSION_CODE, versionCode)
            putLong(EmoRepoIpcContract.EXTRA_HOST_APK_LAST_MODIFIED, apkLastModified)
            putLong(EmoRepoIpcContract.EXTRA_HOST_APK_LENGTH, apkLength)
        }

        companion object {
            @Suppress("DEPRECATION")
            fun create(context: Context): HostFingerprint {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
                val apk = File(context.applicationInfo.sourceDir)
                return HostFingerprint(versionCode, apk.lastModified(), apk.length())
            }
        }
    }

    private const val LOCATOR_ID = "abstract_qq_custom_menu_item"
    private const val LOCATOR_SCHEMA_VERSION = 1
    private const val CACHE_READ_TIMEOUT_MILLIS = 1_500L
    private const val AIO_MESSAGE_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem"
    private const val STABLE_ABSTRACT_MENU_ITEM_CLASS =
        "com.tencent.qqnt.aio.menu.ui.AbstractQQCustomMenuItem"
    private const val ABSTRACT_MENU_ITEM_PACKAGE = "com.tencent.qqnt.aio.menu.ui."
    private const val ABSTRACT_MENU_ITEM_TRAIT = "QQCustomMenuItem{title='"
    private const val ISOLATED_DEXKIT_RUNNER_CLASS =
        "top.e404.emorepo.experiment.lsposed.IsolatedDexKitRunner"

    enum class MethodTarget(
        val locatorId: String,
        val displayName: String,
        val traitStrings: Array<String>,
        val matchAll: Boolean,
        val packagePrefix: String,
        private val validator: (Method) -> Boolean,
    ) {
        CHAT_PANEL_INIT(
            locatorId = "chat_panel_init",
            displayName = "QQ 聊天输入栏初始化方法",
            traitStrings = arrayOf("updateFunBtn"),
            matchAll = true,
            packagePrefix = "",
            validator = { method ->
                !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE
            },
        ),
        GUILD_EMOJI_BUTTON_CREATE(
            locatorId = "guild_emoji_button_create",
            displayName = "QQ 群频道表情按钮创建方法",
            traitStrings = arrayOf("mEmojiLayout"),
            matchAll = true,
            packagePrefix = "",
            validator = { method ->
                !Modifier.isStatic(method.modifiers) &&
                    android.view.View::class.java.isAssignableFrom(method.returnType)
            },
        ),
        PANEL_ICON_LAYOUT_UPDATE(
            locatorId = "panel_icon_layout_update",
            displayName = "QQ 快捷栏图标更新方法",
            traitStrings = arrayOf("peerUid", "panelCallback"),
            matchAll = true,
            packagePrefix = "com.tencent.qqnt.aio.shortcutbar.",
            validator = { method ->
                !Modifier.isStatic(method.modifiers) &&
                    android.widget.LinearLayout::class.java.isAssignableFrom(method.declaringClass)
            },
        ),
        AIO_CREATE(
            locatorId = "aio_create",
            displayName = "QQ 会话创建方法",
            traitStrings = arrayOf("rootVMBuild", "recursiveBuildVM"),
            matchAll = false,
            packagePrefix = "com.tencent.aio.",
            validator = { method ->
                !Modifier.isStatic(method.modifiers) && method.declaringClass.declaredFields.any { field ->
                    field.type.name == AIO_PARAM_CLASS
                }
            },
        ),
        AIO_DESTROY(
            locatorId = "aio_destroy",
            displayName = "QQ 会话销毁方法",
            traitStrings = arrayOf("ChatPie", "onDestroy "),
            matchAll = true,
            packagePrefix = "com.tencent.aio.base.chat.",
            validator = { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.declaringClass.name.startsWith("com.tencent.aio.base.chat.")
            },
        ),
        ;

        fun isValid(method: Method): Boolean = runCatching { validator(method) }.getOrDefault(false)
    }

    private const val AIO_PARAM_CLASS = "com.tencent.aio.data.AIOParam"
}
