# 工作进度

## 当前构建与验证

版本：`0.1.12-preview`（`versionCode 13`），以下验证对应源码提交 [`fa20fd8`](https://github.com/ayatinene/Sony-Alpha-Companion/commit/fa20fd84ba879c200f4b71e9acf5676645873bb5)：

- [Android Debug #41565160957](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41565160957)：236 项 JVM 单元测试通过（34 个套件，0 失败 / 错误 / 跳过），Android Lint 0 错误、34 警告，固定签名 APK 构建与证书校验通过。
- [APK 产物 #13199715187](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41565160957/artifacts/13199715187)。
- [Android 运行时检查 #41565160895](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41565160895)：71 项 Android 测试通过（0 失败 / 错误 / 跳过）。

## 待完成事项

- `0.1.12-preview` 新增的“断开时让相机休眠（实验性）”开关（默认关闭，仅原文件连接正常空闲且相机能力支持时手动断开尝试）与扫描/下载取消及断开收尾的实机确认（已测基线与边界见 [STATUS.md](STATUS.md)）。
- RAW（Send to Computer）暂停/继续、扫描中优先下载、扫描或下载进行中切后台/熄屏稳定性，以及原文件 SHA-256 校验。
- 全屏沉浸式拍摄界面的实机确认。
- 遥控拍照回传失败时的界面手动重试入口。
