# QAux 边界

- 状态：`confirmed`
- 日期：2026-08-25

## 决策

- EmoRepo 第一版依赖 QAux，不自己 Hook QQ。
- 向 QAux 提交通用表情 Provider SPI，不写死 EmoRepo。
- QQ 定位、面板和发送由 QAux 维护。
- EmoRepo 管理仓库、Git、IPC 和 Provider 数据。

## 后果

Provider SPI 未进入 QAux 前，不实现假的本地替代接口。EmoRepo 编译时对正式 SPI 使用 `compileOnly`。
