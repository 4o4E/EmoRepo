# 表情仓库目录

- 状态：`confirmed`
- 更新：2026-08-25

## 结构

保持当前 lite-tools 的表情包目录语义，不增加统一 `images` 层：

```text
<repository>/
├── index.jsonl
├── <pack-name>/
│   ├── index.jsonl
│   ├── <md5>.<ext>
│   └── ...
├── <another-pack>/
│   ├── index.jsonl
│   └── ...
└── recent/
    ├── <device-id>.csv
    └── ...
```

## 不变量

- 仓库根目录的普通表情包文件夹名就是 `package` 值。
- 图片和该表情包的 `index.jsonl` 位于同一目录。
- `recent` 是协议保留目录，不是表情包。
- 索引中的 `name` 只能是文件名，不得包含绝对路径或 `..`。
- Git 内只保存相对仓库根目录的数据。
- 根目录 `index.jsonl` 保存表情包顺序；表情包目录内的同名文件保存包内表情记录，两者按相对路径区分。
- 旧 `index.json` 不属于新协议；加载器忽略它。

## 测试数据

格式开发使用正式表情仓库的复制件，不直接迁移或修改当前正式仓库。
