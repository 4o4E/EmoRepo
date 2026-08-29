# QAux 集成

- 状态：`superseded`
- 更新：2026-08-26
- 核对基线：QAuxiliary 上游 main `805cb4f7`

本方案已由 [`../decisions/0005-standalone-lsposed.md`](../decisions/0005-standalone-lsposed.md) 取代，内容仅保留为历史验证记录。

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

## 入口进程边界

QAux 当前会在其完成基础初始化且未进入安全模式的每个 QQ 进程调用外部模块入口，外部模块入口本身必须收窄进程：

- QAux 加载器先初始化 `ExternalModuleEnvironment`；仅当其进程名等于 `com.tencent.mobileqq` 时注册 Provider。
- QQ 的 MSF、工具、推送等其他进程直接返回，不创建 IPC 连接、不读取仓库，也不重复注册。
- 注册只在当前 QQ 主进程内有效；QQ 主进程退出后由进程回收，不做跨进程静态状态持久化。

核对位置：

- `app/src/main/java/io/github/qauxv/chainloader/detail/ExternalModuleChainLoader.java`
- `app/src/main/java/io/github/qauxv/chainloader/detail/ChainLoaderParentClassLoader.java`
- `app/src/main/java/io/github/qauxv/chainloader/api/ChainLoaderAgent.java`
- `loader/emoticon-provider-api/src/main/java/io/github/qauxv/chainloader/api/emoticon/ExternalModuleEnvironment.java`
- `app/src/main/java/io/github/qauxv/chainloader/detail/ExternalModuleManager.kt`
- `app/src/main/java/io/github/qauxv/core/MainHook.java`

历史实现曾在 EmoRepo APK 写入 `META-INF/qauxv/module.prop` 并使用 `top.e404.emorepo.integration.qaux.EmoRepoQAuxEntry`；独立 LSPosed 方案确认后，这些入口和 QAux Provider API 编译依赖均已移除。

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

## Provider SPI V1

SPI 放在 `io.github.qauxv.chainloader.api.emoticon`，包含以下公开 Java 类型：

- `EmoticonProviderRegistry`：线程安全的进程内注册中心，公开 `API_VERSION = 1`、注册、注销和不可变快照查询。
- `IEmoticonProvider`：Provider 行为接口。
- `EmoticonProviderRegistration`：注册句柄；`close()` 只注销当前实例，重复调用无副作用。
- `EmoticonPackInfo`、`EmoticonItemInfo`：只读 DTO。
- `EmoticonProviderException`：Provider 可预期故障，携带稳定错误码和可展示的中文消息。

`IEmoticonProvider` V1 提供以下能力：

```java
String getProviderId();
String getDisplayName();
long getRevision();
List<EmoticonPackInfo> listPacks() throws EmoticonProviderException;
List<EmoticonItemInfo> listItems(String packId, int offset, int limit)
        throws EmoticonProviderException;
ParcelFileDescriptor openItem(String packId, String itemId)
        throws EmoticonProviderException;
void recordUse(String packId, String itemId, long usedAtMillis)
        throws EmoticonProviderException;
```

字段语义：

- `providerId`、`packId` 和 `itemId` 在各自作用域内稳定；EmoRepo 的 `itemId` 使用索引中的 MD5。
- `revision` 在表情包、表情元数据或图片内容发生变化后单调增加，QAux 用它识别失效缓存。
- `EmoticonPackInfo` 包含 `id`、显示名、封面表情 ID、表情数量和排序值。
- `EmoticonItemInfo` 包含 `id`、原文件名、MIME 类型、是否为动图和排序值，不包含私有绝对路径。
- `listItems` 使用从零开始的偏移量；单次 `limit` 范围为 1 至 200，非法参数直接报错。
- `openItem` 返回只读 `ParcelFileDescriptor`；调用方负责关闭，不能通过接口获得 App 私有路径。
- `recordUse` 只记录一次成功发送后的使用行为，失败发送不得写最近使用记录。

Registry 规则：

- 注册时校验 API 版本和非空字段；不兼容版本直接拒绝。
- 同一 `providerId` 的同一实例重复注册视为幂等；不同实例占用同一 ID 时拒绝，避免静默替换。
- 消费方每次打开表情面板和显式重试时读取最新快照。
- 禁用外部模块或更新 APK 后，仍以重启 QQ 作为完整卸载边界；注册句柄只用于模块主动退出和测试清理。

## QAux 面板行为

