# APK 签名

持续分发的 APK 使用同一份 PKCS12 签名密钥。Debug 和 Release 构建共用该密钥。

## GitHub Actions

在仓库 Settings → Secrets and variables → Actions 中添加一个 Repository secret：

- 名称：`ANDROID_SIGNING_BUNDLE`
- 内容：签名备份文本文件的完整 JSON 内容。

JSON 包含 `keystore_base64`、`store_password`、`key_alias` 和 `key_password`。
备份文本本身包含恢复签名所需的全部内容，请妥善保存，不要提交到仓库。

配置后重新运行 Android Debug 工作流。构建会校验密钥及 APK 证书是否匹配
`.github/signing-certificate.sha256`。缺少 Secret 或证书不匹配时，构建停止，
不会发布临时签名 APK。Pull Request 仅运行临时 Debug 构建及检查，不发布 APK。

## 本地构建

设置 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、
`ANDROID_KEY_PASSWORD` 后正常运行 Gradle。不设置时保留 Android Studio 默认 Debug 签名。

## 安装更新

首次从旧临时 Debug 签名切换到固定签名，需要卸载旧版再安装。
此后使用同一固定密钥构建的版本可以正常覆盖更新。卸载前导出需保留的应用设置和日志；
已保存到系统相册的照片由系统相册管理。
