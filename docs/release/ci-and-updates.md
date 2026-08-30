# CI、发布与更新索引

- 状态：`verified`
- 更新：2026-08-30

## 构建渠道

EmoRepo 只发布两个渠道：

- `dev`：普通分支 push 和手动触发 GitHub Actions 时构建。使用 `debug` 构建类型并开启 Android 调试；独立 Hook 方案确定后，调试版和正式版统一使用应用 ID `top.e404.emorepo` 与应用名“表情仓”，新调试包会直接覆盖同签名的旧安装。产物只保存在 Actions Artifact，不创建 GitHub Release。
- `release`：只有严格匹配 `v<major>.<minor>.<patch>` 的标签触发。使用 `release` 构建类型，应用 ID 为 `top.e404.emorepo`，产物上传到对应 GitHub Release。

两个渠道都生成以下 APK：

- `universal`
- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

CI 中两个渠道使用同一套持久签名凭据，保证同一应用 ID 的后续构建可以覆盖安装；本地没有 CI 凭据时，debug 仍使用 Android 默认调试签名，release 可以构建未签名产物用于源码检查，但不得发布。

为避免 CI 和源码依赖预览 SDK，App 固定使用 `compileSdk=36`、`targetSdk=36` 和 `minSdk=24`，兼容 Android 7.0 及以上设备。Compose 固定使用 BOM `2026.06.01`（Foundation `1.11.4`），AndroidX Core 固定为 `1.17.0`；不得升级到要求 API 37/36.1 的 Compose `1.12` 或 Core `1.18+`，除非重新确认兼容基线。

## 版本

- release 的 `versionName` 直接取标签去掉前导 `v` 的 SemVer，例如 `v0.1.0` 对应 `0.1.0`。
- release 的 `versionCode` 为 `major * 1,000,000 + minor * 1,000 + patch`；`minor` 和 `patch` 必须在 `0..999`。
- dev 的 `versionName` 为 `<基线版本>-dev.<run_number>.<short_sha>`，`versionCode` 与 `baseVersion` 的 SemVer 计算值一致。dev 与 release 共用包名和签名，同版本码允许相互覆盖，避免已安装 release 时被 Android 判定为降级。
- release 标签必须指向已经推送到默认分支的提交；workflow 会再次校验标签格式和版本范围。

## ABI

APK 同时包含 Compose 依赖的 ABI 原生库，因此 CI 发布 universal 和四种 ABI 分包。ABI 分包只减少无关原生库，不改变 Kotlin/Java 功能。

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

当前 GitHub 仓库是 private，上述地址和 Release 资产需要有仓库读取权限的 GitHub 身份认证。若 App 未来需要无 Token 自动检查更新，必须先确认公开仓库、公开镜像或受控更新服务中的一种来源；不能在 APK 内内置 GitHub Token。

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

## 2026-08-27 dev 验收

- 以下记录发生在应用 ID 统一之前，仅作为历史构建证据；当前 dev 已不再使用 `.dev` 后缀，需在下一次 CI 中重新验收正式包覆盖行为。
- GitHub Actions run `33034944943` 成功完成测试、五 APK 构建和 Artifact 上传。
- Artifact 名为 `EmoRepo-dev-4-38885745cf2601fd34bbc262cd03b30184eeb1a3`，包含 universal、arm64-v8a、armeabi-v7a、x86、x86_64 和 `SHA256SUMS`。
- 五个 SHA-256 全部通过；universal APK 的应用 ID 为 `top.e404.emorepo.dev`、`versionCode=4`、`versionName=0.1.0-dev.4.3888574`、`debuggable=true`，签名证书 SHA-256 与 `version.properties` 一致。

## 2026-08-27 release 验收

- 标签 `v0.1.0` 指向 `35eb4ba02bea7408ed5e4d2ec3e734b818913ae3`；补发 run `33035704550` 成功完成测试、签名构建、摘要校验、索引生成和 Release 创建。
- GitHub Release `EmoRepo 0.1.0` 不是 draft/prerelease，包含五个 APK、`SHA256SUMS` 和 `release-index.json`。
- 独立下载后五个 SHA-256 全部通过；每个 APK 的文件大小、摘要和签名证书都与索引一致。
- universal APK 为 `top.e404.emorepo`、`versionCode=1000`、`versionName=0.1.0`、`minSdk=24`、`targetSdk=36`；索引 tag、commit、五个 ABI 项和下载地址均正确。

## 2026-08-30 release 验收

- 标签 `v0.2.0` 指向 `a64a8d35e245fdb7a2fdd7b43672cdd519e42f1e`；run `33271388945` 成功完成测试、签名构建、摘要校验、索引生成和 Release 创建。
- GitHub Release `EmoRepo 0.2.0` 不是 draft/prerelease，包含五个 APK、`SHA256SUMS` 和 `release-index.json`。
- 独立下载后五个 SHA-256 全部通过；每个 APK 的文件大小、摘要和签名证书都与索引一致。
- universal APK 为 `top.e404.emorepo`、`versionCode=2000`、`versionName=0.2.0`、`minSdk=24`、`targetSdk=36`；索引 tag、commit、五个 ABI 项和下载地址均正确。

## 2026-08-30 0.2.1 release 验收

- 标签 `v0.2.1` 指向 `eeb0318070ffae5ee671a28f6653460b0e368242`；run `33296486672` 成功完成测试、签名构建、摘要校验、索引生成和 Release 创建。
- GitHub Release `EmoRepo 0.2.1` 不是 draft/prerelease，包含五个 APK、`SHA256SUMS` 和 `release-index.json`。
- 独立下载后五个 SHA-256 全部通过；每个 APK 的文件大小、摘要、下载地址和签名证书都与索引一致，五个 APK 均通过 v2 签名校验。
- universal APK 为 `top.e404.emorepo`、`versionCode=2001`、`versionName=0.2.1`、`minSdk=24`、`targetSdk=36`；索引 tag、commit、五个 ABI 项和下载地址均正确。
