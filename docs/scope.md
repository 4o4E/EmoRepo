# 范围

- 状态：`confirmed`
- 更新：2026-08-25

## 目标

EmoRepo 管理一个保持现有 lite-tools 表情目录语义的 Git 仓库，并通过 QAuxiliary 向 Android QQ 提供表情。

第一版包括：

- 管理表情包、图片、封面和显式顺序。
- 使用 `index.jsonl` 保存表情索引。
- 使用 `recent/<deviceId>.csv` 保存每台设备的最近使用记录。
- 使用 JGit 完成自动和手动同步。
- 默认将仓库保存于 EmoRepo App 私有目录。
- 通过待加入 QAux 的通用表情 Provider SPI 接入 QQ。

## 非目标

- 不直接定位、Hook 或调用 QQ 内部类。
- 不长期维护 QAuxiliary fork。
- 不支持 TIM、QQ 国际版或其他宿主。
- 不读取、迁移或双写旧 `index.json`。
- 不提供只拉取而不上传的同步模式。
- 不提供 Wi-Fi-only 选项。
- 不在首版实现 SSH，除非后续明确确认。

## 开发约束

- EmoRepo 业务代码使用 Kotlin。
- QAux 公共 SPI 可以为 ABI 稳定使用少量 Java。
- Apache-2.0 适用于本仓库原创代码。
- 未在文档中确认的可观察逻辑不得实现。
