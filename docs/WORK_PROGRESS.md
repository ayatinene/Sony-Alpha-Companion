# 工作进度

## 当前构建与验证

版本：`0.1.10-preview`（`versionCode 11`），构建与自动化测试尚待验证。

上一版本 `0.1.9-preview`（`versionCode 10`）验证记录：

- [Android Debug #41319750812](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41319750812)：96 项 JVM 单元测试通过（0 失败 / 错误 / 跳过），Android Lint 0 错误 / 33 警告，固定签名 APK 构建与证书校验通过。
- [APK 产物 #13089206020](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41319750812/artifacts/13089206020)。
- [Android 运行时检查 #41319750664](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41319750664)：15 项 Android 测试通过（0 失败 / 错误 / 跳过）。

## 待完成事项

- `0.1.10-preview` 的构建、JVM 单元测试、Android 运行时测试与 Lint 验证。
- RAW（Send to Computer）大容量存储卡分批扫描、后台/熄屏稳定性，以及 `.ARW` / `.JPG` 原文件下载与 SHA-256 校验的实机测试。
- 全屏沉浸式拍摄界面的实机确认。
- 遥控拍照回传失败时的界面手动重试入口。
