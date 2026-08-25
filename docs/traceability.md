# 需求追踪

- 更新：2026-08-25

状态含义见 [`README.md`](README.md)。代码列为空表示尚未实现。

| ID | 要求 | 权威文档 | 状态 | 代码/测试 |
|---|---|---|---|---|
| BASE-001 | 项目身份为 EmoRepo / `top.e404.emorepo` | `decisions/0001-foundation.md` | implemented | Gradle、Manifest |
| BASE-002 | 业务代码使用 Kotlin | `decisions/0001-foundation.md` | implemented | App 骨架 |
| SCOPE-001 | 只支持 Android QQ | `scope.md` | confirmed | — |
| QAUX-001 | 不直接 Hook QQ，依赖通用 QAux Provider | `architecture/qaux-integration.md` | needs-confirmation | — |
| DATA-001 | 保持当前表情目录语义 | `protocol/repository.md` | confirmed | — |
| DATA-002 | `index.jsonl` 严格编解码和记录校验 | `protocol/index-jsonl.md` | verified | `IndexJsonlCodec`、`IndexJsonlCodecTest` |
| DATA-003 | `order` 是唯一最终显示顺序 | `protocol/index-jsonl.md` | confirmed | — |
| DATA-004 | 最近使用 CSV 编解码、去重和稳定排序 | `protocol/recent-csv.md` | verified | `RecentCsvCodec`、`RecentCsvCodecTest` |
| DATA-005 | 不兼容旧 `index.json` | `scope.md` | confirmed | — |
| DATA-006 | 最近使用按设备文件保存、改名和限制数量 | `protocol/recent-csv.md` | verified | `RecentUsageRepository`、`RecentUsageRepositoryTest` |
| GIT-001 | Android 使用 JGit | `decisions/0003-git-and-storage.md` | implemented | Gradle 依赖；运行时未验证 |
| SYNC-001 | 表情修改触发同步 | `git/sync.md` | confirmed | — |
| SYNC-002 | 使用记录默认最多等待 30 分钟 | `git/sync.md` | confirmed | — |
| SYNC-003 | 每次同步检查远端并按需 rebase | `git/sync.md` | confirmed | — |
| SYNC-004 | 使用记录同步延迟为 0 时每次使用后立即同步 | `git/sync.md` | confirmed | — |
| MERGE-001 | 协议冲突优先自动处理 | `git/conflicts.md` | confirmed | — |
| STORE-001 | 仓库默认位于 App 私有目录 | `android/runtime.md` | confirmed | — |
| IPC-001 | QQ 进程通过受控 IPC 读取私有仓库 | `android/runtime.md` | confirmed | — |
| UI-001 | App 使用 Compose Material 3，提供仓库和表情管理界面 | `ui/app.md` | verified | `MainActivity`、`EmoRepoTheme` |
| UI-002 | App 展示 Git 配置和同步状态 | `ui/app.md` | confirmed | — |
| UI-003 | 底部提供表情列表、添加表情、软件设置三个可返回路由 | `ui/app.md` | implemented | `EmoRepoApp`；真机验收待完成 |
| UI-004 | 表情包列表支持列表/平铺切换，两种布局均显示封面 | `ui/app.md` | implemented | `PackScreens`、`PackCoverSelectionTest`；真机验收待完成 |
| MANAGE-001 | 管理领域层支持单个和批量导入、删除、移动表情 | `management/emoticons.md` | verified | `EmoticonRepository`、`EmoticonRepositoryTest` |
| MANAGE-002 | 删除和移动同步更新本设备最近使用记录 | `management/emoticons.md` | confirmed | — |
| MANAGE-003 | 从多选框拖动，按视觉顺序连续选择起点到终点 | `management/emoticons.md` | implemented | `PackManagerScreen`、`RangeSelectionTest`；真机手势验收待完成 |
| IMAGE-001 | 缩略图使用插值过滤，禁止最近邻缩放 | `management/emoticons.md` | verified | `FilteredThumbnail` |
| STORE-002 | 单文件刷新、替换和中断恢复 | `management/persistence.md` | verified | `AtomicFileStore`、`AtomicFileStoreTest` |
| STORE-003 | 图片和多个索引之间使用可恢复事务日志 | `management/persistence.md` | confirmed | — |
