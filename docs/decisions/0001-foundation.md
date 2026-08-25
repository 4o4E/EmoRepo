# 基础身份和范围

- 状态：`confirmed`
- 日期：2026-08-25

## 决策

- 项目名 `EmoRepo`，显示名“表情仓”。
- 包名 `top.e404.emorepo`。
- 只支持 Android QQ，不支持 TIM。
- EmoRepo 使用 Kotlin；QAux 稳定 SPI 允许少量 Java。
- 本仓库使用 Apache-2.0。

## 后果

包名作为安装、IPC 和升级身份，不在开发中随意更改。项目不复制 QAux 的 QQ 定位实现。
