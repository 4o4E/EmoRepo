package top.e404.emorepo.experiment.lsposed

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * 仅由隔离 ClassLoader 反射调用，避免 QQ 内其他模块携带的 DexKit 与本模块发生类或 JNI 冲突。
 */
internal object IsolatedDexKitRunner {
    private val bridgeLock = Any()
    private var bridgePath: String? = null
    private var sharedBridge: DexKitBridge? = null

    init {
        System.loadLibrary("dexkit")
    }

    @JvmStatic
    fun findClassesUsingString(
        apkPath: String,
        traitString: String,
        packagePrefix: String,
    ): Array<String> = withBridge(apkPath) { bridge ->
        bridge.batchFindMethodUsingStrings {
            groups(
                mapOf(TARGET_GROUP to setOf(traitString)),
                StringMatchType.Equals,
            )
        }[TARGET_GROUP].orEmpty()
            .map { it.className }
            .filter { it.startsWith(packagePrefix) }
            .distinct()
            .toTypedArray()
    }

    @JvmStatic
    fun findMethodDescriptorsUsingStrings(
        apkPath: String,
        traitStrings: Array<String>,
        packagePrefix: String,
        matchAll: Boolean,
    ): Array<String> = withBridge(apkPath) { bridge ->
        val groups = if (matchAll) {
            mapOf(TARGET_GROUP to traitStrings.toSet())
        } else {
            traitStrings.mapIndexed { index, value -> "$TARGET_GROUP-$index" to setOf(value) }.toMap()
        }
        bridge.batchFindMethodUsingStrings {
            groups(groups, StringMatchType.Equals)
        }.values
            .asSequence()
            .flatten()
            .filter { packagePrefix.isBlank() || it.className.startsWith(packagePrefix) }
            .map { it.descriptor }
            .distinct()
            .toList()
            .toTypedArray()
    }

    /** 同一 QQ APK 只解析一次；查询串行化，避免多个目标并发操作同一个 native bridge。 */
    private fun <T> withBridge(apkPath: String, block: (DexKitBridge) -> T): T =
        synchronized(bridgeLock) {
            if (bridgePath != apkPath || sharedBridge == null) {
                sharedBridge?.close()
                sharedBridge = DexKitBridge.create(apkPath)
                bridgePath = apkPath
            }
            block(requireNotNull(sharedBridge))
        }

    private const val TARGET_GROUP = "emorepo_target"
}
