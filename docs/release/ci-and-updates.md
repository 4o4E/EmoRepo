# CI、发布与更新索引

- 状态：`confirmed`
- 更新：2026-08-27

## 构建渠道

EmoRepo 只发布两个渠道：

- `dev`：普通分支 push 和手动触发 GitHub Actions 时构建。使用 `debug` 构建类型，开启 Android 调试，应用 ID 为 `top.e404.emorepo.dev`，应用名为“表情仓 Dev”。产物只保存在 Actions Artifact，不创建 GitHub Release。
- `release`：只有严格匹配 `v<major>.<minor>.<patch>` 的标签触发。使用 `release` 构建类型，应用 ID 为 `top.e404.emorepo`，产物上传到对应 GitHub Release。

两个渠道都生成以下 APK：

- `universal`
- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

CI 中两个渠道使用同一套持久签名凭据，保证同一应用 ID 的后续构建可以覆盖安装；本地没有 CI 凭据时，debug 仍使用 Android 默认调试签名，release 可以构建未签名产物用于源码检查，但不得发布。

## 版本

- release 的 `versionName` 直接取标签去掉前导 `v` 的 SemVer，例如 `v0.1.0` 对应 `0.1.0`。
- release 的 `versionCode` 为 `major * 1,000,000 + minor * 1,000 + patch`；`minor` 和 `patch` 必须在 `0..999`。
- dev 的 `versionName` 为 `<下一个基线版本>-dev.<run_number>.<short_sha>`，`versionCode` 使用 GitHub Actions `run_number`。
- release 标签必须指向已经推送到默认分支的提交；workflow 会再次校验标签格式和版本范围。

## ABI 与 Provider API

APK 同时包含 Compose 依赖的 ABI 原生库，因此 CI 发布 universal 和四种 ABI 分包。ABI 分包只减少无关原生库，不改变 Kotlin/Java 功能。

EmoRepo 使用 QAux Provider API 的 compile-only 复合构建。GitHub Actions 固定检出：

```text
repository: 4o4E/QAuxiliary
commit: 2b506520e73ff43365fcb09a5a111c79c09d5430
```

不得在 workflow 中跟随浮动分支；升级 SPI 时先更新并验证 QAux 提交，再更新此固定值。

## 签名 Secrets

仓库 Actions 使用以下 Secrets：

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

标签 workflow 缺少任一项时必须失败，不得退回 debug 签名或发布未签名 APK。

## Release 更新索引

每个 GitHub Release 必须附带 `release-index.json`。稳定检查地址为：

```text
https://github.com/4o4E/EmoRepo/releases/latest/download/release-index.json
```

索引使用 UTF-8 JSON，结构如下：

```json
{
  "schemaVersion": 1,
  "applicationId": "top.e404.emorepo",
  "channel": "release",
  "tag": "v0.1.0",
  "versionName": "0.1.0",
  "versionCode": 1000,
  "minimumSdk": 24,
  "commit": "<40 位 Git SHA>",
  "publishedAt": "<ISO-8601>",
  "releaseUrl": "https://github.com/4o4E/EmoRepo/releases/tag/v0.1.0",
  "signingCertificateSha256": "95aea64497d6e79e56a29d77624f876d27e5ad1c7d0fc867932cc7f556268022",
  "artifacts": [
    {
      "abi": "universal",
      "fileName": "EmoRepo-0.1.0-universal.apk",
      "size": 123,
      "sha256": "<64 位小写 SHA-256>",
      "downloadUrl": "https://github.com/4o4E/EmoRepo/releases/download/v0.1.0/EmoRepo-0.1.0-universal.apk"
    }
  ]
}
```

`artifacts` 固定按 universal、arm64-v8a、armeabi-v7a、x86、x86_64 排序。更新检查先比较 `versionCode`，再按设备 ABI 选择专用 APK；没有匹配项时使用 universal。索引和 `SHA256SUMS` 中的摘要必须从最终上传文件计算。

所有 release APK 的签名证书 SHA-256 必须等于索引中的 `signingCertificateSha256`；workflow 在创建 Release 前逐个校验，不匹配时终止发布。

## GitHub Actions

- `android-dev.yml`：普通 push 和手动触发；运行 JVM 测试、构建 dev APK、生成 `SHA256SUMS`，上传一个保留 14 天的 Artifact。
- `android-release.yml`：仅 `v*` 标签和手动补发；验证 SemVer、运行 JVM 测试、构建签名 release APK、生成 `release-index.json` 与 `SHA256SUMS`，再创建或覆盖同标签 Release 资产。
- Release workflow 使用 `contents: write`；dev workflow 只使用 `contents: read`。
- 普通提交和失败的标签构建都不得创建 GitHub Release。
