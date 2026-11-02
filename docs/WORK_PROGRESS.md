# 工作进度

## 当前构建与验证

版本：`0.1.11-preview`（`versionCode 12`），以下验证对应源码提交 `e91cba9`：

- [Android Debug #41460982529](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41460982529)：186 项 JVM 单元测试通过（27 个套件，0 失败 / 错误 / 跳过），Android Lint 0 错误 / 33 警告，固定签名 APK 构建与证书校验通过。
- [APK 产物 #13149425911](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41460982529/artifacts/13149425911)。
- [Android 运行时检查 #41460982418](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41460982418)：58 项 Android 测试通过（0 失败 / 错误 / 跳过）。

## 待完成事项

- `0.1.11-preview` 更新的 RAW 优先读取近期照片、相机图库网格/详情列表视图切换与 6 项排序的实机确认（已测基线与边界见 [STATUS.md](STATUS.md)）。
- RAW（Send to Computer）暂停/继续、扫描中优先下载、扫描或下载进行中切后台/熄屏稳定性，以及原文件 SHA-256 校验。
- 全屏沉浸式拍摄界面的实机确认。
- 遥控拍照回传失败时的界面手动重试入口。
