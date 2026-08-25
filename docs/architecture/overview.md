# 架构

- 状态：`confirmed`
- 更新：2026-08-25

## 进程

EmoRepo 涉及两个独立 Android 进程和沙箱：

```text
EmoRepo App 进程                         QQ 进程
┌──────────────────────────┐            ┌──────────────────────────┐
│ 管理 UI                   │            │ QAuxiliary               │
│ App 私有 Git 仓库         │◀── IPC ───│ EmoRepo 外部模块入口     │
│ JGit 同步                 │            │ QAux 表情 Provider       │
│ JSONL / CSV               │            │ QQ 面板和发送适配        │
└──────────────────────────┘            └──────────────────────────┘
```

QQ 进程不能直接读取 `top.e404.emorepo` 的私有文件。QAux 加载 EmoRepo APK 中的代码只改变 ClassLoader，不改变调用进程 UID，因此必须通过 Android IPC 从 EmoRepo App 获取表情元数据和图片文件。

## 组件边界

第一阶段只使用一个 `app` Gradle 模块，避免创建空模块。代码按职责分包：

```text
top.e404.emorepo
├── protocol     # JSONL、CSV 和仓库约束
├── repository   # 私有仓库访问
├── git          # JGit 封装和同步编排
├── ipc          # 向 QQ 进程提供只读数据
├── qaux         # 外部模块入口和 QAux SPI 适配
└── ui           # App 管理界面
```

只有出现独立发布、不同平台依赖或明显构建收益时，才把包拆为 Gradle 模块；拆分前需要更新本文档。

## 依赖方向

- UI 调用应用服务，不直接调用 JGit。
- 同步编排依赖项目自己的 Git 接口，JGit 只存在于实现层。
- QAux 适配只通过 IPC 读取数据，不运行 JGit，不直接访问 App 私有路径。
- 协议解析不依赖 Android UI 或 QQ 类。

## IPC

使用导出的只读 `ContentProvider`：`query` 返回分页元数据，`openFile` 返回图片文件描述符；Provider 必须校验调用 UID 对应受支持 QQ 包及签名。
