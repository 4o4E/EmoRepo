# QAux 集成

- 状态：`needs-confirmation`
- 更新：2026-08-25
- 核对基线：QAuxiliary 上游 main `805cb4f7`

## 当前外部模块加载契约

当前 QAux 源码已经确认以下事实：

1. 外部 APK 根部必须包含 `META-INF/qauxv/module.prop`。
2. 文件包含 `entry=<入口类全名>`。
3. 入口类必须实现 `Runnable`。
4. 入口构造函数签名为：

```java
Entry(String modulePath, String hostDataDir, Map<String, Method> xblService)
```

5. QAux 使用 `PathClassLoader` 加载 APK。
6. 父 ClassLoader只额外暴露 `io.github.qauxv.chainloader.*` 和 `io.github.qauxv.loader.hookapi.*`。
7. 用户需要在 QAux 外部模块设置中登记包名、启用状态和 APK 签名证书 SHA-256。
8. QAux 启动且不处于安全模式时加载已启用模块。
9. 同一 QQ 进程内，同一包名只加载一次；更新 APK 后需要重启 QQ 才能加载新代码。

核对位置：

- `app/src/main/java/io/github/qauxv/chainloader/detail/ExternalModuleChainLoader.java`
- `app/src/main/java/io/github/qauxv/chainloader/detail/ChainLoaderParentClassLoader.java`
- `app/src/main/java/io/github/qauxv/chainloader/api/ChainLoaderAgent.java`
- `app/src/main/java/io/github/qauxv/chainloader/detail/ExternalModuleManager.kt`
- `app/src/main/java/io/github/qauxv/core/MainHook.java`

当前 EmoRepo 骨架不写入 `module.prop`，因为通用表情 Provider SPI 尚不存在，入口注册逻辑还不能形成有效实现。

## QAux PR 边界

QAux PR 应加入通用表情 Provider SPI 和动态 Registry，不能引用 `top.e404.emorepo` 或只服务本项目。SPI 负责传递稳定 DTO；QAux 自己负责：

- QQ 版本和进程适配。
- 表情面板入口。
- 把 DTO 转换为 QQ 内部对象。
- 实际插入或发送图片。

EmoRepo 负责：

- 注册 Provider。
- 通过 IPC 查询 App 中的表情包、表情和图片。
- 返回 SPI 规定的数据，不接触 QQ 内部类。

## SPI 约束

- SPI 位于 QAux `io.github.qauxv.chainloader.api` 范围或其稳定子包，使当前父 ClassLoader 能解析。
- SPI 使用 Java 基础类型、稳定 DTO 和明确版本号。
- 不暴露 Kotlin `FunctionN`、`Unit`、默认参数、协程或 QQ/QAux 内部 Hook 类型。
- EmoRepo 对 SPI 使用 `compileOnly`，不得把同名 API 类打进 APK。
- Registry 必须允许外部模块在 QAux延迟 Hook 初始化后注册，消费者每次打开或刷新面板时读取当前 Registry，不能只在模块加载前复制一次列表。

## 待确认和源码工作

- QAux 具体在哪些 QQ 进程调用外部模块入口。
- Provider SPI 的类名、DTO 字段、版本协商和卸载行为。
- QAux 面板在 Provider 不可用、App 未运行或 IPC 超时时的展示。
- 外部模块配置能否由 EmoRepo 提供可复制的证书摘要提示，还是完全由 QAux UI 处理。
