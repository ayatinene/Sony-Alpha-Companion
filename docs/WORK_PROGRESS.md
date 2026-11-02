# 工作进度

## 当前构建与验证

当前版本：`0.1.11-preview`（`versionCode 12`），GitHub Actions 构建与测试待运行回填：

- RAW 模式按存储卡返回句柄逆向分批读取，优先显示近期照片。
- 相机图库（JPEG / RAW）支持网格与详情列表视图切换，切换后保留当前浏览位置与已选状态。
- 相机图库提供拍摄时间、文件名自然序、文件大小共 6 项排序，修复 RAW 拍摄时间解析与展示。

上一版本记录：`0.1.10-preview`（`versionCode 11`，源码提交 `ba0ce6a`）：

- [Android Debug #41424645052](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41424645052)：159 项 JVM 单元测试通过（0 失败 / 错误 / 跳过），Android Lint 0 错误 / 33 警告，固定签名 APK 构建与证书校验通过。
- [APK 产物 #13130818388](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41424645052/artifacts/13130818388)。
- [Android 运行时检查 #41424645134](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/41424645134)：39 项 Android 测试通过（0 失败 / 错误 / 跳过）。
- 实机验证（α7R II）：完整扫描 3,001 个对象（2,980 个支持文件），Sony `0x9280` / `0x9281` 均返回 OK；扫描完成后下载 `DSC00027.JPG`（4,259,840 bytes）与 `DSC00027.ARW`（36,700,160 bytes）保存成功且长度与相机声明一致（未做 SHA-256 比对）；首次连接遇 10s 初始化超时后重试恢复。

## 待完成事项

- `0.1.11-preview` CI 验证，以及 RAW 逆向读取、相机图库视图切换与 6 项排序的实机确认。
- RAW（Send to Computer）暂停/继续、扫描中优先下载、扫描或下载进行中切后台/熄屏稳定性，以及原文件 SHA-256 校验。
- 全屏沉浸式拍摄界面的实机确认。
- 遥控拍照回传失败时的界面手动重试入口。
