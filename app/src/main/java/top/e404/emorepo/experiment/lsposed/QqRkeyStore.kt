package top.e404.emorepo.experiment.lsposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 监听 QQ 富媒体 rkey 响应。只解析协议中需要的长度字段，不依赖 QAux 的 Proto 实现。
 */
internal object QqRkeyStore {
    @Volatile
    private var groupRkey: String? = null

    @Volatile
    private var privateRkey: String? = null

    fun install(hostClassLoader: ClassLoader) {
        val handlerClass = Class.forName(RESPONSE_HANDLER_CLASS, false, hostClassLoader)
        val hooks = XposedBridge.hookAllMethods(
            handlerClass,
            RESPONSE_METHOD,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val response = findResponse(param.args) ?: return
                    val command = response.javaClass.methods.firstOrNull { method ->
                        method.name == "getServiceCmd" && method.parameterTypes.isEmpty()
                    }?.invoke(response) as? String ?: return
                    if (command != RKEY_COMMAND) return
                    val buffer = response.javaClass.methods.firstOrNull { method ->
                        method.name == "getWupBuffer" && method.parameterTypes.isEmpty()
                    }?.invoke(response) as? ByteArray ?: return
                    runCatching { parseRkeys(unpack(buffer)) }
                        .onSuccess { keys ->
                            groupRkey = keys.group
                            privateRkey = keys.private
                            QqPanelIntegration.log(
                                "已更新 QQ 富媒体 rkey：group=${keys.group.length} private=${keys.private.length}",
                            )
                        }
                        .onFailure { error -> QqPanelIntegration.log("解析 QQ 富媒体 rkey 失败", error) }
                }
            },
        )
        check(hooks.isNotEmpty()) { "QQ rkey 响应方法不存在" }
    }

    fun downloadRkey(group: Boolean): String? =
        (if (group) groupRkey else privateRkey)?.takeIf(String::isNotBlank)

    internal fun parseRkeys(payload: ByteArray): Rkeys {
        val level1 = ProtoFields.lengthDelimited(payload, 4).singleOrNull()
            ?: error("rkey 响应缺少 field 4")
        val level2 = ProtoFields.lengthDelimited(level1, 4).singleOrNull()
            ?: error("rkey 响应缺少 field 4.4")
        val entries = ProtoFields.lengthDelimited(level2, 1)
        check(entries.size >= 2) { "rkey 响应条目不足" }
        val keys = entries.take(2).map { entry ->
            val value = ProtoFields.lengthDelimited(entry, 1).singleOrNull()
                ?.toString(Charsets.UTF_8)
                ?.takeIf { it.isNotBlank() && it.length <= MAXIMUM_RKEY_LENGTH }
                ?: error("rkey 条目无效")
            value
        }
        return Rkeys(keys[0], keys[1])
    }

    private fun unpack(buffer: ByteArray): ByteArray =
        if (buffer.size >= 4 && buffer[0] == 0.toByte()) buffer.copyOfRange(4, buffer.size) else buffer

    private fun findNamedField(target: Any, name: String): Any? =
        generateSequence(target.javaClass) { current -> current.superclass }
            .mapNotNull { current -> runCatching { current.getDeclaredField(name) }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?.get(target)

    private fun findResponse(args: Array<Any?>): Any? {
        args.filterNotNull().firstOrNull { value ->
            value.javaClass.name.endsWith(".FromServiceMsg")
        }?.let { return it }
        for (wrapper in args.filterNotNull()) {
            findNamedField(wrapper, "fromServiceMsg")?.let { return it }
            val typedField = generateSequence(wrapper.javaClass) { current -> current.superclass }
                .flatMap { current -> current.declaredFields.asSequence() }
                .firstOrNull { field -> field.type.name.endsWith(".FromServiceMsg") }
            if (typedField != null) {
                typedField.isAccessible = true
                typedField.get(wrapper)?.let { return it }
            }
            val getter = wrapper.javaClass.methods.firstOrNull { method ->
                method.parameterTypes.isEmpty() && method.returnType.name.endsWith(".FromServiceMsg")
            }
            getter?.invoke(wrapper)?.let { return it }
        }
        return null
    }

    internal data class Rkeys(val group: String, val private: String)

    private object ProtoFields {
        fun lengthDelimited(bytes: ByteArray, wantedField: Int): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            var index = 0
            while (index < bytes.size) {
                val (tag, afterTag) = readVarint(bytes, index)
                index = afterTag
                val field = (tag ushr 3).toInt()
                when ((tag and 0x07).toInt()) {
                    0 -> index = readVarint(bytes, index).second
                    1 -> index = checkedAdvance(bytes, index, 8)
                    2 -> {
                        val (lengthValue, afterLength) = readVarint(bytes, index)
                        check(lengthValue <= Int.MAX_VALUE.toLong()) { "protobuf 字段过长" }
                        val end = checkedAdvance(bytes, afterLength, lengthValue.toInt())
                        if (field == wantedField) result += bytes.copyOfRange(afterLength, end)
                        index = end
                    }
                    5 -> index = checkedAdvance(bytes, index, 4)
                    else -> error("不支持的 protobuf wire type")
                }
            }
            return result
        }

        private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var index = start
            while (index < bytes.size && shift <= 63) {
                val current = bytes[index++].toInt() and 0xff
                value = value or ((current and 0x7f).toLong() shl shift)
                if (current and 0x80 == 0) return value to index
                shift += 7
            }
            error("protobuf varint 损坏")
        }

        private fun checkedAdvance(bytes: ByteArray, start: Int, length: Int): Int {
            check(length >= 0 && start >= 0 && start <= bytes.size - length) { "protobuf 字段越界" }
            return start + length
        }
    }

    private const val RESPONSE_HANDLER_CLASS = "mqq.app.msghandle.MsgRespHandler"
    private const val RESPONSE_METHOD = "dispatchRespMsg"
    private const val RKEY_COMMAND = "OidbSvcTrpcTcp.0x9067_202"
    private const val MAXIMUM_RKEY_LENGTH = 4096
}
