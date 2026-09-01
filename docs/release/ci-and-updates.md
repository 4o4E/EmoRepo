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

CI 和本地的 debug/release 都必须使用同一套生产签名，保证同一应用 ID 的任意已授权构建可以直接覆盖安装并真实验证 App 内更新。Gradle 不再回退 Android 默认调试证书，也不生成未签名 release；缺少配置、配置不完整或证书 SHA-256 与 `version.properties` 不一致时在配置期失败。

- CI 继续使用 `EMOREPO_KEYSTORE_PATH`、`EMOREPO_STORE_PASSWORD`、`EMOREPO_KEY_ALIAS`、`EMOREPO_KEY_PASSWORD`。
- 本地可以使用同名环境变量，或把 `local-signing.properties.example` 复制为被 Git 忽略的 `local-signing.properties`，填写 `keystorePath`、`storePassword`、`keyAlias`、`keyPassword`。
- 生产 keystore 和密码不得提交、输出到日志或通过 GitHub Actions Artifact 导出；GitHub Secrets 无法安全读回，本机缺少私钥时必须由持有者线下提供。

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

当前 GitHub 仓库是 public，上述稳定地址和 Release 资产允许 App 匿名访问。App 内更新不得读取表情仓库 Git Token，也不得在 APK 内内置 GitHub Token；若仓库未来改回 private，必须重新确认独立认证方案，不能静默复用现有凭据。

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

## 2026-08-31 0.3.0 release 验收

- 标签 `v0.3.0` 指向 `dd9e9d8e278c8606edec1943b4364146050f07d4`；release run `33325777021` 成功完成测试、签名构建、摘要校验、索引生成和 Release 创建，main 的 dev run `33325768298` 同时成功。
- GitHub Release `EmoRepo 0.3.0` 不是 draft/prerelease，包含五个 APK、`SHA256SUMS` 和 `release-index.json`。
- 独立下载后五个 APK 的文件大小和 SHA-256 均与索引及 `SHA256SUMS` 一致；五包全部通过 v2 签名校验，签名证书 SHA-256 为 `95aea64497d6e79e56a29d77624f876d27e5ad1c7d0fc867932cc7f556268022`。
- 五个 APK 均为 `top.e404.emorepo`、`versionCode=3000`、`versionName=0.3.0`、`minSdk=24`、`targetSdk=36` 且不可调试；索引 tag、commit、ABI 固定顺序和下载地址均正确。

## 2026-09-01 0.3.1 release 验收

- 标签 `v0.3.1` 指向 `97fa6c2a0f9b10977a9e8b0d037cf5d50a1283ab`；release run `33418728159` 成功完成 102 项测试、签名构建、摘要校验、索引生成和 Release 创建。
- GitHub Release `EmoRepo 0.3.1` 不是 draft/prerelease，包含五个 APK、`SHA256SUMS` 和 `release-index.json`；独立下载后的大小、SHA-256、索引提交和五包 v2 签名证书全部匹配。
- arm64 正式版已覆盖安装到 `CPH2653`，保留原仓库和设置；版本为 `versionCode=3001`、`versionName=0.3.1`。
- 首次同步安全恢复约 23.7 小时的陈旧 `.git/index.lock`，依次完成暂存、提交、fetch、rebase、协议校验和 push；设备与远端 `face/master` 同为 `6fb2ae401dbf0c1eaf938bd2192f5f81d4e52cb7`。
- 系统文件创建器成功导出含 App 和 QQ Hook 日志的诊断 ZIP；49 行日志均为合法 JSON，未发现 Token/认证字段、邮箱或 QQ 聊天/会话字段。

## 2026-09-01 0.3.2 release 验收

- 标签 `v0.3.2` 指向 `d1607f91145c35b7943f0d46a4f68cb47970671a`；release run `33485428580` 成功完成 106 项测试、签名构建、五包签名校验、更新索引生成和 GitHub Release 创建，配套 main dev run `33485402009` 同时成功。
- GitHub Release `EmoRepo 0.3.2` 不是 draft/prerelease，包含 universal、arm64-v8a、armeabi-v7a、x86、x86_64 五个 APK、`SHA256SUMS` 和 `release-index.json`。
- 独立下载后，五个 APK 的文件大小和 SHA-256 均与 `SHA256SUMS`、`release-index.json` 及 GitHub asset digest 一致；五包证书 SHA-256 均为 `95aea64497d6e79e56a29d77624f876d27e5ad1c7d0fc867932cc7f556268022`。
- universal APK 为 `top.e404.emorepo`、`versionCode=3002`、`versionName=0.3.2`、`minSdk=24`、`targetSdk=36`；索引 tag、commit、ABI 固定顺序、尺寸、摘要和下载地址全部匹配。

## 2026-09-01 0.4.0 release 验收

- 标签 `v0.4.0` 指向 `debe41e845a4b469860d1da98f52849a8e73b571`；release run `33495383289` 成功完成 112 项测试、签名构建、五包签名校验、更新索引生成和 GitHub Release 创建，配套 main dev run `33495378408` 同时成功。
- GitHub Release `EmoRepo 0.4.0` 不是 draft/prerelease，包含 universal、arm64-v8a、armeabi-v7a、x86、x86_64 五个 APK、`SHA256SUMS` 和 `release-index.json`。
- 独立下载后，五个 APK 的文件大小和 SHA-256 均与 `SHA256SUMS`、`release-index.json` 及 GitHub asset digest 一致；五包证书 SHA-256 均为 `95aea64497d6e79e56a29d77624f876d27e5ad1c7d0fc867932cc7f556268022`。
- universal APK 为 `top.e404.emorepo`、`versionCode=4000`、`versionName=0.4.0`、`minSdk=24`、`targetSdk=36`；索引 tag、commit、ABI 固定顺序、尺寸、摘要和下载地址全部匹配。

## 2026-09-01 0.5.0 release 验收

- 标签 `v0.5.0` 指向 `a3e92510553f8ffb384f289d57e486dbf9591b62`；release run `33506589269` 成功完成测试、签名构建、五包签名校验、更新索引生成和 GitHub Release 创建。
- GitHub Release `EmoRepo 0.5.0` 是当前 latest release，不是 draft/prerelease，包含 universal、arm64-v8a、armeabi-v7a、x86、x86_64 五个 APK、`SHA256SUMS` 和 `release-index.json`。
- 独立下载后，五个 APK 的文件大小和 SHA-256 均与 `SHA256SUMS`、`release-index.json` 及 GitHub asset digest 一致；五包证书 SHA-256 均为 `95aea64497d6e79e56a29d77624f876d27e5ad1c7d0fc867932cc7f556268022`。
- universal APK 为 `top.e404.emorepo`、`versionCode=5000`、`versionName=0.5.0`、`minSdk=24`、`targetSdk=36`；索引 tag、commit、ABI 固定顺序、尺寸、摘要和下载地址全部匹配。
- 当前测试机安装的是历史 Debug 签名版本；为保留未导出的本地表情仓库，本次未卸载数据并安装生产签名 APK，因此 0.5.0 的生产包覆盖安装和 App 内自动更新仍需在完成数据迁移后真机验收。
