# 工作进度

## 当前构建与验证

版本：`0.1.10-preview`（`versionCode 11`），以下验证对应源码提交 `ba0ce6a`：

- [Android Debug #41424645052](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41424645052)：159 项 JVM 单元测试通过（0 失败 / 错误 / 跳过），Android Lint 0 错误 / 33 警告，固定签名 APK 构建与证书校验通过。
- [APK 产物 #13130818388](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41424645052/artifacts/13130818388)。
- [Android 运行时检查 #41424645134](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41424645134)：39 项 Android 测试通过（0 失败 / 错误 / 跳过）。

## 待完成事项

- RAW（Send to Computer）大容量存储卡分批扫描、后台/熄屏稳定性、异常恢复，以及 `.ARW` / `.JPG` 原文件下载与 SHA-256 校验的实机测试。
- 全屏沉浸式拍摄界面的实机确认。
- 遥控拍照回传失败时的界面手动重试入口。
