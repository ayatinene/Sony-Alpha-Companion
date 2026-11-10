# Sony Alpha Companion

给 Sony α7R II（ILCE-7RM2）用的 Android 客户端，当前版本 `0.1.14-preview`。

## 能做什么

- 遥控拍摄（机内 Smart Remote Control）：实时取景、快门、曝光参数、自动对焦、录像与间隔拍摄；支持拍摄页随手机旋转切换横竖屏，以及可选为手机快门回传的预览 JPEG 写入手机位置信息（EXIF）。可调参数由相机当前开放的接口决定。
- 遥控同热点相机图库：在同一 Smart Remote 连接内从拍摄页切至相机图库，按每页最多 20 个内容项倒序分页读取近期照片，支持按 `全部 / JPEG / ARW` 筛选、预览并下载原始 `.JPG` 与 `.ARW`，完成后返回拍摄。
- JPEG 传输（机内 Send to Smartphone）：浏览相机文件夹、切换网格/详情列表视图与排序（按拍摄时间、文件名或文件大小）、大图预览与缩放、多选或全选当前可见文件下载，可选相机提供的不同 JPEG 尺寸。
- RAW / 原文件传输（机内 Send to Computer，实验性）：在局域网下通过 PTP/IP 下载原始 `.ARW` 和 `.JPG`。连接后直接进入相机图库并分批读取文件列表、优先查找近期照片，支持暂停/继续查找、网格/详情列表切换、按 `全部 / JPEG / ARW` 筛选、排序、缩略预览与多选优先下载。需要先用电脑或应用设置里的 USB OTG 工具把接收端 GUID 登记到相机。
- 相机文件扫描与下载期间由前台服务通知维持后台执行；相机图库未完成的下载任务按相机标识和传输模式保存，重连同一台相机后（RAW 模式需待扫描完成）可核对文件列表并从头继续未完成的图库下载。
- 本地图库保存在 `DCIM/AlphaCompanion`，支持看大图、看 EXIF、同名 RAW/JPEG 关联、分享，也可以单独清除导入记录或直接删除照片文件。

## 安装与连接

在 [GitHub Actions](https://github.com/ayatinene/Sony-Alpha-Companion/actions) 打开最近一次成功的构建，下载 `Alpha-Companion-Debug-APK`，解压后安装 APK（支持 Android 10 及以上）。

仓库构建的预览版使用固定签名，后续版本可直接覆盖安装；如果手机上装过早期临时签名版本，第一次切换时需要先卸载旧版，详见 [APK 签名说明](docs/SIGNING.md)。

连接步骤：
1. 在相机上打开对应功能（`Smart Remote Control`、`发送到智能手机` 或 `发送到电脑`）。
2. 在手机系统 Wi-Fi 中连接网络：遥控拍摄、遥控同热点相机图库（可下载 `.JPG` / `.ARW` 原文件）和“发送到智能手机”均直连相机自身的 Wi-Fi 热点；仅实验性的“发送到电脑”模式需要相机与手机接入同一局域网/路由器热点。
3. 打开应用点击对应模式连接。遥控模式下可在拍摄页顶部点击“相机图库”浏览并下载原文件，完成后点击“返回拍摄”恢复取景。

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
