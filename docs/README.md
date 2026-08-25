# 文档索引

本文档集是 EmoRepo 行为的权威来源。根 `README.md` 只做项目入口，不重复协议和算法。

## 阅读顺序

1. [`scope.md`](scope.md)：范围和非目标。
2. [`architecture/overview.md`](architecture/overview.md)：组件边界和进程关系。
3. [`protocol/repository.md`](protocol/repository.md)：仓库目录。
4. [`protocol/index-jsonl.md`](protocol/index-jsonl.md)：表情索引。
5. [`protocol/recent-csv.md`](protocol/recent-csv.md)：最近使用记录。
6. [`git/sync.md`](git/sync.md)：触发器和同步流水线。
7. [`git/conflicts.md`](git/conflicts.md)：自动冲突处理。
8. [`architecture/qaux-integration.md`](architecture/qaux-integration.md)：QAux 加载和 Provider 边界。
9. [`android/runtime.md`](android/runtime.md)：私有存储、IPC 和后台任务。
10. [`ui/app.md`](ui/app.md)：App 页面和状态。
11. [`management/emoticons.md`](management/emoticons.md)：表情管理操作。
12. [`management/persistence.md`](management/persistence.md)：管理写入和中断恢复。
13. [`traceability.md`](traceability.md)：确认和实现状态。

## 状态

- `draft`：内容未完成。
- `needs-confirmation`：方案完整，但含待用户确认的行为；不可实现。
- `confirmed`：用户已确认，可以实现。
- `implemented`：已有实现，验证尚未完成。
- `verified`：对应验收已通过。

## 当前需要确认

核心协议、单仓库、Git 认证、同步、冲突、IPC 和 App UI 基线已确认。尚未确认的内容只阻塞对应功能：

- QAux 外部模块加载进程、Provider SPI 最终 API 和不可用状态展示。
- 系统通知和卸载前警告的具体 UI。
