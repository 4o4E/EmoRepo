# Android 运行时

- 状态：`confirmed`
- 更新：2026-08-25

## 存储

默认仓库位于 App 私有目录：

```text
<filesDir>/repository/
```

- 不默认申请公共存储权限。
- 导入和导出通过系统文件选择器完成。
- Git 凭据不放在仓库目录。
- 私有仓库、配置和凭据全部排除在 Android 云备份和设备迁移之外，避免恢复出不完整的 Git 工作树或失效密钥引用。
- App 卸载会删除私有仓库，因此 UI 必须明确提示用户在卸载前导出或确认远端已同步；具体提示流程待 UI 文档确认。

## QQ 进程访问

QAux 外部模块代码运行在 QQ UID 下，不能直接访问上述私有目录。推荐提供一个导出的只读 `ContentProvider`：

- `query` 分页返回表情包和表情元数据。
- `openFile` 返回图片的只读 `ParcelFileDescriptor`。
- 每次调用校验 `Binder.getCallingUid()` 对应包名，只允许 EmoRepo 自身和受支持 QQ 包。
- 对 QQ 包进一步校验签名证书摘要，避免同包名伪装。
- 不向调用方暴露仓库绝对路径、Git 目录或 Token。
- Provider 不执行 Git 操作，只读取已经落盘的一致快照。

该 IPC 方案已确认。实现时在 Manifest 中导出 Provider，但所有查询和文件打开都必须先完成调用方校验。

## 后台同步

- 立即同步使用唯一 OneTimeWorkRequest。
- 独立远端轮询使用唯一 PeriodicWorkRequest，默认 30 分钟，可配置或关闭；配置值不得低于 Android 的 15 分钟技术下限。
- 任务要求网络可用，不区分 Wi-Fi 和移动数据。
- WorkManager 只负责调度；仓库级互斥、恢复和幂等由同步层保证。
- App 进程被杀后，已提交但未 push 的提交必须由后续任务继续上传。

## 后续 UI 工作

- 仓库存在未推送提交时的卸载提示流程在实现对应 UI 前确认。
- 第一阶段同步失败只在 App 内展示，不申请通知权限；系统通知在实现前另行确认。
