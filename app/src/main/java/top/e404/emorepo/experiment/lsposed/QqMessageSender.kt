package top.e404.emorepo.experiment.lsposed

import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 使用 QQ NT 稳定接口构建图片元素并发送，不模拟点击 QQ 输入栏。 */
internal object QqMessageSender {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun send(
        hostClassLoader: ClassLoader,
        contact: QqContact,
        file: File,
        callback: (SendResult) -> Unit,
    ) {
        runCatching {
            check(file.isFile && file.length() > 0L) { "待发送表情文件不存在" }
            val contactObject = createContact(hostClassLoader, contact)
            val element = createPictureElement(hostClassLoader, contact, file)
            applyEmoticonPictureSubtype(element, contact.chatType)
            val service = kernelMessageService(hostClassLoader)
            val serverTime = serverTime(hostClassLoader)
            val uniqueId = service.javaClass.methods.firstOrNull { method ->
                method.name == "generateMsgUniqueId" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType),
                    )
            }?.invoke(service, contact.chatType, serverTime) as? Long
                ?: error("QQ 不支持生成消息唯一 ID")
            val callbackClass = Class.forName(IOPERATE_CALLBACK_CLASS, false, hostClassLoader)
            val completed = AtomicBoolean(false)
            val callbackProxy = Proxy.newProxyInstance(
                hostClassLoader,
                arrayOf(callbackClass),
            ) { _, method, args ->
                val code = args.orEmpty().firstOrNull { it is Number }?.let { (it as Number).toInt() }
                if (code != null && completed.compareAndSet(false, true)) {
                    val message = args.orEmpty().firstOrNull { it is String } as? String
                    mainHandler.post { callback(SendResult(code, message)) }
                }
                defaultValue(method.returnType)
            }
            val elements = ArrayList<Any>().apply { add(element) }
            val attributes = createMessageAttributes(hostClassLoader)
            val sendMethod = service.javaClass.methods.singleOrNull { method ->
                method.name == "sendMsg" && method.parameterTypes.size == 5 &&
                    method.parameterTypes[0] == Long::class.javaPrimitiveType &&
                    method.parameterTypes[1].isInstance(contactObject) &&
                    List::class.java.isAssignableFrom(method.parameterTypes[2]) &&
                    Map::class.java.isAssignableFrom(method.parameterTypes[3]) &&
                    method.parameterTypes[4].isAssignableFrom(callbackClass)
            } ?: service.javaClass.methods.single { method ->
                method.name == "sendMsg" && method.parameterTypes.size == 5 &&
                    method.parameterTypes[0] == Long::class.javaPrimitiveType
            }
            sendMethod.invoke(service, uniqueId, contactObject, elements, attributes, callbackProxy)
            mainHandler.postDelayed({
                if (completed.compareAndSet(false, true)) {
                    callback(SendResult(-2, "QQ 发送回调超时"))
                }
            }, SEND_CALLBACK_TIMEOUT_MILLIS)
        }.onFailure { error ->
            QqPanelIntegration.log("调用 QQ 图片发送接口失败", error)
            mainHandler.post { callback(SendResult(-1, error.message ?: "QQ 图片发送失败")) }
        }
    }

    private fun createContact(classLoader: ClassLoader, contact: QqContact): Any {
        val contactClass = loadKernelClass(classLoader, "Contact")
        return contactClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        ).newInstance(contact.chatType, contact.peerUid, contact.guildId)
    }

    private fun createPictureElement(
        classLoader: ClassLoader,
        contact: QqContact,
        file: File,
    ): Any {
        val helperClass = Class.forName(MSG_UTIL_CLASS, false, classLoader)
        val helper = helperClass.getDeclaredConstructor().newInstance()
        val methodName = if (contact.chatType == 4) "createPicElementForGuild" else "createPicElement"
        val method = helperClass.methods.single { method ->
            method.name == methodName && method.parameterTypes.contentEquals(
                arrayOf(
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ),
            )
        }
        return requireNotNull(method.invoke(helper, file.absolutePath, true, 0)) {
            "QQ 未能构建图片消息"
        }
    }

    private fun kernelMessageService(classLoader: ClassLoader): Any {
        val mobileQqClass = Class.forName(MOBILE_QQ_CLASS, false, classLoader)
        val mobileQq = requireNotNull(
            mobileQqClass.getDeclaredField("sMobileQQ").apply { isAccessible = true }.get(null),
        ) { "QQ MobileQQ 尚未初始化" }
        val runtime = requireNotNull(
            mobileQqClass.getDeclaredField("mAppRuntime").apply { isAccessible = true }.get(mobileQq),
        ) { "QQ AppRuntime 尚未初始化" }
        val kernelInterface = Class.forName(KERNEL_SERVICE_INTERFACE, false, classLoader)
        val getRuntimeService = runtime.javaClass.getMethod(
            "getRuntimeService",
            Class::class.java,
            String::class.java,
        )
        val kernelService = requireNotNull(getRuntimeService.invoke(runtime, kernelInterface, "")) {
            "QQ KernelService 不可用"
        }
        val msgService = requireNotNull(
            kernelService.javaClass.getMethod("getMsgService").invoke(kernelService),
        ) { "QQ MsgService 不可用" }
        return runCatching { msgService.javaClass.getMethod("getService").invoke(msgService) }.getOrNull()
            ?: msgService.javaClass.methods.first { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType.name.endsWith("IKernelMsgService")
            }.invoke(msgService)
    }

    private fun applyEmoticonPictureSubtype(element: Any, chatType: Int) {
        val picElement = requireNotNull(
            element.javaClass.methods.firstOrNull { method ->
                method.name == "getPicElement" && method.parameterTypes.isEmpty()
            }?.invoke(element),
        ) { "QQ 图片消息缺少 PicElement" }
        val getSubtype = picElement.javaClass.methods.firstOrNull { method ->
            method.name == "getPicSubType" && method.parameterTypes.isEmpty()
        } ?: error("QQ PicElement 不支持读取图片子类型")
        val original = (getSubtype.invoke(picElement) as Number).toInt()
        val target = emoticonPicSubtype(chatType, original)
        if (target != original) {
            val setter = picElement.javaClass.methods.firstOrNull { method ->
                method.name == "setPicSubType" && method.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType),
                )
            } ?: error("QQ PicElement 不支持设置图片子类型")
            setter.invoke(picElement, target)
        }
        QqPanelIntegration.log("QQ 表情图片子类型：original=$original，final=$target")
    }

    private fun serverTime(classLoader: ClassLoader): Long {
        val clazz = Class.forName(SERVER_TIME_CLASS, false, classLoader)
        return clazz.methods.single { method ->
            method.name == "getServerTimeMillis" && Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() && method.returnType == Long::class.javaPrimitiveType
        }.invoke(null) as Long
    }

    private fun createMessageAttributes(classLoader: ClassLoader): HashMap<Int, Any> = runCatching {
        val plate = instantiateWithDefaults(loadKernelClass(classLoader, "VASMsgNamePlate")) { index, type ->
            when {
                type == Int::class.javaPrimitiveType && index == 0 -> 258
                type == Int::class.javaPrimitiveType && index == 1 -> 64
                type == Int::class.javaPrimitiveType && index == 6 -> 258
                else -> defaultValue(type)
            }
        }
        val bubble = instantiateWithDefaults(loadKernelClass(classLoader, "VASMsgBubble"))
        val pendant = instantiateWithDefaults(loadKernelClass(classLoader, "VASMsgAvatarPendant"))
        val font = instantiateWithDefaults(loadKernelClass(classLoader, "VASMsgFont"))
        val iceBreak = instantiateWithDefaults(loadKernelClass(classLoader, "VASMsgIceBreak"))
        val vasElementClass = loadKernelClass(classLoader, "VASMsgElement")
        val vasByType = listOf(plate, bubble, pendant, font, iceBreak).associateBy { it.javaClass }
        val vasElement = instantiateWithDefaults(vasElementClass) { _, type ->
            vasByType.entries.firstOrNull { (clazz, _) -> type.isAssignableFrom(clazz) }?.value
                ?: defaultValue(type)
        }
        val attributeClass = loadKernelClass(classLoader, "MsgAttributeInfo")
        val attribute = instantiateWithDefaults(attributeClass) { _, type ->
            if (type.isAssignableFrom(vasElementClass)) vasElement else defaultValue(type)
        }
        HashMap<Int, Any>().apply { put(0, attribute) }
    }.onFailure { error ->
        QqPanelIntegration.log("创建 QQ 消息属性失败，将使用空属性", error)
    }.getOrElse { HashMap() }

    private fun instantiateWithDefaults(
        type: Class<*>,
        value: (Int, Class<*>) -> Any? = { _, parameter -> defaultValue(parameter) },
    ): Any {
        val constructor = type.declaredConstructors.minByOrNull { it.parameterTypes.size }
            ?: error("${type.name} 没有构造器")
        constructor.isAccessible = true
        val args = constructor.parameterTypes.mapIndexed(value).toTypedArray()
        return constructor.newInstance(*args)
    }

    private fun loadKernelClass(classLoader: ClassLoader, simpleName: String): Class<*> =
        runCatching {
            Class.forName("com.tencent.qqnt.kernel.nativeinterface.$simpleName", false, classLoader)
        }.getOrElse {
            Class.forName("com.tencent.qqnt.kernelpublic.nativeinterface.$simpleName", false, classLoader)
        }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> 0.toChar()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        ArrayList::class.java, List::class.java -> ArrayList<Any>()
        else -> if (List::class.java.isAssignableFrom(type)) ArrayList<Any>() else null
    }

    private const val MOBILE_QQ_CLASS = "mqq.app.MobileQQ"
    private const val KERNEL_SERVICE_INTERFACE = "com.tencent.qqnt.kernel.api.IKernelService"
    private const val MSG_UTIL_CLASS = "com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl"
    private const val SERVER_TIME_CLASS = "com.tencent.mobileqq.msf.core.NetConnInfoCenter"
    private const val IOPERATE_CALLBACK_CLASS =
        "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback"
    private const val SEND_CALLBACK_TIMEOUT_MILLIS = 60_000L
}

internal fun emoticonPicSubtype(chatType: Int, originalSubtype: Int): Int = when {
    chatType == 4 -> originalSubtype
    originalSubtype != 0 -> originalSubtype
    else -> 7
}

internal data class SendResult(val code: Int, val message: String?) {
    val successful: Boolean get() = code == 0
}
