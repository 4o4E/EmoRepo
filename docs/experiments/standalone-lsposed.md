# 独立 LSPosed 能力验证

- 状态：`verified`
- 日期：2026-08-27
- 适用分支：`feat/standalone-lsposed-poc`

## 目的

验证 EmoRepo 不依赖 QAux 外部模块和 Provider SPI 时，能否作为独立 LSPosed 模块进入 Android QQ 主进程，并直接定位后续集成所需的原生接点。

本实验不改变主线中“EmoRepo 不直接 Hook QQ”的既有边界；是否采用独立模块由实验结果另行确认。

## 验证范围

- 只作用于 `com.tencent.mobileqq` 主进程。
- 使用测试机 QQ `9.1.70` 和 LSPosed `1.9.3`。
- Hook `EmoticonPanelController.getPanelDataList()`，确认 QQ 原生表情面板接点可用。
- 从 QQ 进程通过受控 ContentProvider 查询 EmoRepo 表情包，确认独立模块仍可访问 App 私有仓库。
- 在图片消息原有长按菜单前增加“添加到 EmoRepo”，不替换或拦截 QQ 自带“添加表情”。
- 点击实验菜单通过受控 IPC 将 QQ 缓存原图导入用户选择的 EmoRepo 表情包，不替换 QQ 收藏行为。
- QQ NT 图片菜单抽象类不写死混淆名；稳定类名不可用时，参考 QAux 对应目标的特征字符串和包范围，用上游 DexKit 独立定位并校验菜单类结构。
- 定位缓存保存在 EmoRepo 私有配置中，以 QQ 版本、APK 指纹和规则版本隔离；QQ 启动后后台预热。

## 明确非目标

- 不复制 QAux 的定位框架、Hook、消息菜单分发、会话跟踪或发送桥；仅参考其目标特征和结构判断，DexKit 使用上游公开依赖。
- 本轮只验证动态定位在 QQ `9.1.70` 能替代已知混淆名，不据此宣称其他 QQ 版本已经通过真机兼容测试。
- 不实现表情包展示、发送、实际导入、删除、移动或排序。
- 不把实验代码合并到主线。

## 通过条件

- LSPosed 日志确认模块只在 QQ 主进程初始化一次。
- 打开 QQ 原生表情面板后，Hook 能读取面板返回项数量。
- 同一 Hook 能通过 QQ UID 查询 EmoRepo 表情包数量和前若干稳定 ID。
- QQ 原生收藏操作发生时，日志能标识命中的服务类、方法名和参数摘要。
- 图片消息长按菜单同时保留 QQ 原项和“添加到 EmoRepo”，点击实验项可以命中独立模块回调。
- 禁用 QAux 后上述验证仍成立。

## 2026-08-27 验证结果

- `top.e404.emorepo.dev` 由 LSPosed `1.9.3` 成功加载到 QQ `9.1.70` 主进程，`io.github.qauxv.dev` 同期保持禁用。
- `EmoticonPanelController.getPanelDataList()` 连续三次命中，QQ 原生面板返回 5 个顶层项。
- QQ UID 通过正式版 `top.e404.emorepo.provider` 读取到 34 个表情包；前五项及顺序为 `riru:1024`、`可爱:2048`、`404:3072`、`9:4096`、`Kipfel:5120`。
- `FavroamingManagerServiceImpl.addCustomEmotions()`、`FavroamingDBManagerServiceImpl.deleteCustomEmotion()` 以及多个更新方法可以直接 Hook，不依赖 DexKit。
- 打开表情面板时，QQ 后台同步会批量调用 `updateCustomEmotionData()` 和 `updateCustomEmotionDataListInDB()`；这些数据库更新不是用户修改的充分证据，正式实现不能据此直接改写 EmoRepo。
- 图片消息原菜单中成功增加“添加到 EmoRepo”，点击实验项后独立模块回调命中；原生菜单项保持不变。
- 自定义菜单点击取得真实 `PicMsgItem`，消息包含 1 个 `MsgElement` 和 1 个 `PicElement`；图片消息提取链路验证通过。
- Debug 闭环使用 `PIC_DOWNLOAD_ORI` 读取 152,555 字节 QQ 缓存原图，经 PFD 调用 Dev Provider 导入 `QQ 导入`，内容识别为 JPG，MD5 为 `557598be8b7d0af5c3f35c73b67f4ada`。
- Dev 仓库的原图、包内 `index.jsonl` 和根索引一致落盘；重新打开 App 显示 1 个表情包、1 张表情及正确封面。
- 上述自动创建 Dev 仓库只用于能力验证；独立方案转为正式实现后已移除对应 IPC 和测试仓库常量，未配置用户只能通过 App 完成真实仓库设置。
- 已执行一次 QQ 原生“添加表情”，命中 `updateCustomEmotion(CustomEmotionData)`；尚未解析对象字段，也未验证删除和排序的成功边界。

## 动态定位验收

- 构建后确认 APK 包含上游 DexKit 及当前 ABI 的原生库。
- QQ 进程存在其他 DexKit 使用方时，EmoRepo 必须从隔离 ClassLoader 加载自身版本，日志不得出现 `nativeInitDexKit` JNI 不匹配。
- 清除既有定位缓存并重启 QQ，日志必须显示通过特征 `QQCustomMenuItem{title='` 定位菜单抽象类，且结构校验通过。
- 再次重启 QQ，日志必须显示命中持久缓存，不重复扫描宿主 APK。
- 两次启动都必须保留 QQ 原菜单并正常显示、点击“添加到 EmoRepo”。
- 构造一个无候选或多候选的 JVM 测试，确认不会选择未经唯一验证的类。

## 2026-08-28 动态定位结果

- QQ `9.1.70` 冷启动清除定位缓存后，使用 QAux 同目标的特征 `QQCustomMenuItem{title='` 和包范围独立定位到 `com.tencent.qqnt.aio.menu.ui.d`；扫描约 2 秒并写入宿主指纹缓存。
- 测试机另一 LSPosed 模块同时携带不同版本 DexKit；EmoRepo 从自己的 `nativeLibraryDir` 和隔离 ClassLoader 成功加载 `libdexkit.so`，未再出现 `nativeInitDexKit` JNI 不匹配。
- 重启 QQ 后缓存文件修改时间保持不变，日志没有再次加载 EmoRepo DexKit native 库或扫描宿主 APK；图片菜单实现直接基于缓存类生成。
- 图片原菜单保持可用，“添加到 EmoRepo”可点击并取得 `PicMsgItem` 的 1 个图片元素，随后正常显示含 `QQ 导入` 的表情包选择框。
- JVM 测试覆盖唯一候选、无有效候选和多个有效候选，后两者均拒绝继续定位。
- 本轮只验证 QQ `9.1.70`，其他 QQ 版本仍需真机回归；实现不包含按 `9.1.70` 写死类名的版本分支。
