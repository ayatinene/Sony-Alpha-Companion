# APK 签名说明

项目的 Gradle 构建配置让 Debug 和 Release 共用同一份 PKCS12 签名密钥；当前 GitHub Actions 工作流构建并发布固定签名的 Debug APK（`Alpha-Companion-Debug-APK`）。

## GitHub Actions 配置

在仓库 `Settings → Secrets and variables → Actions` 中添加 Repository secret：

- 名称：`ANDROID_SIGNING_BUNDLE`
- 内容：签名备份文件的完整 JSON 内容（包含 `keystore_base64`、`store_password`、`key_alias`、`key_password`）。

签名备份文件本身包含恢复签名所需的全部私钥和口令，请在线下妥善保存，不要提交到代码仓库。

配置后运行 Android Debug 工作流，构建脚本会校验密钥及生成的 APK 证书是否匹配 `.github/signing-certificate.sha256`。缺少 Secret 或证书指纹不匹配时构建会直接停止，不会发布临时签名 APK。Pull Request 只跑编译与测试检查，不上传 APK 产物。

## 本地构建

在本地设置环境变量 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 后运行 Gradle，即可使用同一签名。未设置这些环境变量时，本地构建会使用 Android Studio 默认的临时 Debug 签名。

## 安装与覆盖更新

第一次从早期临时 Debug 签名版本切换到固定签名版本时，需要先卸载旧版再安装；之后凡是使用同一固定密钥构建的版本都可以直接覆盖更新。卸载旧版前请先记下需要保留的连接设置并导出日志；已经保存到系统相册（`DCIM/AlphaCompanion`）的照片由系统相册管理，不受卸载影响。
