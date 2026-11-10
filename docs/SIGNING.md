# APK 签名说明

项目的 Gradle 构建配置让 Debug 和 Release 共用同一份 PKCS12 签名密钥；GitHub Actions 构建固定签名的 Release APK（`AlphaCompanion-<版本>-<短SHA>-release.apk`，`-release` 表示 Android 构建变体，版本号中的 `-preview` 表示预发布阶段），通过 Actions 产物（`Alpha-Companion-Release-APK`）与 GitHub Releases 预发布版分发。

## GitHub Actions 配置

在仓库 `Settings → Secrets and variables → Actions` 中添加 Repository secret：

- 名称：`ANDROID_SIGNING_BUNDLE`
- 内容：签名备份文件的完整 JSON 内容（包含 `keystore_base64`、`store_password`、`key_alias`、`key_password`）。

签名备份文件本身包含恢复签名所需的全部私钥和口令，请在线下妥善保存，不要提交到代码仓库。

配置后运行 `Android Release Build` 工作流（执行 `:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease`），构建脚本会校验密钥、生成的 Release APK 证书是否匹配 `.github/signing-certificate.sha256` 以及 `android:debuggable` 为 `false`，并将 APK 按构建元数据命名为 `AlphaCompanion-<版本>-<短SHA>-release.apk` 上传至 `Alpha-Companion-Release-APK` 产物。缺少 Secret 或证书指纹不匹配时构建会直接停止，不会发布临时签名或未签名 APK。Pull Request 只跑编译与测试检查，不上传 APK 产物；`Android UI` 工作流在模拟器上运行 Debug 插桩测试（`connectedDebugAndroidTest`）。

当 `main` 分支的 `push` 提交同时通过 `Android Release Build` 与 `Android UI` 工作流后，`Android Release Publish` 工作流会校验构建元数据（`build-metadata.json`）与触发提交 SHA 一致、构建变体为 `release` 且工作区干净，自动创建带唯一标签 `v<版本>-<短SHA>` 的 GitHub Pre-release 并附上该固定签名 APK（已存在同名标签时不会覆盖）。

## 本地构建

在本地设置环境变量 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 后运行 Gradle，即可生成使用同一固定签名的 Release 或 Debug APK。未设置这些环境变量时，本地 `:app:assembleDebug` 会使用 Android Studio 默认的临时 Debug 签名。

## 安装与覆盖更新

第一次从早期临时 Debug 签名版本切换到固定签名版本时，需要先卸载旧版再安装；之后凡是使用同一固定密钥构建的版本都可以直接覆盖更新。卸载旧版前请先记下需要保留的连接设置并导出日志；已经保存到系统相册（`DCIM/AlphaCompanion`）的照片由系统相册管理，不受卸载影响。
