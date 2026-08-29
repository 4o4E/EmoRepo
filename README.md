# EmoRepo

EmoRepo（表情仓）是面向 Android QQ 的表情仓库管理器。它负责管理本地表情、Git 同步，并作为独立 LSPosed 模块在 QQ 中提供浏览、预览、发送和图片导入能力。

当前已实现严格的 `index.jsonl` / 最近使用 CSV、表情管理、Compose 管理界面、JGit 同步、受控 Provider 和 QQ 主进程适配。

## 项目信息

- 包名：`top.e404.emorepo`
- 语言：Kotlin
- Android：minSdk 24、targetSdk 36、compileSdk 36
- 构建：JDK 17、Gradle 9.7.0、AGP 9.3.1
- Git：JGit
- 许可证：Apache-2.0
- 宿主：只支持 Android QQ

## 文档

从 [`docs/README.md`](docs/README.md) 开始阅读。可观察行为必须先在文档中确认，再进入实现。

## CLI 构建

```powershell
$env:JAVA_HOME='<JDK 17 installation directory>'
$env:ANDROID_HOME='<Android SDK installation directory>'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME

.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Debug APK 按 ABI 输出到 `app\build\outputs\apk\debug\`，包括 `app-arm64-v8a-debug.apk` 和 `app-universal-debug.apk`。
