package top.e404.emorepo.experiment.lsposed

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** 为 QQ 新版图片抽屉和旧式表情抽屉追加同一个导入入口。 */
internal object QqPreviewImportHook {
    private val hookedNewPanelClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val hookedLegacyListenerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val legacySources = Collections.synchronizedMap(WeakHashMap<Any, LegacySource>())

    @Volatile
    private var pendingNewGalleryPanel = false

    @Volatile
    private var currentNewPanel: WeakReference<Any>? = null

    fun install(classLoader: ClassLoader) {
        val newInstalled = runCatching { installNewPanel(classLoader) }
            .onFailure { error -> QqPanelIntegration.log("安装 QQ 新版图片预览入口失败", error) }
            .isSuccess
        val legacyInstalled = runCatching { installLegacyPanel(classLoader) }
            .onFailure { error -> QqPanelIntegration.log("安装 QQ 旧式表情预览入口失败", error) }
            .isSuccess
        check(newInstalled || legacyInstalled) { "QQ 大图预览导入没有可用接点" }
        QqPanelIntegration.log("已安装 QQ 大图预览导入入口：new=$newInstalled legacy=$legacyInstalled")
    }

    private fun installNewPanel(classLoader: ClassLoader) {
        val itemClass = Class.forName(NEW_ACTION_ITEM_CLASS, false, classLoader)
        val baseItemClass = Class.forName(NEW_ACTION_BASE_CLASS, false, classLoader)
        val constructor = itemClass.getConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            CharSequence::class.java,
        )
        val idMethod = baseItemClass.declaredMethods.single { method ->
            method.parameterTypes.isEmpty() && method.returnType == String::class.java
        }
        val iconMethod = baseItemClass.declaredMethods.single { method ->
            method.parameterTypes.isEmpty() && method.returnType == Int::class.javaPrimitiveType
        }
        val labelMethod = baseItemClass.declaredMethods.single { method ->
            method.parameterTypes.isEmpty() && method.returnType == CharSequence::class.java
        }

