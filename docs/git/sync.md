# Git 同步

- 状态：`implemented`
- 更新：2026-08-26

## 引擎和边界

- Android 端使用 JGit。
- 上层只依赖 EmoRepo 自己的 Git 接口，不直接传播 JGit 类型。
- 每个仓库同一时间最多运行一个同步任务。
- 不提供 pull-only 模式；每次同步都检查远端并在需要时 rebase。
- 不提供 Wi-Fi-only 条件。

## 触发器

| 事件 | 默认行为 |
|---|---|
| 新增、删除、移动表情 | debounce 后立即同步 |
| 修改索引、封面或顺序 | debounce 后立即同步 |
| 最近使用 CSV 变化 | 本地立即写入；同步延迟可配置，默认 30 分钟，`0` 表示每次使用后立即同步 |
| App 启动 | 执行一次同步 |
| 用户手动同步 | 立即执行 |
| 后台远端轮询 | 默认每 30 分钟执行，可配置或关闭；最低 15 分钟 |

不对使用记录同步设置 15 分钟下限：

- 延迟为 `0` 时，使用唯一 OneTimeWorkRequest 立即请求同步。
- 延迟大于 `0` 且不足 15 分钟时，使用带初始延迟的 OneTimeWorkRequest 合并记录。
- 只有与使用事件无关的周期远端轮询需要受 Android PeriodicWorkRequest 最低 15 分钟限制。

网络不可用时使用退避重试，不限制网络类型。

- 表情修改 debounce 默认为 3 秒。
- 使用记录正数延迟从第一条未同步记录开始计算固定窗口，后续使用不重置到期时间。
- 任何其他同步先发生时，一并刷新和提交尚未到期的使用记录。
- 仓库配置完成后，App 每次启动都请求同步。
- 单次任务失败最多自动重试 5 次，指数退避从 30 秒开始，上限 30 分钟；之后保留本地提交并在 App 内显示错误。

## 流水线

每个触发器进入相同流水线：

```text
刷新待写入 JSONL/CSV
→ 检查本地变更
→ git add / commit
→ fetch 上游
→ 上游有新提交时 rebase
→ 结构化自动解决冲突并继续 rebase
→ 本地领先时 push
→ 重新加载仓库状态
```

- 本地无变化时仍执行 fetch/rebase 检查。
- rebase 失败不得用 force push 或 reset 覆盖任一侧。
- push 失败保留本地提交，由下次同步重试。
- 同步失败不能阻塞本地表情读取；状态和错误交给 App 展示。
- Git 作者名和邮箱由用户按仓库配置；缺失时禁止创建提交，但允许拉取更新。
- 默认提交信息为 `Update local emoticons`，允许用户配置。

## 2026-08-26 GitHub 真机代理验证

- 创建 private 测试仓库 `4o4E/emorepo-integration-test`，从 `F:\Desktop\face` 的已提交历史复制，不包含来源工作树中未提交的 `recent/404E.csv`。
- 独立副本将旧 `index.json` 转换为当前根/包内 `index.jsonl`，并在校验字节一致后删除 2 个重复 MD5 文件；协议转换提交为 `8408305`。
- Android 测试机通过当前 VPN/代理完成 private 仓库完整 clone、创建提交、fetch/rebase、push 和第二次完整 clone 校验，耗时 290 秒。
- Android 推送提交为 `92fb9b5 test: 验证 Android 代理推送`，二次 clone 读取到相同标记内容。
- 测试使用一次性 app 私有 Token 文件；无论成功失败均在 `finally` 删除。验收后已确认临时 Token 和约 4 GiB 双克隆缓存均不存在。
