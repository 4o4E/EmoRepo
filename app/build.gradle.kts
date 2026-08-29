import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { input -> load(input) }
}
val baseVersion = requireNotNull(versionProperties.getProperty("baseVersion")) {
    "version.properties 缺少 baseVersion"
}
val baseVersionParts = baseVersion.split('.').map { part ->
    part.toIntOrNull() ?: error("baseVersion 必须是 SemVer：$baseVersion")
}
require(
    baseVersionParts.size == 3 &&
        baseVersionParts.all { it >= 0 } &&
        baseVersionParts[1] <= 999 &&
        baseVersionParts[2] <= 999,
) {
    "baseVersion 必须是 SemVer，且 minor/patch 必须小于 1000：$baseVersion"
}
val baseVersionCodeLong =
    baseVersionParts[0].toLong() * 1_000_000L + baseVersionParts[1] * 1_000L + baseVersionParts[2]
require(baseVersionCodeLong in 1..Int.MAX_VALUE.toLong()) {
    "baseVersion 对应的 versionCode 越界：$baseVersionCodeLong"
}
val baseVersionCode = baseVersionCodeLong.toInt()
val configuredVersionName = providers.gradleProperty("emorepo.versionName")
    .orElse("$baseVersion-dev")
val configuredVersionCode = providers.gradleProperty("emorepo.versionCode")
    .map { value -> value.toIntOrNull() ?: error("emorepo.versionCode 必须是整数") }
    .orElse(baseVersionCode)
val ciKeystorePath = providers.environmentVariable("EMOREPO_KEYSTORE_PATH").orNull
val ciStorePassword = providers.environmentVariable("EMOREPO_STORE_PASSWORD").orNull
val ciKeyAlias = providers.environmentVariable("EMOREPO_KEY_ALIAS").orNull
val ciKeyPassword = providers.environmentVariable("EMOREPO_KEY_PASSWORD").orNull
val signingValues = listOf(ciKeystorePath, ciStorePassword, ciKeyAlias, ciKeyPassword)
val hasCiSigning = signingValues.any { value -> !value.isNullOrBlank() }

if (hasCiSigning && signingValues.any { value -> value.isNullOrBlank() }) {
    error("CI 签名配置不完整，必须同时提供 keystore、store password、alias 和 key password")
}

android {
    namespace = "top.e404.emorepo"

    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "top.e404.emorepo"
        minSdk = 24
        targetSdk = 36
        versionCode = configuredVersionCode.get()
        versionName = configuredVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                storeFile = file(requireNotNull(ciKeystorePath))
                storePassword = requireNotNull(ciStorePassword)
                keyAlias = requireNotNull(ciKeyAlias)
                keyPassword = requireNotNull(ciKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        getByName("release") {
            isDebuggable = false
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        // 独立 ClassLoader 需要真实 nativeLibraryDir，不能只依赖 APK 内的未解压库。
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    compileOnly("de.robv.android.xposed:api:82")
    implementation("net.bytebuddy:byte-buddy:1.18.12-jdk5")
    implementation("net.bytebuddy:byte-buddy-android:1.18.12") {
        exclude(group = "net.bytebuddy", module = "byte-buddy")
    }
    implementation("org.luckypray:dexkit:2.2.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("me.saket.telephoto:zoomable:0.19.0")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-gif:3.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