        val fragmentClass = Class.forName(NEW_SHARE_PANEL_FRAGMENT_CLASS, false, classLoader)
        val onViewCreated = fragmentClass.methods.single { method ->
            method.name == "onViewCreated" && method.parameterTypes.firstOrNull() == View::class.java
        }
        XposedBridge.hookMethod(
            onViewCreated,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!pendingNewGalleryPanel) return
                    val root = param.args.firstOrNull() as? View ?: return
                    scheduleNewItem(
                        fragment = param.thisObject,
                        fallbackRoot = root,
                        itemClass = itemClass,
                        constructor = constructor,
                        idMethod = idMethod,
                        iconMethod = iconMethod,
                        labelMethod = labelMethod,
                        attempt = 0,
                    )
                }
            },
        )

        val clickClass = Class.forName(NEW_ACTION_CLICK_CLASS, false, classLoader)
        val clickMethod = clickClass.methods.single { method ->
            method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf(baseItemClass))
        }
        XposedBridge.hookMethod(
            clickMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args.firstOrNull() ?: return
                    if (idMethod.invoke(item) != ACTION_ID) return
                    val owner = currentNewPanel?.get()?.let(::findNewPanelOwner) ?: return
                    dismissNewPanel()
                    EmoRepoMessageMenuHook.startPreviewImport(findActivity(owner) ?: return, owner)
                    param.result = null
                }
            },
        )

        val apiClass = Class.forName(NEW_SHARE_PANEL_API_CLASS, false, classLoader)
        val createPanel = apiClass.methods.single { method -> method.name == "createSharePanel" }
        XposedBridge.hookMethod(
            createPanel,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val panel = param.result ?: return
                    if (param.args.getOrNull(1) != NEW_GALLERY_SCENE) return
                    currentNewPanel = WeakReference(panel)
                    pendingNewGalleryPanel = true
                    hookNewPanelDismiss(panel.javaClass)
                }
            },
        )
    }

    private fun scheduleNewItem(
        fragment: Any,
        fallbackRoot: View,
        itemClass: Class<*>,
        constructor: Constructor<*>,
        idMethod: Method,
        iconMethod: Method,
        labelMethod: Method,
        attempt: Int,
    ) {
        fallbackRoot.postDelayed({
            val completed = runCatching {
                appendNewItem(
                    resolvePanelRoot(fragment) ?: fallbackRoot,
                    itemClass,
                    constructor,
                    idMethod,
                    iconMethod,
                    labelMethod,
                )
            }.getOrElse { error ->
                QqPanelIntegration.log("追加 QQ 新版图片预览菜单失败", error)
                true
            }
            if (!completed && attempt + 1 < NEW_BIND_ATTEMPTS) {
                scheduleNewItem(
                    fragment,
                    fallbackRoot,
                    itemClass,
                    constructor,
                    idMethod,
                    iconMethod,
                    labelMethod,
                    attempt + 1,
                )
            } else if (!completed) {
                QqPanelIntegration.log("QQ 新版图片操作行未在等待窗口内建立")
            }
        }, NEW_BIND_RETRY_MILLIS)
    }

    private fun appendNewItem(
        root: View,
        itemClass: Class<*>,
        constructor: Constructor<*>,
        idMethod: Method,
        iconMethod: Method,
        labelMethod: Method,
    ): Boolean {
        if (!pendingNewGalleryPanel) return true
        val selected = hostRecyclerAdapters(root).asSequence()
            .filter { adapter -> adapter.javaClass.name == NEW_ACTION_ADAPTER_CLASS }
            .mapNotNull { adapter ->
                val adapterClass: Class<*> = adapter.javaClass
                generateSequence(adapterClass) { current -> current.superclass }
                    .flatMap { current -> current.declaredFields.asSequence() }
                    .filter { field -> List::class.java.isAssignableFrom(field.type) }
                    .mapNotNull { field ->
                        field.isAccessible = true
                        (field.get(adapter) as? List<*>)?.let { items -> adapter to items }
                    }
                    .firstOrNull { (_, items) ->
                        items.any { item -> label(item, labelMethod) in NEW_OPERATION_LABELS }
                    }
            }
            .firstOrNull() ?: return false
        val (adapter, original) = selected
        if (original.any { item ->
                item != null && runCatching { idMethod.invoke(item) == ACTION_ID }.getOrDefault(false)
            }
        ) return true
        val iconSource = original.firstOrNull { item -> label(item, labelMethod) in NEW_ICON_SOURCE_LABELS }
        val icon = iconSource?.let { item -> runCatching { iconMethod.invoke(item) as? Int }.getOrNull() } ?: 0
        check(icon != 0) { "QQ 新版图片操作行没有可复用图标" }
        val custom = constructor.newInstance(ACTION_ID, icon, MENU_TITLE)
        val updated = ArrayList<Any>(original.size + 1).apply {
            original.filterNotNullTo(this)
            add(custom)
        }
        adapter.javaClass.methods.single { method ->
            method.name == "setData" && method.parameterTypes.contentEquals(arrayOf(List::class.java))
        }.invoke(adapter, updated)
        pendingNewGalleryPanel = false
        QqPanelIntegration.log("已追加 QQ 新版图片预览导入菜单")
        return true
    }

    private fun label(item: Any?, method: Method): String? = item?.let { value ->
        runCatching { method.invoke(value)?.toString() }.getOrNull()
    }

    private fun installLegacyPanel(classLoader: ClassLoader) {
        val itemClass = Class.forName(LEGACY_ACTION_ITEM_CLASS, false, classLoader)
        val constructor = itemClass.getDeclaredConstructor().apply { isAccessible = true }
        val hookedShowMethods = mutableSetOf<Method>()
        var installed = 0
        LEGACY_PANEL_IMPLEMENTATIONS.forEach { className ->
            val panelClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                ?: return@forEach
            if (panelClass.isInterface) return@forEach
            val show = panelClass.methods.singleOrNull { method ->
                method.name == "show" && method.returnType == Void.TYPE && method.parameterTypes.isEmpty()
            } ?: return@forEach
            if (!hookedShowMethods.add(show)) return@forEach
            XposedBridge.hookMethod(
                show,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val panel = param.thisObject ?: return
                        val listener = findLegacyListener(panel) ?: return
                        val source = resolveLegacySource(listener) ?: return
                        if (!hookLegacyClick(panel, itemClass)) return
                        if (appendLegacyItem(panel, itemClass, constructor, source)) {
                            QqPanelIntegration.log("已追加 QQ 旧式表情预览导入菜单")
                        }
                    }
                },
            )
            installed++
        }
        check(installed > 0) { "QQ 旧式表情抽屉没有可 Hook 的实现" }
    }

    private fun hookLegacyClick(panel: Any, itemClass: Class<*>): Boolean {
        val listener = findLegacyListener(panel) ?: return false
        if (!hookedLegacyListenerClasses.add(listener.javaClass)) return true
        val click = listener.javaClass.declaredMethods.singleOrNull { method ->
            method.returnType == Void.TYPE && method.parameterTypes.firstOrNull() == itemClass
        }?.apply { isAccessible = true } ?: return false
        XposedBridge.hookMethod(
            click,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args.firstOrNull() ?: return
                    if (readInt(item, "action", "id") != LEGACY_ACTION_ID) return
                    val source = legacySources.remove(item) ?: return
                    val activity = source.activity.get() ?: return
                    runCatching { param.args.getOrNull(1)?.javaClass?.getMethod("dismiss")?.invoke(param.args[1]) }
                    EmoRepoMessageMenuHook.startResolvedPreviewImport(activity, source.file)
                    param.result = null
                }
            },
        )
        return true
    }

    private fun appendLegacyItem(
        panel: Any,
        itemClass: Class<*>,
        constructor: Constructor<*>,
        source: LegacySource,
    ): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>().apply { add(panel to 0) }
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            for (field in instanceFields(current.javaClass)) {
                field.isAccessible = true
                val value = runCatching { field.get(current) }.getOrNull() ?: continue
                if (field.type.isArray && field.type.componentType == List::class.java) {
                    @Suppress("UNCHECKED_CAST")
                    val rows = value as? Array<List<Any>> ?: continue
                    for (row in rows) {
                        @Suppress("UNCHECKED_CAST")
                        val items = row as? MutableList<Any> ?: continue
                        if (items.none(itemClass::isInstance)) continue
                        if (items.none { item -> readString(item, "label") in LEGACY_OPERATION_LABELS }) continue
                        if (items.any { item -> readInt(item, "action", "id") == LEGACY_ACTION_ID }) return false
                        val custom = constructor.newInstance()
                        setField(custom, LEGACY_ACTION_ID, "action", "id")
                        setField(custom, MENU_TITLE, "label")
                        setField(custom, readInt(items.first(), "icon") ?: 0, "icon")
                        legacySources[custom] = source
                        items.add(custom)
                        return true
                    }
                }
                if (depth < OBJECT_SEARCH_DEPTH && field.type.name.contains("ShareActionSheet")) {
                    queue.add(value to depth + 1)
                }
            }
        }
        return false
    }

    private fun findLegacyListener(panel: Any): Any? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>().apply { add(panel to 0) }
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            for (field in instanceFields(current.javaClass)) {
                field.isAccessible = true
                val value = runCatching { field.get(current) }.getOrNull() ?: continue
                if (field.type.name == LEGACY_LISTENER_CLASS) return value
                if (depth < OBJECT_SEARCH_DEPTH && field.type.name.contains("ShareActionSheet")) {
                    queue.add(value to depth + 1)
                }
            }
        }
        return null
    }

    private fun resolveLegacySource(listener: Any): LegacySource? {
        val fragment = instanceFields(listener.javaClass)
            .firstOrNull { field -> field.type.name == AIO_EMOTION_FRAGMENT_CLASS }
            ?.apply { isAccessible = true }
            ?.get(listener) ?: return null
        val activity = findActivity(fragment) ?: return null
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>().apply { add(fragment to 0) }
        var record: Any? = null
        var modelFile: File? = null
        while (queue.isNotEmpty() && modelFile == null) {
            val (current, depth) = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current !== fragment) modelFile = findKnownModelFile(current)
            instanceFields(current.javaClass).forEach { field ->
                field.isAccessible = true
                val value = runCatching { field.get(current) }.getOrNull() ?: return@forEach
                if (isMessageRecord(value)) record = value
                if (depth < OBJECT_SEARCH_DEPTH && LEGACY_SOURCE_PREFIXES.any { prefix ->
                        value.javaClass.name.startsWith(prefix)
                    }
                ) queue.add(value to depth + 1)
            }
        }
        val messageFile = record?.let { currentRecord ->
            fragment.javaClass.declaredMethods.singleOrNull { method ->
                method.returnType == File::class.java && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(currentRecord.javaClass)
            }?.apply { isAccessible = true }?.let { resolver ->
                (runCatching { resolver.invoke(fragment, currentRecord) }.getOrNull() as? File)
                    ?.takeIf { candidate -> candidate.isFile && candidate.length() > 0L }
            }
        }
        val file = messageFile ?: modelFile ?: return null
        return LegacySource(WeakReference(activity), file)
    }

    private fun findKnownModelFile(model: Any): File? {
        val fieldFile = instanceFields(model.javaClass)
            .filter { field -> field.type == String::class.java }
            .firstNotNullOfOrNull { field ->
                field.isAccessible = true
                existingFile(runCatching { field.get(model) as? String }.getOrNull())
            }
        if (fieldFile != null) return fieldFile
        return generateSequence(model.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredMethods.asSequence() }
            .filter { method -> method.parameterTypes.isEmpty() && method.returnType == String::class.java }
            .firstNotNullOfOrNull { method ->
                method.isAccessible = true
                existingFile(runCatching { method.invoke(model) as? String }.getOrNull())
            }
    }

    private fun existingFile(path: String?): File? = path?.takeIf(String::isNotBlank)?.let(::File)
        ?.takeIf { candidate -> candidate.isFile && candidate.length() > 0L }

    private fun isMessageRecord(value: Any): Boolean = generateSequence(value.javaClass) { current ->
        current.superclass
    }.any { current -> current.name == QQ_MESSAGE_RECORD_CLASS }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return current as? Activity
    }

    private fun findActivity(owner: Any): Activity? = owner.javaClass.methods.firstNotNullOfOrNull { method ->
        if (method.parameterTypes.isEmpty() && Activity::class.java.isAssignableFrom(method.returnType)) {
            runCatching { method.invoke(owner) as? Activity }.getOrNull()
        } else {
            null
        }
    }

    private fun resolvePanelRoot(fragment: Any): View? {
        val dialog = fragment.javaClass.methods.firstOrNull { method ->
            method.name == "getDialog" && method.parameterTypes.isEmpty()
        }?.invoke(fragment) as? android.app.Dialog ?: return null
        return dialog.window?.decorView
    }

    private fun hookNewPanelDismiss(panelClass: Class<*>) {
        if (!hookedNewPanelClasses.add(panelClass)) return
        val dismiss = panelClass.methods.singleOrNull { method ->
            method.name == "dismiss" && method.returnType == Void.TYPE && method.parameterTypes.isEmpty()
        } ?: return
        XposedBridge.hookMethod(
            dismiss,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (currentNewPanel?.get() === param.thisObject) currentNewPanel = null
                    pendingNewGalleryPanel = false
                }
            },
        )
    }

    private fun dismissNewPanel() {
        val panel = currentNewPanel?.get() ?: return
        runCatching {
            panel.javaClass.methods.single { method ->
                method.name == "dismiss" && method.returnType == Void.TYPE && method.parameterTypes.isEmpty()
            }.invoke(panel)
        }.onFailure { error -> QqPanelIntegration.log("关闭 QQ 新版图片抽屉失败", error) }
        currentNewPanel = null
    }

    private fun findNewPanelOwner(panel: Any): Any? {
        val parameter = instanceFields(panel.javaClass)
            .firstOrNull { field -> field.type.name == NEW_SHARE_PANEL_PARAM_CLASS }
            ?.apply { isAccessible = true }
            ?.get(panel) ?: return null
        val callback = instanceFields(parameter.javaClass)
            .firstOrNull { field -> field.type.name == NEW_PREPARE_CALLBACK_CLASS }
            ?.apply { isAccessible = true }
            ?.get(parameter) ?: return null
        return instanceFields(callback.javaClass)
            .firstOrNull { field -> field.type.name == MORE_PART_CLASS }
            ?.apply { isAccessible = true }
            ?.get(callback)
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun hostRecyclerAdapters(fallback: View): List<Any> = windowRoots(fallback).asSequence()
        .flatMap(::descendants)
        .filter { view -> view.javaClass.name == HOST_RECYCLER_VIEW_CLASS }
        .mapNotNull { view -> runCatching { view.javaClass.getMethod("getAdapter").invoke(view) }.getOrNull() }
        .toList()

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun windowRoots(fallback: View): List<View> = runCatching {
        // QQ 分享抽屉使用独立 Window；只读枚举根视图，失败时回退当前 Fragment 根。
        val managerClass = Class.forName("android.view.WindowManagerGlobal")
        val manager = managerClass.getMethod("getInstance").invoke(null)
        val viewsField = managerClass.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (viewsField.get(manager) as? List<View>).orEmpty().ifEmpty { listOf(fallback) }
    }.getOrElse { listOf(fallback) }

    private fun instanceFields(type: Class<*>): Sequence<java.lang.reflect.Field> =
        generateSequence(type) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filterNot { field -> Modifier.isStatic(field.modifiers) }

    private fun readInt(target: Any, vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        findField(target.javaClass, name)?.let { field -> runCatching { field.getInt(target) }.getOrNull() }
    }

    private fun readString(target: Any, vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        findField(target.javaClass, name)?.let { field -> runCatching { field.get(target) as? String }.getOrNull() }
    }

    private fun setField(target: Any, value: Any, vararg names: String) {
        val field = names.firstNotNullOfOrNull { name -> findField(target.javaClass, name) }
            ?: error("QQ 菜单字段不存在：${names.joinToString()}")
        when (value) {
            is Int -> field.setInt(target, value)
            else -> field.set(target, value)
        }
    }

    private fun findField(type: Class<*>, name: String) = instanceFields(type)
        .firstOrNull { field -> field.name == name }
        ?.apply { isAccessible = true }

    private data class LegacySource(val activity: WeakReference<Activity>, val file: File)

    private const val MORE_PART_CLASS = "com.tencent.qqnt.aio.gallery.part.NTAIOLayerMorePart"
    private const val HOST_RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    private const val NEW_SHARE_PANEL_API_CLASS =
        "com.tencent.mobileqq.sharepanel.api.impl.SharePanelApiImpl"
    private const val NEW_SHARE_PANEL_PARAM_CLASS =
        "com.tencent.mobileqq.sharepanel.launcher.SharePanelParam"
    private const val NEW_PREPARE_CALLBACK_CLASS = "com.tencent.mobileqq.sharepanel.n"
    private const val NEW_SHARE_PANEL_FRAGMENT_CLASS =
        "com.tencent.mobileqq.sharepanel.fragment.SharePanelDialogFragment"
    private const val NEW_ACTION_ADAPTER_CLASS = "com.tencent.mobileqq.sharepanel.action.c"
    private const val NEW_ACTION_ITEM_CLASS = "com.tencent.mobileqq.sharepanel.action.a"
    private const val NEW_ACTION_BASE_CLASS = "com.tencent.mobileqq.sharepanel.action.e"
    private const val NEW_ACTION_CLICK_CLASS =
        "com.tencent.mobileqq.sharepanel.action.ShareActionPart\$initRecyclerView\$onItemClickListener\$1"
    private const val LEGACY_ACTION_ITEM_CLASS =
        "com.tencent.mobileqq.utils.ShareActionSheetBuilder\$ActionSheetItem"
    private const val LEGACY_LISTENER_CLASS =
        "com.tencent.mobileqq.widget.share.ShareActionSheet\$OnItemClickListener"
    private const val AIO_EMOTION_FRAGMENT_CLASS =
        "com.tencent.mobileqq.emotionintegrate.AIOEmotionFragment"
    private const val QQ_MESSAGE_RECORD_CLASS = "com.tencent.mobileqq.data.MessageRecord"
    private const val ACTION_ID = "emorepo_import"
    private const val NEW_GALLERY_SCENE = "mediamessage_picture"
    private const val NEW_BIND_RETRY_MILLIS = 200L
    private const val NEW_BIND_ATTEMPTS = 10
    private const val LEGACY_ACTION_ID = 0x0E405
    private const val OBJECT_SEARCH_DEPTH = 3
    private const val MENU_TITLE = "添加到 EmoRepo"

    private val LEGACY_PANEL_IMPLEMENTATIONS = listOf(
        "com.tencent.mobileqq.widget.share.ShareActionSheetV2",
        "com.tencent.mobileqq.widget.share.b",
        "com.tencent.mobileqq.widget.share.c",
    )
    private val LEGACY_OPERATION_LABELS = setOf("添加到表情", "查看表情", "保存到手机", "定位聊天")
    private val NEW_OPERATION_LABELS = setOf("保存到手机", "收藏", "编辑", "定位聊天", "翻译", "提取文字")
    private val NEW_ICON_SOURCE_LABELS = setOf("收藏", "保存到手机")
    private val LEGACY_SOURCE_PREFIXES = listOf(
        "com.tencent.mobileqq.emotionintegrate.",
        "com.tencent.mobileqq.emoticonview.",
    )
}