- Provider 返回的表情包直接作为 QAux 表情面板的顶层标签，与 QAux 本地表情包并列，不增加一层 EmoRepo 容器。
- 元数据和图片读取使用共享的 4 线程有界执行器；主线程只提交状态和绘制结果，不为每张图片创建独立线程池。
- App 进程未运行时，由 Android 启动导出的 Provider；这不是错误状态。
- IPC 权限拒绝、超时或 App 数据不可用时，不静默隐藏 Provider。保留以 Provider 名称标识的错误页，展示精简原因和重试按钮。
- QAux 将 `openItem` 返回的原始字节写入 QQ 缓存目录后再预览和发送，不转码、不抽取首帧，因此透明 GIF 仍按完整动画播放。
- QAux 必须按 GIF 编码中的原始帧间隔播放，支持 GIF 以 10 ms 为单位可表达的最高 100 FPS；不能把 0/10 ms 帧改成 20 ms 或 100 ms。显示设备刷新率不足时允许自然丢弃不可见的中间帧，但动画时间轴和完整循环时长不能被拉长。
- GIF 预览使用不限制 10 ms 帧的专用动画解码器，直接读取 QAux 本地原文件或 Provider 原缓存，不生成转写播放副本；静态图片继续使用 Glide。动画 Drawable 离开可视区或面板关闭时必须停止并释放，不得因支持高帧率而让不可见 GIF 持续解码。
- GIF 必须遵守文件中的循环次数；没有循环扩展的动画播放一次后停在末帧，有限循环按编码次数结束，只有无限循环素材才持续播放。
- 最近使用、本地封面和 Provider 封面都只准备当前可见项。异步解码结果必须绑定所属面板或对话框的生命周期；界面关闭、切页或请求过期后只能回收结果，不得把 Drawable 重新挂到已脱离窗口的 `ImageView`。
- 原始文件缓存按 `providerId + itemId + revision` 区分版本，采用 128 MiB 有界 LRU；超过上限时删除最久未使用的旧文件，源文件仍在 EmoRepo，需要时重新读取。该上限只属于 QAux 实现，不固化到 SPI。
- 网格图片按单元格尺寸解码，只加载当前可见行；Provider 动态封面仅进入横向标签栏可视区时加载，滑出后清理 Drawable，避免所有 GIF 封面同时常驻内存。
- 只有 QAux 确认图片发送成功后才调用 `recordUse`；记录失败只影响最近使用同步，不回滚已经发送的 QQ 消息。

## IPC 身份校验

EmoRepo 导出的受控 Provider 按 [`../android/runtime.md`](../android/runtime.md) 校验调用 UID、包名和签名。当前测试机 QQ `9.1.70` 的包名为 `com.tencent.mobileqq`，签名证书 SHA-256 为：

```text
ea6e97ad6c34f7039a9c6daba732c97d0e098e83ede2b4d52c76eb0184ac7a38
```

实现使用受支持 QQ 签名白名单并兼容 Android 签名轮换历史，不能只判断包名。EmoRepo 自身进程仅允许同 UID 调用。QAux 外部模块的证书摘要只由 QAux 外部模块设置页管理，EmoRepo 设置页不重复展示。

## 测试版 QAux 安装

当前测试机安装的是同包名 release 版，开发签名不能覆盖安装。为保留 release 版及其管理端数据，联调默认采用本地测试专用包名 `io.github.qauxv.dev`：

- release 版继续保持在 LSPosed 中禁用。
- 测试版修正 QAux 对自身包名的硬编码判断后，以独立包安装并在 LSPosed 中单独启用。
- 测试包名差异只属于本地联调配置，不进入通用 Provider SPI 的上游 PR。
- 如果独立包实测遇到 QAux 自身身份假设无法完整隔离，再单独确认是否备份并卸载 release 版；未经确认不卸载。

## 2026-08-26 真机验证

- 测试机保留 `io.github.qauxv` release 包并在 LSPosed 中禁用；`io.github.qauxv.dev` 已安装、启用且作用域只勾选 QQ。
- QAux Dev 在 QQ 主进程加载成功，EmoRepo 外部入口完成注册；MSF 等非主进程没有重复注册。
- QQ UID 成功分页读取 34 个表情包、元数据和原始图片；shell UID 查询同一 Provider 被 `SecurityException` 拒绝。
- 根目录 `index.jsonl` 临时交换 `9` 与 `404` 后，QAux 顶层标签同步显示为 `9, 404`；恢复文件后不保留测试顺序。
- 冷缓存并发创建已验证，无目录竞态；打开外部包后缓存为 55 个原文件、约 29.7 MiB，其中 4 个 GIF。
- 相隔 800 ms 的两张真机截图中多个 GIF 帧不同，证明缓存和显示链路保留完整动画，不存在首帧叠底。
- 高帧率播放不再使用 Glide 的逐帧请求调度。QAux 从原文件读取每帧延迟，在 Glide 解码头中恢复 10 ms 原始值，再由 4 线程共享解码器按绝对截止时间提前解码和换帧；解码或 UI 调度的偶发延迟会在后续帧追赶，不累加到完整循环。
- 真实 `67d178daf5015132ee1c23e13d244ee1.gif` 为 537 帧、30 个 10 ms 帧，编码循环为 17,170 ms；120 Hz 测试机首轮实测 17,182 ms、第二轮 17,173 ms，误差分别为 12 ms 和 3 ms。其他 59 至 270 帧动图的稳态循环同样与编码时间基本一致。
- 高帧率实现直接读取 Provider 原缓存，没有生成 `sticker_playback` 转写文件；透明帧截图未出现首帧叠底。连续三次切走/返回后，激活态 PSS 为 939,455、939,028、938,728 KiB，离开态后两次为 926,135、925,008 KiB，没有单调增长；GIF 解码线程固定为 4 个。
- 生命周期回归使用临时的 100 条最近记录、20 个动态本地封面、一个无限循环和一个无循环扩展 GIF：最近使用只启动当前可见区域，横向滚动只启动新进入可视区的封面；无限循环项持续换帧，无循环扩展项在末帧保持不变；面板打开 120 ms 后立即关闭，清空日志后继续观察 2 秒没有 GIF 循环输出。测试完成后已恢复原始空 `set.json`、`recent.json` 并删除临时目录。
- Provider 后台线程稳定为 4 个；反复关闭、打开动态表情包后 PSS 快照没有单调增长。QQ 自身内存波动较大，后续仍应在更长滚动场景观察 128 MiB 淘汰是否合适。
- 尚未点击发送真实 QQ 消息，因此“QQ 发送成功回调后写最近使用”和发送失败分支只完成源码检查，不能标记为真机已通过。
