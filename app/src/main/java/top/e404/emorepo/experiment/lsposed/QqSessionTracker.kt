package top.e404.emorepo.experiment.lsposed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.util.ArrayDeque

/** 跟踪 QQ NT 当前 AIO 会话，面板打开时再冻结为不可变 Contact。 */
internal object QqSessionTracker {
    private val lock = Any()
    private val sessions = ArrayDeque<SessionEntry>()

    @Volatile
    private var fallbackContact: FallbackContact? = null

    fun install(createMethod: java.lang.reflect.Method, destroyMethod: java.lang.reflect.Method) {
        XposedBridge.hookMethod(
            createMethod,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject ?: return
                    val aioParam = findFieldByType(owner, AIO_PARAM_CLASS)?.get(owner) ?: return
                    val activity = param.args.asSequence()
                        .mapNotNull(::contextFromObject)
                        .mapNotNull(::activityFromContext)
                        .firstOrNull()
                    synchronized(lock) {
                        sessions.removeAll { entry -> entry.owner === owner }
                        sessions.addLast(SessionEntry(owner, aioParam, activity))
                        if (fallbackContact?.activity === activity) fallbackContact = null
                    }
                    QqPanelIntegration.log("已捕获 QQ 会话：${owner.javaClass.name}")
                }
            },
        )
        XposedBridge.hookMethod(
            destroyMethod,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject ?: return
                    synchronized(lock) {
                        val removedActivities = sessions.filter { entry -> entry.owner === owner }
                            .mapNotNull(SessionEntry::activity)
                        sessions.removeAll { entry -> entry.owner === owner }
                        if (fallbackContact?.activity in removedActivities) fallbackContact = null
                    }
                    EmoRepoPanelDialog.dismissCurrent()
                }
            },
        )
    }

    fun currentContact(context: Context): QqContact? {
        val activity = activityFromContext(context)
        val entry = synchronized(lock) {
            sessions.lastOrNull { candidate -> candidate.activity === activity }
                ?: sessions.singleOrNull { candidate -> candidate.activity == null }
        }
        val resolved = entry?.let { session ->
            runCatching { resolveContact(context, session.aioParam) }
            .onFailure { error -> QqPanelIntegration.log("解析 QQ 当前会话失败", error) }
            .getOrNull()
        }
        if (resolved != null) return resolved
        return synchronized(lock) {
            fallbackContact?.takeIf { candidate -> candidate.activity === activity }?.contact
        }
    }

    fun updateFromMessage(message: Any, context: Context) {
        val contact = runCatching {
            val contactObject = message.javaClass.methods.firstOrNull { method ->
                method.parameterTypes.isEmpty() && method.returnType.name.endsWith(".Contact")
            }?.invoke(message) ?: return@runCatching null
            val chatType = contactObject.javaClass.getMethod("getChatType").invoke(contactObject) as Int
            val peerUid = contactObject.javaClass.getMethod("getPeerUid").invoke(contactObject) as String
            val guildId = contactObject.javaClass.getMethod("getGuildId").invoke(contactObject) as String
            QqContact(chatType, peerUid, guildId)
        }.getOrNull()
        if (contact != null && contact.chatType in SUPPORTED_CHAT_TYPES && contact.peerUid.isNotBlank()) {
            val activity = activityFromContext(context) ?: return
            synchronized(lock) { fallbackContact = FallbackContact(contact, activity) }
        }
    }

    private fun contextFromObject(value: Any?): Context? {
        if (value is Context) return value
        return value?.javaClass?.methods
            ?.firstOrNull { method ->
                method.name == "getContext" && method.parameterTypes.isEmpty() &&
                    Context::class.java.isAssignableFrom(method.returnType)
            }
            ?.let { method -> runCatching { method.invoke(value) as? Context }.getOrNull() }
    }

    private fun activityFromContext(context: Context): Activity? =
        generateSequence(context) { current ->
            (current as? ContextWrapper)?.baseContext?.takeIf { it !== current }
        }.filterIsInstance<Activity>().firstOrNull()

    private fun resolveContact(context: Context, aioParam: Any): QqContact {
        val aioSession = requireNotNull(findFieldByType(aioParam, AIO_SESSION_CLASS)?.get(aioParam)) {
            "AIOParam 缺少 AIOSession"
        }
        val aioContact = requireNotNull(findFieldByType(aioSession, AIO_CONTACT_CLASS)?.get(aioSession)) {
            "AIOSession 缺少 AIOContact"
        }
        val versionCode = hostVersionCode(context)
        val adapter = if (versionCode >= QQ_9_1_70_VERSION_CODE) NEW_CONTACT_FIELDS else OLD_CONTACT_FIELDS
        val chatType = readNamedField(aioContact, adapter.chatType) as? Int
            ?: uniqueFieldValue(aioContact, Int::class.javaPrimitiveType!!) { value -> value in 1..4 }
        val peerUid = readNamedField(aioContact, adapter.peerUid) as? String
            ?: uniqueFieldValue(aioContact, String::class.java) { value -> value.isNotBlank() }
        val guildId = (readNamedField(aioContact, adapter.guildId) as? String).orEmpty()
        require(chatType in SUPPORTED_CHAT_TYPES) { "不支持的 QQ 会话类型：$chatType" }
        require(peerUid.isNotBlank()) { "QQ 会话 peerUid 为空" }
        return QqContact(chatType, peerUid, guildId)
    }

    private fun findFieldByType(target: Any, typeName: String): Field? =
        generateSequence(target.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .firstOrNull { field -> field.type.name == typeName }
            ?.apply { isAccessible = true }

    private fun readNamedField(target: Any, name: String): Any? =
        generateSequence(target.javaClass) { current -> current.superclass }
            .mapNotNull { current -> runCatching { current.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?.get(target)

    private fun <T : Any> uniqueFieldValue(
        target: Any,
        type: Class<T>,
        predicate: (T) -> Boolean,
    ): T {
        val values = generateSequence(target.javaClass) { current -> current.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filter { field -> field.type == type }
            .mapNotNull { field ->
                field.isAccessible = true
                type.cast(field.get(target))
            }
            .filter(predicate)
            .distinct()
            .toList()
        check(values.size == 1) { "无法唯一确定 ${type.simpleName} 会话字段，候选=${values.size}" }
        return values.single()
    }

    @Suppress("DEPRECATION")
    private fun hostVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private data class SessionEntry(val owner: Any, val aioParam: Any, val activity: Activity?)
    private data class FallbackContact(val contact: QqContact, val activity: Activity)
    private data class ContactFields(val chatType: String, val peerUid: String, val guildId: String)

    private val NEW_CONTACT_FIELDS = ContactFields("d", "e", "f")
    private val OLD_CONTACT_FIELDS = ContactFields("e", "f", "g")
    private val SUPPORTED_CHAT_TYPES = setOf(1, 2, 4)
    private const val QQ_9_1_70_VERSION_CODE = 9898L
    private const val AIO_PARAM_CLASS = "com.tencent.aio.data.AIOParam"
    private const val AIO_SESSION_CLASS = "com.tencent.aio.data.AIOSession"
    private const val AIO_CONTACT_CLASS = "com.tencent.aio.data.AIOContact"
}

internal data class QqContact(
    val chatType: Int,
    val peerUid: String,
    val guildId: String,
)
