# 工作进度

## 当前构建与验证

版本：`0.1.13-preview`（`versionCode 14`），以下验证对应源码提交 [`877a43c`](https://github.com/ayatinene/Sony-Alpha-Companion/commit/877a43ccc69fea2f6127a0612edf9376846c6035)：

- [Android Debug #41669506684](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41669506684)：280 项 JVM 单元测试通过（38 个套件，0 失败 / 错误 / 跳过），Android Lint 0 错误、38 警告，固定签名 APK 构建与证书校验通过。
- [APK 产物 #13247816663](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41669506684/artifacts/13247816663)。
- [Android 运行时检查 #41669506568](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41669506568)：89 项 Android 测试通过（0 失败 / 错误 / 跳过）。

## 待完成事项

- RAW（Send to Computer）断开与恢复：`0.1.12-preview` 实机已观察到扫描途中由手机主动断开（未开启实验性休眠开关、在途强制 `abort` 无 `CloseSession`/休眠）后机身仍保持旧发送状态并导致重连失败；在途断开恢复方案待后续研究，空闲状态手动断开及“断开时让相机休眠（实验性）”开关待实机确认（已测基线与边界见 [STATUS.md](STATUS.md)）。
- `0.1.13-preview` 连接入口按需自动权限检查、JPEG 日期文件夹排序、拍摄页跟随手机旋转（系统锁定方向下横竖屏切换及退出恢复）与遥控照片添加手机位置（EXIF）的实机确认。
- RAW（Send to Computer）暂停/继续、扫描中优先下载、扫描或下载进行中切后台/熄屏稳定性，以及原文件 SHA-256 校验。
- 全屏沉浸式拍摄界面的实机确认。
- 遥控拍照回传失败时的界面手动重试入口。
