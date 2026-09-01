# App 内更新

- 状态：`confirmed`
- 更新：2026-09-01

## 入口和触发

- 软件设置增加“软件更新”卡片，展示当前版本、检查结果、下载进度和可读错误。
- 只由用户点击“检查更新”触发 GitHub 请求，不增加后台周期检查、启动弹窗或自动下载。
- 发现更高 `versionCode` 后显示版本号和“下载并安装”；点击后自动选择设备 ABI、下载并校验 APK，再进入系统安装流程。
- 当前已是最新版时明确显示最新版；远端版本不高于当前版本时不得下载或触发安装。

## public GitHub 访问

- 更新源固定为 public 仓库 `4o4E/EmoRepo` 的最新非 draft、非 prerelease GitHub Release。
- 检查索引和下载 APK 全程匿名访问，不读取、不复用现有 Git Token，也不发送 `Authorization` 头；表情仓库同步 Token 与软件更新完全隔离。
- Release asset 下载仍关闭自动重定向并手动限制为 HTTPS，避免不受控跳转；跳转后的带签名临时 URL不写日志。
- 响应流直接写 App 私有缓存，不使用 Android `DownloadManager`；日志不得记录响应正文或临时下载 URL。

## Release 选择和校验

1. 请求 `GET /repos/4o4E/EmoRepo/releases/latest`，取得 Release 和 asset 元数据。
2. 通过 asset API 下载 `release-index.json`，限制索引体积并严格解析。
3. 校验 `schemaVersion=1`、`applicationId=top.e404.emorepo`、`channel=release`、tag、版本名、版本码、最低 SDK、Release 提交和固定发布证书摘要。
4. 按 `Build.SUPPORTED_ABIS` 顺序选择第一个同名 ABI 产物；没有专用产物时回退 `universal`。不得按 CPU 型号字符串猜测 ABI。
5. Release asset 必须与索引中的文件名和大小一致；下载写入 `cache/updates/*.part`，设置 256 MiB 硬上限，完成后校验精确字节数和 SHA-256，再原子改名为 `.apk`。失败、取消或校验不通过立即删除临时文件。
6. 安装前用 Android PackageManager 读取 APK：包名必须是 `top.e404.emorepo`，APK 签名证书必须等于构建时从 `version.properties` 固定进 BuildConfig 的发布证书摘要。不能只信任远端索引中的证书字段。
7. 当前已安装 App 的签名也必须等于固定发布证书；本地 Debug 签名版本明确提示不能覆盖正式版，禁止建议卸载，因为卸载会删除私有仓库。

## 安装流程

- Manifest 声明 `REQUEST_INSTALL_PACKAGES`，APK 仅通过现有非导出的 FileProvider 授予系统安装器一次性只读 URI，不暴露缓存目录。
- Android 8.0 及以上先检查 `canRequestPackageInstalls()`；未授权时打开当前 App 的“安装未知应用”系统设置，用户返回且授权成功后继续打开安装器。
- 使用系统 `ACTION_INSTALL_PACKAGE` 进入安装确认页。普通 Android 应用不能静默完成安装，最终确认、取消和失败结果由系统安装器控制。
- 下载完成不修改表情仓库、不触发 Git 同步；缓存中的旧更新包在下一次检查或下载前清理。

## 错误和恢复

- 网络、GitHub 认证、索引解析、ABI、空间、大小、摘要、包名、签名和安装权限错误分别展示，不把所有错误折叠为“更新失败”。
- 下载进度基于已写入字节和索引大小；页面离开后 ViewModel 继续持有状态，但进程被杀时不自动恢复半包，下一次操作清理 `.part` 后重新下载。
- 已校验 APK 可以在同一次进程内重试调起安装器，不重复下载；重新检查到不同版本时删除旧 APK。

## 验收

- public GitHub 在没有 Token 时可以检查索引并下载；更新功能不得因为用户未配置表情仓库 Token 而不可用。
- arm64、armeabi-v7a、x86、x86_64 和未知 ABI 分别选择专用包或 universal。
- 索引、大小、SHA-256、包名或证书任一不匹配时不出现系统安装页且删除文件。
- 正式签名版本可进入系统安装确认；本地 Debug 签名版本只显示不可覆盖提示，保留仓库数据。
- 源码检查、JVM 测试、Android 构建、真实 GitHub private Release 检查和系统安装页分别记录。

## 2026-09-01 实现与验收

- 设置页已增加“软件更新”，当前本地 Debug 为 `0.4.0-dev / 4000`；仓库公开前曾使用 Keystore Token 真机读取 private `v0.4.0` 并显示“当前已是最新版本”。仓库现已改为 public，当前实现改为匿名请求且不再读取 Token；匿名真机回归待生产签名 Debug 构建后补齐。
- JVM 测试覆盖 ABI 首选/回退、稳定 Release/索引/asset 一致性、版本码比较、摘要拒绝，以及匿名 asset 请求和跨主机 302 均不携带认证头，并完成大小/SHA-256 校验和 `.part` 清理。
- Manifest、私有缓存 FileProvider、固定生产证书 BuildConfig、APK 包名/版本/签名校验、未知来源设置和系统安装 Intent 已实现。当前测试机仍是本地 Debug 签名，不能安全覆盖生产 APK；为保留私有仓库，本轮不卸载，因此真实下载新版本、未知来源授权和系统安装确认页仍待生产签名 Debug 环境验证。
