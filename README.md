# Sony Alpha Companion

给 Sony α7R II（ILCE-7RM2）用的 Android 客户端，当前版本 `0.1.12-preview`。

## 能做什么

- 遥控拍摄（机内 Smart Remote Control）：实时取景、快门、曝光参数、自动对焦、录像和间隔拍摄。具体能调哪些参数取决于相机机内 Smart Remote 应用开放的接口。
- JPEG 传输（机内 Send to Smartphone）：浏览相机文件夹、切换网格/详情列表视图与排序（按拍摄时间、文件名或文件大小）、大图预览与缩放、多选或全选当前可见文件下载，可选相机提供的不同 JPEG 尺寸。
- RAW / 原文件传输（机内 Send to Computer，实验性）：在局域网下通过 PTP/IP 下载原始 `.ARW` 和 `.JPG`。连接后直接进入相机图库并分批读取文件列表、优先查找近期照片，支持暂停/继续查找、网格/详情列表切换、按 `全部 / JPEG / ARW` 筛选、排序、缩略预览与多选优先下载。需要先用电脑或应用设置里的 USB OTG 工具把接收端 GUID 登记到相机。
- 相机文件扫描与下载期间由前台服务通知维持后台执行；相机图库导入队列按相机标识和传输模式保存，重连同一台相机后（RAW 模式需待扫描完成）可核对文件列表并从头继续未完成的图库下载。
- 本地图库保存在 `DCIM/AlphaCompanion`，支持看大图、看 EXIF、同名 RAW/JPEG 关联、分享，也可以单独清除导入记录或直接删除照片文件。

## 安装与连接

在 [GitHub Actions](https://github.com/ayatinene/Sony-Alpha-Companion/actions) 打开最近一次成功的构建，下载 `Alpha-Companion-Debug-APK`，解压后安装 APK（支持 Android 10 及以上）。

仓库构建的预览版使用固定签名，后续版本可直接覆盖安装；如果手机上装过早期临时签名版本，第一次切换时需要先卸载旧版，详见 [APK 签名说明](docs/SIGNING.md)。

连接步骤：先在相机上进入对应模式（Smart Remote、发送到智能手机、发送到电脑），再到手机系统 Wi-Fi 里连上相机网络（RAW 模式需要相机和手机连同一个路由器/热点），最后打开应用连接。

## 文档导航

- [实机操作与测试说明](docs/DEVICE_TEST.md)
- [功能支持表](docs/SUPPORT.md)
- [版本状态与限制](docs/STATUS.md)
- [日志与诊断说明](docs/LOGGING.md)
- [协议边界与上游参考](docs/PROTOCOL_RESEARCH.md)
- [代码复用与许可](docs/REUSE.md)
- [APK 签名说明](docs/SIGNING.md)

## 构建

需要 Android Studio，或 JDK 17、Android SDK 36、Build Tools 35.0.0：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `gradlew.bat`，构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 许可证

项目采用 [GPL-3.0](LICENSE)。第三方代码来源、修改记录和许可证原文见 [docs/REUSE.md](docs/REUSE.md) 与 [licenses/](licenses/)。
