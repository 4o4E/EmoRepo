# 需求追踪

- 更新：2026-08-27

状态含义见 [`README.md`](README.md)。代码列为空表示尚未实现。

| ID | 要求 | 权威文档 | 状态 | 代码/测试 |
|---|---|---|---|---|
| BASE-001 | 项目身份为 EmoRepo / `top.e404.emorepo` | `decisions/0001-foundation.md` | implemented | Gradle、Manifest |
| BASE-002 | 业务代码使用 Kotlin | `decisions/0001-foundation.md` | implemented | App 骨架 |
| SCOPE-001 | 只支持 Android QQ | `scope.md` | confirmed | — |
| QAUX-001 | 不直接 Hook QQ，依赖通用 QAux Provider | `architecture/qaux-integration.md` | implemented | `EmoRepoQAuxEntry`、`EmoRepoQAuxProvider`；QAux Dev 真机注册/表情包/GIF 已通过，真实发送待验收 |
| QAUX-002 | QAux 本地和 Provider 的 GIF 按原始时间轴播放并支持最高 100 FPS，发送仍使用原文件 | `architecture/qaux-integration.md` | verified | QAux `GifFrameTiming`、`GifLoopController`、`ExactTimingGifDecoder`、`StickerGifDrawable`；6 项 JVM 测试、Debug APK 构建、537 帧双循环时长、100 条最近记录/20 个封面可见性、有限循环与快速关闭真机验收 |
| DATA-001 | 保持当前表情目录语义 | `protocol/repository.md` | confirmed | — |
| DATA-002 | `index.jsonl` 严格编解码和记录校验 | `protocol/index-jsonl.md` | verified | `IndexJsonlCodec`、`IndexJsonlCodecTest` |
| DATA-003 | `order` 是唯一最终显示顺序 | `protocol/index-jsonl.md` | confirmed | — |
| DATA-004 | 最近使用 CSV 编解码、去重和稳定排序 | `protocol/recent-csv.md` | verified | `RecentCsvCodec`、`RecentCsvCodecTest` |
| DATA-005 | 不兼容旧 `index.json` | `scope.md` | confirmed | — |
| DATA-006 | 最近使用按设备文件保存、改名和限制数量 | `protocol/recent-csv.md` | verified | `RecentUsageRepository`、`RecentUsageRepositoryTest` |
| DATA-007 | 根目录 `index.jsonl` 控制 App 和 QAux 的表情包顺序 | `protocol/root-index-jsonl.md` | verified | `RootIndexJsonlCodec`、`EmoticonRepository`、`ProtocolConflictResolver`；编解码/仓库/JGit 冲突测试和 QAux 真机顺序验收 |
| GIT-001 | Android 使用 JGit | `decisions/0003-git-and-storage.md` | verified | `JGitRepositoryService`、`JGitRepositoryServiceTest`、`GitHubProxyIntegrationTest`；private GitHub 真机 clone/push/二次 clone 验收 |
| AUTH-001 | HTTPS Token 使用 Android Keystore 保护且不回显 | `git/authentication.md` | verified | `KeystoreTokenStore`；Android 真机密文写入/清除验收 |
| SYNC-001 | 表情修改触发同步 | `git/sync.md` | implemented | `GitSyncScheduler`、`EmoRepoState.manage`；Android 网络任务待验证 |
| SYNC-002 | 使用记录默认最多等待 30 分钟 | `git/sync.md` | confirmed | — |
| SYNC-003 | 每次同步检查远端并按需 rebase | `git/sync.md` | implemented | `JGitRepositoryService`、本地 bare Git 端到端测试；Android HTTPS 待验证 |
| SYNC-004 | 使用记录同步延迟为 0 时每次使用后立即同步 | `git/sync.md` | confirmed | — |
| MERGE-001 | 协议冲突优先自动处理 | `git/conflicts.md` | verified | `ProtocolConflictResolver`、`ProtocolConflictResolverTest`、JGit rebase 冲突测试 |
| STORE-001 | 仓库默认位于 App 私有目录 | `android/runtime.md` | confirmed | — |
| IPC-001 | QQ 进程通过受控 IPC 读取私有仓库 | `android/runtime.md` | implemented | `EmoRepoContentProvider`、`CallerVerifier`；QQ UID 读取和 shell UID 拒绝已真机验证，最近使用回写待验收 |
| UI-001 | App 使用 Compose Material 3，提供仓库和表情管理界面 | `ui/app.md` | verified | `MainActivity`、`EmoRepoTheme` |
| UI-002 | App 展示 Git 配置和同步状态 | `ui/app.md` | implemented | `SettingsScreen`、`SyncStatus`；Android 网络状态待验证 |
| UI-003 | 底部提供表情列表、添加表情、软件设置三个可返回路由 | `ui/app.md` | verified | `EmoRepoApp`；Android 真机路由验收 |
| UI-004 | 表情包列表支持列表/平铺切换，两种布局均显示封面 | `ui/app.md` | verified | `PackScreens`、`PackCoverSelectionTest`；Android 真机列表/平铺验收 |
| UI-005 | 表情项只显示图片且无常驻容器背景，GIF 封面和预览自动播放 | `ui/app.md` | verified | `EmoticonPreview`；Android 真机封面/网格/大图双帧和透明表情项验收 |
| UI-006 | 多选模式显式进入后才显示多选框，关闭时清空选择 | `ui/app.md` | verified | `PackManagerScreen`、`SelectionTest`；Android 真机选择模式验收 |
| UI-007 | 列表/平铺使用仅含图标的 Material 3 单选分段按钮组 | `ui/app.md` | verified | `PackLayoutSelector`；Android 真机切换验收 |
| UI-008 | 表情包内固定四列并提供可分页、缩放和拖动的全屏预览 | `ui/app.md` | implemented | `FullScreenPreview`；Android 四列/分页/双击缩放/拖动/GIF 验收，双指待用户确认 |
| UI-009 | 首次使用通过三步引导完成必要仓库、身份和设备配置 | `ui/app.md` | implemented | `OnboardingScreen`；Android 引导和校验验收，HTTPS 克隆待联网验证 |
| UI-010 | 设置页只展示并修改已经接入实际行为的配置 | `ui/app.md` | implemented | `SettingsScreen`、`SettingsStore`；Android 保存和 Token 验收，网络同步待验证 |
| UI-011 | 图片导入支持多选并在写入前二次确认目标和数量 | `ui/app.md` | verified | `ImportConfirmationDialog`；Android 双文件选择/取消不写入验收 |
| UI-012 | 主页布局切换位于内容首行，卡片使用紧凑封面和底部信息条 | `ui/app.md` | verified | `PackLayoutSelector`、`PackGridLayout`；Android 列表/平铺验收 |
| UI-013 | 新建表情包入口位于添加表情页面 | `ui/app.md` | verified | `AddEmoticonsScreen`；Android 新建弹窗验收 |
| UI-014 | 顶级页面移除 TopAppBar，底部路由仅以图标和文字颜色表达选中状态 | `ui/app.md` | verified | `EmoRepoApp`；Android 底部路由验收 |
| UI-015 | 表情包平铺固定四列，首行显示表情包数和表情总数 | `ui/app.md` | verified | `PackGridLayout`；Android 四列和统计行验收 |
| UI-016 | 全屏预览长按支持原文件导出和临时授权转发 | `ui/app.md` | verified | `FullScreenPreview`、`FileProvider`；Android 保存选择器和 Sharesheet URI 授权验收 |
| UI-017 | 软件设置底部路由使用矢量齿轮图标 | `ui/app.md` | verified | `ic_settings.xml`；Android 底部路由验收 |
| UI-018 | 主页表情包支持长按卡片拖动并在完成时持久化根索引顺序 | `ui/app.md` | verified | `PackCollection`、`EmoRepoState.reorderPacks`、`PackReorderTest`；首次长按、模式内直接连续拖动、取消/返回/完成及哈希真机验收 |
| UI-019 | 添加页复用表情包列表/四列平铺和布局切换 | `ui/app.md` | verified | `PackCollection`、`PackLayoutSelector`；共享布局、选择高亮和导入按钮真机验收 |
| IMAGE-002 | 缩略图使用有界内存/磁盘缓存、并发去重和平台动画解码器加速 | `management/emoticons.md` | verified | `ThumbnailCache`、Coil 3.4；Android 冷/热打开、滚动内存和 GIF 双帧验收 |
| MANAGE-001 | 管理领域层支持单个和批量导入、删除、移动表情 | `management/emoticons.md` | verified | `EmoticonRepository`、`EmoticonRepositoryTest` |
| MANAGE-002 | 删除和移动同步更新本设备最近使用记录 | `management/emoticons.md` | implemented | `EmoRepoState.deleteEmoticons`、`moveEmoticons` |
| MANAGE-003 | 从多选框拖动，按视觉顺序连续选择起点到终点 | `management/emoticons.md` | implemented | `PackManagerScreen`、`RangeSelectionTest`；真机手势验收待完成 |
| IMAGE-001 | 缩略图使用插值过滤，禁止最近邻缩放 | `management/emoticons.md` | verified | `FilteredThumbnail` |
| STORE-002 | 单文件刷新、替换和中断恢复 | `management/persistence.md` | verified | `AtomicFileStore`、`AtomicFileStoreTest` |
| STORE-003 | 图片和多个索引之间使用可恢复事务日志 | `management/persistence.md` | confirmed | — |
| RELEASE-001 | 普通提交构建可调试 dev 渠道并只上传 Actions Artifact | `release/ci-and-updates.md` | verified | `android-dev.yml`、`collect-apks.sh`；run `33034944943` 的五 APK Artifact、SHA-256、dev 包名/版本/调试状态和持久签名验收 |
| RELEASE-002 | `v*` 标签构建签名 ABI/universal APK 并发布 GitHub Release | `release/ci-and-updates.md` | verified | `android-release.yml`；`v0.1.0` run `33035704550`、五个签名 APK 和 GitHub Release 独立下载验收 |
| RELEASE-003 | Release 附带含版本、下载地址和 SHA-256 的更新索引 | `release/ci-and-updates.md` | verified | `generate-release-index.sh`；`release-index.json` 的 tag/commit/版本/五 ABI/尺寸/SHA-256/证书/下载地址独立验收 |
