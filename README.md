# EmoRepo

EmoRepo（表情仓）是面向 Android QQ 的开发者自用表情仓库管理器。它负责管理本地表情、Git 同步和向 QAuxiliary 提供表情数据；QQ 定位、Hook 和发送适配由 QAuxiliary 负责。

当前已实现严格的 `index.jsonl` / 最近使用 CSV、表情管理领域层和 Compose 管理界面。Git 同步、QAux Provider 及 QQ 进程集成仍在开发中。

## 项目信息

- 包名：`top.e404.emorepo`
- 语言：Kotlin；QAux 公共 SPI 允许使用少量 Java
- Android：minSdk 24、targetSdk 36、compileSdk 37.0
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

Debug APK 输出到 `app\build\outputs\apk\debug\app-debug.apk`。
