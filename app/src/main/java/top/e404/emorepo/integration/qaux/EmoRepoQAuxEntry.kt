package top.e404.emorepo.integration.qaux

import android.util.Log
import io.github.qauxv.chainloader.api.emoticon.EmoticonProviderRegistration
import io.github.qauxv.chainloader.api.emoticon.EmoticonProviderRegistry
import io.github.qauxv.chainloader.api.emoticon.ExternalModuleEnvironment
import java.lang.reflect.Method

class EmoRepoQAuxEntry(
    @Suppress("UNUSED_PARAMETER") modulePath: String,
    @Suppress("UNUSED_PARAMETER") hostDataDir: String?,
    @Suppress("UNUSED_PARAMETER") xblService: Map<String, Method>?,
) : Runnable {
    override fun run() {
        if (ExternalModuleEnvironment.getProcessName() != QQ_MAIN_PROCESS) return
        synchronized(LOCK) {
            if (registration != null) return
            val provider = EmoRepoQAuxProvider(ExternalModuleEnvironment.getHostApplication())
            registration = EmoticonProviderRegistry.register(
                EmoticonProviderRegistry.API_VERSION,
                provider,
            )
            Log.i(TAG, "已在 QQ 主进程注册 EmoRepo 表情 Provider")
        }
    }

    private companion object {
        const val QQ_MAIN_PROCESS = "com.tencent.mobileqq"
        const val TAG = "EmoRepoQAux"
        val LOCK = Any()

        @Volatile
        var registration: EmoticonProviderRegistration? = null
    }
}
