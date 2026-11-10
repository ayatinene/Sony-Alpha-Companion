# Sony Alpha Companion

面向 **Sony α7R II（ILCE-7RM2）** 的个人 Android 客户端，采用 Material 3 界面。

当前为 `0.1.5-preview` 预览版，A7R II 遥控与 JPEG 传输已有首轮反馈，稳定性仍在验证。

## 功能

- **遥控拍摄**：实时取景、快门、曝光参数、自动对焦、录像和间隔拍摄。可用控制取决于相机开放的能力。
- **JPEG 传输**：相机图库、缩略图、多选下载、进度显示、取消与有限重试。下载尺寸以相机实际提供的资源为准。
- **本地图库**：导入记录、EXIF、RAW/JPEG 同名配对、筛选、分享与导入记录管理。照片保存到 `DCIM/AlphaCompanion`。
- **RAW / 原文件传输（实验性）**：支持实验性 USB OTG 接收端登记，或使用电脑已登记的 GUID 连接。
- 支持浅色、深色和动态配色，以及分级日志和诊断包导出。

## 安装与使用

在 [GitHub Actions](https://github.com/ayatinene/Sony-Alpha-Companion/actions) 中打开成功的构建，下载 `Alpha-Companion-Debug-APK`，解压后安装 APK。支持 Android 10 及以上版本。

先在相机选择工作模式，再用系统 Wi-Fi 设置连接相机网络，最后在应用首页连接对应模式。

- [实机测试与操作指南](docs/DEVICE_TEST.md)
- [功能支持矩阵](docs/SUPPORT.md)
- [版本状态与已知问题](docs/STATUS.md)

## 构建

使用 Android Studio，或 JDK 17、Android SDK 36 和 Build Tools 35.0.0：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `gradlew.bat`。构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 许可

项目采用 [GPL-3.0](LICENSE)。第三方代码来源和许可见 [复用说明](docs/REUSE.md) 与 [licenses](licenses)。
