# 工作进度

## 当前构建与验证

版本：`0.1.12-preview`（`versionCode 13`），本版 CI 构建与测试待运行后回填。

上一版已验证基线（`0.1.11-preview`，源码提交 `e91cba9`）：

- [Android Debug #41460982529](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41460982529)：186 项 JVM 单元测试通过（27 个套件，0 失败 / 错误 / 跳过），Android Lint 0 错误 / 33 警告，固定签名 APK 构建与证书校验通过。
- [APK 产物 #13149425911](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41460982529/artifacts/13149425911)。
- [Android 运行时检查 #41460982418](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41460982418)：58 项 Android 测试通过（0 失败 / 错误 / 跳过）。

## 待完成事项

- `0.1.12-preview` 新增的“断开时让相机休眠（实验性）”开关（默认关闭，仅原文件连接空闲时手动断开尝试）与扫描/下载取消及断开收尾的实机确认（已测基线与边界见 [STATUS.md](STATUS.md)）。
- RAW（Send to Computer）暂停/继续、扫描中优先下载、扫描或下载进行中切后台/熄屏稳定性，以及原文件 SHA-256 校验。
- 全屏沉浸式拍摄界面的实机确认。
- 遥控拍照回传失败时的界面手动重试入口。
