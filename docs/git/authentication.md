# Git 身份和认证

- 状态：`confirmed`
- 更新：2026-08-25

## 已确认

- Git 作者名和邮箱由用户按仓库配置。
- 凭据不得写入表情 Git 仓库、普通配置文件或日志。
- 敏感凭据使用 Android Keystore 保护。
- 日志中的远端 URL 必须移除用户名、Token 和查询参数。
- HTTPS 必须保持证书链和主机名校验，不提供关闭 `http.sslVerify` 的设置。JGit 包内虽然包含仅供显式关闭校验时使用的宽松 TrustManager，EmoRepo 不得进入该路径。

## 首版范围

- 只支持 HTTPS Token，不支持 SSH 私钥、known_hosts 或密钥口令。
- Token 属于当前单一仓库，不建立跨仓库凭据条目。
- 凭据失效只暂停同步，不阻止本地查看、使用或修改表情。
- 更新凭据后立即请求一次同步。
