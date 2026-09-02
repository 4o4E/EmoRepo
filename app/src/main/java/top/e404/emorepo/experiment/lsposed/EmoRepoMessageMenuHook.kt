package top.e404.emorepo.experiment.lsposed

import android.app.AndroidAppHelper
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.HttpURLConnection
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import net.bytebuddy.ByteBuddy
import net.bytebuddy.android.AndroidClassLoadingStrategy
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.implementation.FieldAccessor
import net.bytebuddy.implementation.MethodCall
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.returns
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import top.e404.emorepo.ipc.EmoRepoIpcContract
import top.e404.emorepo.diagnostics.DiagnosticSanitizer

/**
 * 在 QQ 原有图片消息菜单中增加 EmoRepo 导入项，不替换任何原生菜单行为。
 */
internal object EmoRepoMessageMenuHook {
    private val hookedComponentClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val generationLock = Any()
    private val componentPrewarmScheduled = AtomicBoolean(false)
    private val menuNotReadyLogged = AtomicBoolean(false)

    @Volatile
    private var generatedMenuClass: GeneratedMenuClass? = null

    fun schedulePrewarm(classLoader: ClassLoader) {
        schedulePrewarmAttempt(classLoader, 0)
    }

    private fun schedulePrewarmAttempt(classLoader: ClassLoader, attempt: Int) {
        val application = AndroidAppHelper.currentApplication()
        if (application != null) {
            prewarm(application, classLoader)
            return
        }
        if (attempt >= MAXIMUM_PREWARM_ATTEMPTS) {
            log("QQ Application 未在预热窗口内建立，将在首次打开菜单时定位")
            return
        }
        mainHandler.postDelayed(
            { schedulePrewarmAttempt(classLoader, attempt + 1) },
            PREWARM_RETRY_DELAY_MILLIS,
        )
    }

    fun prewarm(context: Context, classLoader: ClassLoader) {
        QqPanelIntegration.ensureInstalled(context, classLoader)
        worker.execute {
            runCatching {
                QqSymbolLocator.resolveAbstractMenuItem(context, classLoader, ::log)
            }.onSuccess { menuClass ->
                log("QQ 菜单抽象类预热完成：${menuClass.name}")
            }.onFailure { error ->
                log("QQ 菜单抽象类预热失败，图片菜单功能将停用", error)
            }
        }
    }

    fun install(classLoader: ClassLoader) {
        val messageClass = XposedHelpers.findClass(AIO_MESSAGE_CLASS, classLoader)
        val baseComponentClass = XposedHelpers.findClass(BASE_CONTENT_COMPONENT_CLASS, classLoader)
        val messageMethods = baseComponentClass.declaredMethods.filter { method ->
            method.returnType == messageClass && method.parameterTypes.isEmpty()
        }
        val getMessageMethod = messageMethods.firstOrNull { method ->
            !method.isBridge && !method.isSynthetic
        } ?: messageMethods.first()
        getMessageMethod.isAccessible = true
        val menuMethods = baseComponentClass.declaredMethods.filter { method ->
            Modifier.isAbstract(method.modifiers) &&
                List::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.isEmpty()
        }
        val menuMethodName = (menuMethods.firstOrNull { method ->
            !method.isBridge && !method.isSynthetic
        } ?: menuMethods.first()).name
        val getContextMethod = baseComponentClass.methods.first { method ->
            method.name == "getMContext" &&
                Context::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.isEmpty()
        }

        XposedBridge.hookAllConstructors(
            baseComponentClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val componentClass = param.thisObject.javaClass
                    if (!isSupportedPictureComponent(componentClass)) return
                    val context = runCatching {
                        getContextMethod.invoke(param.thisObject) as? Context
                    }.getOrNull()
                    runCatching { getMessageMethod.invoke(param.thisObject) }
                        .getOrNull()
                        ?.let { message ->
                            if (context != null) QqSessionTracker.updateFromMessage(message, context)
                        }
                    if (componentPrewarmScheduled.compareAndSet(false, true)) {
                        if (context != null) prewarm(context, classLoader)
                    }
                    if (!hookedComponentClasses.add(componentClass)) return
                    val menuMethod = componentClass.methods.singleOrNull { method ->
                        method.name == menuMethodName && method.parameterTypes.isEmpty()
                    } ?: return
                    hookMenuMethod(
                        menuMethod,
                        getMessageMethod,
                        getContextMethod,
                        messageClass,
                    )
                }
            },
        )
        log("已监听 QQ 图片消息组件构造，菜单方法=$menuMethodName")
    }

    private fun hookMenuMethod(
        menuMethod: Method,
        getMessageMethod: Method,
        getContextMethod: Method,
        messageClass: Class<*>,
    ) {
        XposedBridge.hookMethod(
            menuMethod,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = param.result as? List<*> ?: return
                    if (containsEmoRepoItem(original)) return
                    if (QqSymbolLocator.resolvedAbstractMenuItemOrNull() == null) {
                        if (menuNotReadyLogged.compareAndSet(false, true)) {
                            log("QQ 菜单定位尚未完成，本次保留原菜单并跳过 EmoRepo 项")
                        }
                        return
                    }
                    val message = getMessageMethod.invoke(param.thisObject) ?: return
                    val context = getContextMethod.invoke(param.thisObject) as? Context ?: return
                    val item = createMenuItem(
                        context = context,
                        message = message,
                        messageClass = messageClass,
                        originalItems = original,
                    )
                    param.result = ArrayList<Any>(original.size + 1).apply {
                        add(item)
                        original.filterNotNullTo(this)
                    }
                }
            },
        )
        log("已 Hook 图片消息菜单：${menuMethod.declaringClass.name}.${menuMethod.name}")
    }

    private fun createMenuItem(
        context: Context,
        message: Any,
        messageClass: Class<*>,
        originalItems: List<*>,
    ): Any {
        val generated = menuClass(context, messageClass)
        val item = generated.type.getDeclaredConstructor(messageClass).newInstance(message)
        generated.type.getField(FIELD_TITLE).set(item, MENU_TITLE)
        generated.type.getField(FIELD_ICON).setInt(
            item,
            findReusableIcon(originalItems, generated.contract.iconMethodName),
        )
        generated.type.getField(FIELD_ID).setInt(item, MENU_ID)
        generated.type.getField(FIELD_ACTION).set(item, Runnable { onMenuClick(context, message) })
        return item
    }

    private fun menuClass(
        context: Context,
        messageClass: Class<*>,
    ): GeneratedMenuClass {
        generatedMenuClass?.let { return it }
        return synchronized(generationLock) {
            generatedMenuClass ?: run {
                val baseMenuClass = requireNotNull(
                    QqSymbolLocator.resolvedAbstractMenuItemOrNull(),
                ) { "QQ 菜单定位尚未完成" }
                val contract = inspectMenuContract(baseMenuClass, messageClass)
                val application = requireNotNull(AndroidAppHelper.currentApplication()) {
                    "QQ Application 尚未初始化"
                }
                val runnableMethod = Runnable::class.java.getMethod("run")
                val builder = ByteBuddy()
                    .subclass(baseMenuClass)
                    .name("top.e404.emorepo.generated.EmoRepoMenuItem")
                    .defineField(FIELD_TITLE, String::class.java, Visibility.PUBLIC)
                    .defineField(FIELD_ICON, Int::class.javaPrimitiveType!!, Visibility.PUBLIC)
                    .defineField(FIELD_ID, Int::class.javaPrimitiveType!!, Visibility.PUBLIC)
                    .defineField(FIELD_ACTION, Runnable::class.java, Visibility.PUBLIC)
                    .method(
                        isAbstract<MethodDescription>()
                            .and(returns(String::class.java))
                            .and(takesArguments(0)),
                    )
                    .intercept(FieldAccessor.ofField(FIELD_TITLE))
                    .method(named(contract.idMethodName))
                    .intercept(FieldAccessor.ofField(FIELD_ID))
                    .method(named(contract.clickMethodName))
                    .intercept(MethodCall.invoke(runnableMethod).onField(FIELD_ACTION))
                val completedBuilder = contract.iconMethodName?.let { iconMethodName ->
                    builder
                        .method(named(iconMethodName))
                        .intercept(FieldAccessor.ofField(FIELD_ICON))
                } ?: builder
                completedBuilder
                    .make()
                    .load(
                        baseMenuClass.classLoader,
                        AndroidClassLoadingStrategy.Wrapping(
                            application.getDir("emorepo-bytebuddy", 0),
                        ),
                    )
                    .loaded
                    .let { generated -> GeneratedMenuClass(generated, contract) }
                    .also {
                        generatedMenuClass = it
                        log(
                            "已生成 QQ 菜单实现：base=${baseMenuClass.name} " +
                                "icon=${contract.iconMethodName ?: "none"} " +
                                "id=${contract.idMethodName} click=${contract.clickMethodName}",
                        )
                    }
            }
        }
    }

    private fun inspectMenuContract(
        baseMenuClass: Class<*>,
        messageClass: Class<*>,
    ): MenuContract {
        check(QqSymbolLocator.isValidAbstractMenuItem(baseMenuClass)) {
            "QQ 菜单抽象类结构校验失败：${baseMenuClass.name}"
        }
        check(baseMenuClass.declaredConstructors.any { constructor ->
            !Modifier.isPrivate(constructor.modifiers) &&
                constructor.parameterTypes.contentEquals(arrayOf(messageClass))
        }) { "QQ 菜单抽象类缺少 AIOMsgItem 构造器" }
        val abstractZeroArgumentMethods = baseMenuClass.methods.filter { method ->
            Modifier.isAbstract(method.modifiers) && method.parameterTypes.isEmpty()
        }
        val integerMethods = abstractZeroArgumentMethods.filter { method ->
            method.returnType == Int::class.javaPrimitiveType
        }
        val clickMethod = abstractZeroArgumentMethods.single { method ->
            method.returnType == Void.TYPE
        }
        // QAux 对带图标菜单同样按反射顺序将两个 int 方法解释为图标和菜单 ID。
        val iconMethod = integerMethods.takeIf { it.size == 2 }?.first()
        val idMethod = integerMethods.last()
        return MenuContract(
            iconMethodName = iconMethod?.name,
            idMethodName = idMethod.name,
            clickMethodName = clickMethod.name,
        )
    }

    private fun isSupportedPictureComponent(componentClass: Class<*>): Boolean =
        PICTURE_COMPONENT_PACKAGES.any { prefix -> componentClass.name.startsWith(prefix) }

    private fun containsEmoRepoItem(items: List<*>): Boolean = items.any { item ->
        runCatching {
            item?.javaClass?.methods
                ?.asSequence()
                ?.filter { method ->
                    method.returnType == String::class.java && method.parameterTypes.isEmpty()
                }
                ?.any { method -> method.invoke(item) == MENU_TITLE } == true
        }.getOrDefault(false)
    }

    private fun findReusableIcon(items: List<*>, iconMethodName: String?): Int {
        if (iconMethodName == null) return 0
        for (item in items) {
            if (item == null) continue
            val title = runCatching {
                item.javaClass.methods
                    .asSequence()
                    .filter { method ->
                        method.returnType == String::class.java && method.parameterTypes.isEmpty()
                    }
                    .mapNotNull { method -> method.invoke(item) as? String }
                    .firstOrNull { value -> value.contains("收藏") || value.contains("表情") }
            }.getOrNull()
            if (title == null) continue
            val icon = runCatching {
                item.javaClass.methods
                    .firstOrNull { method ->
                        method.name == iconMethodName && method.parameterTypes.isEmpty()
                    }
                    ?.invoke(item) as? Int
            }.getOrNull()
            if (icon != null) return icon
        }
        return 0
    }

    private fun onMenuClick(context: Context, message: Any) {
        val summary = summarizeMessage(message)
        log("点击添加到 EmoRepo：$summary")
        worker.execute {
            runCatching {
                val pictures = extractPictureElements(message)
                check(pictures.isNotEmpty()) { "消息中没有可导入的图片" }
                listPacks(context) to pictures
            }.onSuccess { (packs, pictures) ->
                mainHandler.post { showPackChooser(context, packs, pictures) }
            }.onFailure { error ->
                log("准备 EmoRepo 导入失败", error)
                showToast(context, error.message ?: "准备导入失败")
            }
        }
    }

    fun startPreviewImport(context: Context, previewOwner: Any) {
        worker.execute {
            runCatching {
                val picture = extractCurrentPreviewPicture(previewOwner)
                listPacks(context) to listOf(picture)
            }.onSuccess { (packs, pictures) ->
                mainHandler.post { showPackChooser(context, packs, pictures) }
            }.onFailure { error ->
                log("准备 QQ 大图预览导入失败", error)
                showToast(context, error.message ?: "准备导入失败")
            }
        }
    }

    fun startResolvedPreviewImport(context: Context, file: File) {
        worker.execute {
            runCatching { listPacks(context) }.onSuccess { packs ->
                mainHandler.post {
                    val chooser = EmoRepoImportDialog.show(context, packs, 1) { packId ->
                        importResolvedPictures(
                            context,
                            packId,
                            listOf(file.name.ifBlank { "qq-preview.bin" } to file),
                        )
                    }
                    chooser.updatePreview(file)
                }
            }.onFailure { error ->
                log("准备 QQ 大图文件导入失败", error)
                showToast(context, error.message ?: "准备导入失败")
            }
        }
    }

    private fun extractCurrentPreviewPicture(previewOwner: Any): PictureRef {
        val previewContext = if (previewOwner.javaClass.name == "com.tencent.qqnt.aio.gallery.share.s") {
            previewOwner
        } else {
            previewOwner.javaClass.declaredMethods.firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    method.returnType.name == "com.tencent.qqnt.aio.gallery.share.s"
            }?.apply { isAccessible = true }?.invoke(previewOwner)
                ?: error("QQ 大图预览缺少当前图片上下文")
        }
        val fields = generateSequence(previewContext.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filterNot { field -> Modifier.isStatic(field.modifiers) }
            .onEach { field -> field.isAccessible = true }
            .toList()
        val element = fields.firstOrNull { field ->
            field.type.name == "com.tencent.qqnt.kernel.nativeinterface.MsgElement"
        }?.get(previewContext) ?: error("QQ 大图预览缺少当前图片元素")
        val picture = element.javaClass.getMethod("getPicElement").invoke(element)
            ?: error("QQ 当前预览项不是图片")
        val mediaInfo = fields.firstOrNull { field ->
            field.type.name == "com.tencent.richframework.gallery.bean.RFWLayerItemMediaInfo"
        }?.get(previewContext)
        val message = mediaInfo?.javaClass?.methods?.firstOrNull { method ->
            method.name == "getExtraData" && method.parameterTypes.isEmpty()
        }?.invoke(mediaInfo)?.takeIf { value ->
            value.javaClass.name == AIO_MESSAGE_CLASS ||
                generateSequence(value.javaClass) { current -> current.superclass }
                    .any { current -> current.name == AIO_MESSAGE_CLASS }
        }
        val fallback = mediaInfo?.javaClass?.methods?.firstOrNull { method ->
            method.name == "getExistSaveOrEditPath" &&
                method.parameterTypes.isEmpty() && method.returnType == String::class.java
        }?.invoke(mediaInfo) as? String
        return PictureRef(
            message = message,
            element = element,
            picture = picture,
            fallbackFile = fallback?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile),
        )
    }

    private fun listPacks(context: Context): List<PanelPack> {
        return importTargetPacks(QqPanelRepository.listPacks(context))
            .also { packs -> check(packs.isNotEmpty()) { "EmoRepo 没有可导入的表情包" } }
    }

    private fun showPackChooser(context: Context, packs: List<PanelPack>, pictures: List<PictureRef>) {
        val chooser = EmoRepoImportDialog.show(context, packs, pictures.size) { packId ->
            importPictures(context, packId, pictures)
        }
        val first = pictures.firstOrNull() ?: return
        worker.execute {
            runCatching { resolvePictureFile(context, first) }
                .onSuccess { file -> mainHandler.post { chooser.updatePreview(file) } }
                .onFailure { error -> log("加载待导入首图预览失败", error) }
        }
    }

    private fun importPictures(context: Context, packId: String, pictures: List<PictureRef>) {
        worker.execute {
            runCatching {
                val sources = pictures.mapIndexed { index, picture ->
                    val source = resolvePictureFile(context, picture)
                    val md5 = invokeString(picture.picture, "getMd5HexStr")?.lowercase()
                    val sourceName = md5?.let { "$it.bin" } ?: source.name.ifBlank { "qq-image-$index" }
                    sourceName to source
                }
                performImport(context, packId, sources)
            }.onSuccess { feedback ->
                showImportFeedback(context, feedback)
            }.onFailure { error ->
                log("导入 EmoRepo 失败", error)
                showToast(context, error.message ?: "添加失败")
            }
        }
    }

    private fun importResolvedPictures(context: Context, packId: String, sources: List<Pair<String, File>>) {
        worker.execute {
            runCatching { performImport(context, packId, sources) }
                .onSuccess { feedback -> showImportFeedback(context, feedback) }
                .onFailure { error ->
                    log("导入 EmoRepo 失败", error)
                    showToast(context, error.message ?: "添加失败")
                }
        }
    }

    private fun performImport(
        context: Context,
        packId: String,
        sources: List<Pair<String, File>>,
    ): ImportFeedback {
        val descriptors = sources.map { (_, source) ->
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        try {
            val result = context.contentResolver.call(
                EmoRepoIpcContract.BASE_URI,
                EmoRepoIpcContract.METHOD_IMPORT_ITEMS,
                null,
                Bundle().apply {
                    putString(EmoRepoIpcContract.EXTRA_PACK_ID, packId)
                    putStringArrayList(
                        EmoRepoIpcContract.EXTRA_SOURCE_NAMES,
                        ArrayList(sources.map { it.first }),
                    )
                    putParcelableArrayList(
                        EmoRepoIpcContract.EXTRA_SOURCE_DESCRIPTORS,
                        ArrayList(descriptors),
                    )
                },
            )
            requireNotNull(result) { "EmoRepo 未返回导入结果" }
            return ImportFeedback(
                succeeded = result.getInt(EmoRepoIpcContract.RESULT_SUCCESS_COUNT),
                duplicates = result.getInt(EmoRepoIpcContract.RESULT_DUPLICATE_COUNT),
                failed = result.getInt(EmoRepoIpcContract.RESULT_FAILED_COUNT),
            )
        } finally {
            descriptors.forEach { descriptor -> runCatching { descriptor.close() } }
        }
    }

    private fun showImportFeedback(context: Context, feedback: ImportFeedback) {
        val text = "导入完成：新增 ${feedback.succeeded}，重复 ${feedback.duplicates}，失败 ${feedback.failed}"
        log("EmoRepo 批量导入结果：$text")
        showToast(context, text)
    }

    private fun extractPictureElements(message: Any): List<PictureRef> {
        val recordField = findMessageRecordField(message)
        val record = recordField.get(message)
        val elements = record.javaClass.getMethod("getElements").invoke(record) as? Collection<*>
            ?: return emptyList()
        return elements.mapNotNull { element ->
            if (element == null) return@mapNotNull null
            runCatching { element.javaClass.getMethod("getPicElement").invoke(element) }
                .getOrNull()
                ?.let { picture -> PictureRef(message, element, picture, null) }
        }
    }

    private fun resolvePictureFile(context: Context, reference: PictureRef): File {
        findMessageCacheFile(reference)?.let { (size, file) ->
            log("使用 QQ 消息缓存：type=$size size=${file.length()}")
            return file
        }
        reference.message?.let(::findExistingMessagePath)?.let { (method, file) ->
            log("使用 QQ 消息预下载路径：method=$method size=${file.length()}")
            return file
        }

        val picture = reference.picture
        val localFiles = picture.javaClass.methods
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java &&
                    method.name.contains("path", ignoreCase = true)
            }
            .mapNotNull { method ->
                val value = runCatching { method.invoke(picture) as? String }.getOrNull()
                value?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)
                    ?.let { file -> method.name to file }
            }
            .sortedBy { (name, _) ->
                when {
                    name.contains("origin", ignoreCase = true) -> 0
                    name.contains("source", ignoreCase = true) -> 1
                    name.contains("file", ignoreCase = true) -> 2
                    name.contains("thumb", ignoreCase = true) -> 10
                    else -> 5
                }
            }
        localFiles.firstOrNull()?.let { (method, file) ->
            log("使用 QQ 本地图片：method=$method size=${file.length()}")
            return file
        }

        waitForQqCache(reference)?.let { (source, file) ->
            log("等待 QQ 图片缓存完成：source=$source size=${file.length()}")
            return file
        }

        val md5 = invokeString(picture, "getMd5HexStr")?.uppercase()
        val remoteResult = runCatching {
            val rawUrl = invokeString(picture, "getOriginImageUrl")
            val url = when {
                rawUrl?.startsWith("http") == true -> rawUrl
                rawUrl?.startsWith("/") == true && !rawUrl.startsWith("/download") ->
                    "https://gchat.qpic.cn$rawUrl"
                rawUrl?.startsWith("/download") == true -> {
                    val group = rawUrl.contains("appid=1406")
                    val rkey = QqRkeyStore.downloadRkey(group)
                        ?: error("QQ 富媒体 rkey 尚未取得，请稍后重试")
                    "https://multimedia.nt.qq.com$rawUrl$rkey"
                }
                md5 != null -> "https://gchat.qpic.cn/gchatpic_new/0/0-0-$md5/0"
                else -> error("QQ 未提供可读取的图片路径或地址")
            }
            downloadPicture(context, md5, url)
        }
        remoteResult.getOrNull()?.let { return it }
        reference.fallbackFile?.takeIf { file -> file.isFile && file.length() > 0L }?.let { file ->
            log("使用 QQ 大图预览后备文件：size=${file.length()}")
            return file
        }
        throw remoteResult.exceptionOrNull() ?: error("QQ 未提供可读取的图片")
    }

    private fun downloadPicture(context: Context, md5: String?, url: String): File {
        val directory = File(context.cacheDir, "emorepo-import").apply { mkdirs() }
        check(directory.isDirectory) { "无法创建 QQ 导入缓存" }
        val target = File(directory, "${md5 ?: System.currentTimeMillis()}.bin")
        if (target.isFile && target.length() > 0L) return target
        val temporary = File(directory, ".${target.name}.tmp")
        temporary.delete()
        val connection = URL(url).openConnection().apply {
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
        }
        try {
            if (connection is HttpURLConnection) {
                check(connection.responseCode in 200..299) { "QQ 原图下载失败：HTTP ${connection.responseCode}" }
            }
            connection.getInputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        check(total <= MAXIMUM_DOWNLOAD_BYTES) { "QQ 原图超过 64 MiB 限制" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            check(temporary.length() > 0L && temporary.renameTo(target)) { "QQ 原图下载失败" }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
        log("已下载 QQ 原图：size=${target.length()}")
        return target
    }

    private fun findExistingMessagePath(message: Any): Pair<String, File>? =
        message.javaClass.methods.asSequence()
            .filter { method ->
                method.parameterTypes.isEmpty() && method.returnType == String::class.java
            }
            .mapNotNull { method ->
                val path = runCatching { method.invoke(message) as? String }.getOrNull()
                val file = path?.takeIf(String::isNotBlank)?.let(::File)
                if (file?.isFile == true && file.length() > 0L) method.name to file else null
            }
            .firstOrNull()

    private fun waitForQqCache(reference: PictureRef): Pair<String, File>? {
        repeat(QQ_CACHE_WAIT_ATTEMPTS) {
            Thread.sleep(QQ_CACHE_WAIT_MILLIS)
            findMessageCacheFile(reference)?.let { (size, file) -> return size to file }
            reference.message?.let(::findExistingMessagePath)?.let { return it }
        }
        return null
    }

    private fun findMessageCacheFile(reference: PictureRef): Pair<String, File>? {
        val message = reference.message ?: return null
        val elementId = runCatching {
            reference.element.javaClass.getMethod("getElementId").invoke(reference.element) as Long
        }.getOrNull() ?: return null
        val picSizeClass = XposedHelpers.findClass(
            "com.tencent.mobileqq.aio.msglist.holder.base.PicSize",
            message.javaClass.classLoader,
        )
        val pathMethod = message.javaClass.methods.firstOrNull { method ->
            method.returnType == String::class.java &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Long::class.javaPrimitiveType &&
                method.parameterTypes[1] == picSizeClass
        } ?: return null
        for (name in MESSAGE_CACHE_PRIORITY) {
            val size = picSizeClass.getField(name).get(null)
            val path = runCatching {
                pathMethod.invoke(message, elementId, size) as? String
            }.getOrNull()
            val file = path?.takeIf(String::isNotBlank)?.let(::File)
            if (file?.isFile == true && file.length() > 0L) return name to file
        }
        return null
    }

    private fun invokeString(target: Any, methodName: String): String? = runCatching {
        target.javaClass.methods
            .firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            ?.invoke(target) as? String
    }.getOrNull()

    private fun showToast(context: Context, text: String) {
        mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun summarizeMessage(message: Any): String {
        val recordField = runCatching { findMessageRecordField(message) }.getOrElse {
            return "message=${message.javaClass.name}, record=missing"
        }
        recordField.isAccessible = true
        val record = recordField.get(message)
        val elements = runCatching {
            record.javaClass.getMethod("getElements").invoke(record) as? Collection<*>
        }.getOrNull()
        val pictureCount = elements.orEmpty().count { element ->
            runCatching {
                element?.javaClass?.getMethod("getPicElement")?.invoke(element) != null
            }.getOrDefault(false)
        }
        return "message=${message.javaClass.name}, elements=${elements?.size ?: -1}, pictures=$pictureCount"
    }

    private fun findMessageRecordField(message: Any) =
        requireNotNull(
            generateSequence(message.javaClass) { current -> current.superclass }
                .flatMap { current -> current.declaredFields.asSequence() }
                .firstOrNull { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        field.type.name == "com.tencent.qqnt.kernel.nativeinterface.MsgRecord"
                },
        ) { "消息缺少 MsgRecord" }.apply { isAccessible = true }

    private data class ImportFeedback(
        val succeeded: Int,
        val duplicates: Int,
        val failed: Int,
    )

    private data class PictureRef(
        val message: Any?,
        val element: Any,
        val picture: Any,
        val fallbackFile: File?,
    )

    private data class MenuContract(
        val iconMethodName: String?,
        val idMethodName: String,
        val clickMethodName: String,
    )

    private data class GeneratedMenuClass(
        val type: Class<*>,
        val contract: MenuContract,
    )

    private fun log(message: String, error: Throwable? = null) {
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

    private const val TAG = "EmoRepo-LSPosed"
    private const val AIO_MESSAGE_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem"
    private const val BASE_CONTENT_COMPONENT_CLASS =
        "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent"
    private const val MENU_TITLE = "添加到 EmoRepo"
    private const val MENU_ID = 0x0E404
    private const val FIELD_TITLE = "emorepoTitle"
    private const val FIELD_ICON = "emorepoIcon"
    private const val FIELD_ID = "emorepoId"
    private const val FIELD_ACTION = "emorepoAction"
    private const val NETWORK_TIMEOUT_MILLIS = 15_000
    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    private const val MAXIMUM_DOWNLOAD_BYTES = 64L * 1024L * 1024L
    private const val QQ_CACHE_WAIT_ATTEMPTS = 30
    private const val QQ_CACHE_WAIT_MILLIS = 100L
    private const val MAXIMUM_PREWARM_ATTEMPTS = 50
    private const val PREWARM_RETRY_DELAY_MILLIS = 200L
    private val MESSAGE_CACHE_PRIORITY = listOf(
        "PIC_DOWNLOAD_ORI",
        "PIC_DOWNLOAD_BIG_THUMB",
        "PIC_LOCAL_HD_THUMB",
        "PIC_DOWNLOAD_THUMB",
    )
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EmoRepo-QQ-Import")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val PICTURE_COMPONENT_PACKAGES = listOf(
        "com.tencent.mobileqq.aio.msglist.holder.component.pic.",
        "com.tencent.mobileqq.aio.msglist.holder.component.mix.",
        "com.tencent.mobileqq.aio.msglist.holder.component.multipci.",
    )
}
