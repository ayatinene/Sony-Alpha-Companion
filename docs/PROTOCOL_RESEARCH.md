# 协议边界与上游参考

Sony α7R II（ILCE-7RM2）的三种无线工作模式分别对应不同的机内入口与通信协议，在机身菜单中手动切换即可，不需要修改固件或安装非官方机内补丁。

## 三种模式概览

| 相机入口 | 手机侧协议 | 发现与端点 | 文件边界 | 本项目策略 |
|---|---|---|---|---|
| Smart Remote Control | ScalarWebAPI / Camera Remote API（HTTP JSON-RPC） | SSDP `UDP 1900`（`urn:schemas-sony-com:service:ScalarWebAPI:1`）；从描述文件读取 `ActionList` URL | 实时取景 JPEG 流；拍照返回或 `getEvent` 通知的 JPEG 预览图（postview），不能读取整卡 RAW | 复用 MIT 遥控控制层，按接口动态能力启用功能 |
| Send to Smartphone | Sony UPnP / SOAP + HTTP 下载 | SSDP 描述文件（如 `dd.xml`，缺失服务时回退探测 `DmsDescPush.xml`）；读取 `ContentDirectory` / `XPushList` 的 `controlURL` | DIDL-Lite 中实际列出的 `res` 资源；RAW 条目可能只提供内嵌 JPEG 预览 | 移植 `ImagingEdge4Linux`，不混用 PTP/IP 假设 |
| Send to Computer | MTP over PTP/IP（双 TCP 通道） | SSDP `MtpNullService:1`；`TCP 15740` | `GetStorageIDs` / `GetObjectHandles` / `GetObjectInfo` / `GetThumb` / `GetObject`，可包含原始 ARW 与 JPEG | 基于 `libptp` 流式传输 + Sony 专有开始命令，需已登记 GUID |

## Smart Remote Control：按动态能力开放控制

[Sony 官方说明](https://www.sony.com/electronics/support/e-mount-body-ilce-7-series/ilce-7rm2/articles/00100204)指出，ISO、光圈、快门速度等控制取决于机内安装的 Smart Remote Control 应用版本，不能仅凭机身固件版本推断。连接后应用会调用 `getApplicationInfo`、`getAvailableApiList` 和各参数的 `getAvailable*` 查询：未开放的参数在界面上只读或禁用，不绕过机内限制。

- JSON-RPC 请求带递增 `id`，明确处理 `error` 响应。
- `getEvent` 先通过 `getVersions` / `getMethodTypes` 查询可用版本，优先使用已声明的版本，不支持时回退 `1.0`。
- Live View 流由 8 字节公共头加 128 字节 payload 头组成（幻数 `24 35 68 79`，含 24 位 payload 长度和 8 位 padding），对焦框等类型 2 辅助包需读取并跳过。
- 遥控拍照回传的是 JPEG postview，原文件是否写卡由机身设置决定。长曝光返回 `40403` 或等待超时后，若支持 `awaitTakePicture` 则等待结果，不重发 `actTakePicture`。
- AF 使用接口声明的半按/取消对（仅在当前会话仍持有该相机实例时释放半按）；录像仅在 API 允许的相机模式下启用。
- 正常断开（或初始化后续步骤失败回滚）时，先尝试 `stopLiveview`，且仅在本应用曾显式收到 `startRecMode` 成功响应、同时相机未处于录像中（`MovieRecording`）时才调用 `stopRecMode`，绝不自动停止正在进行的录像；Wi-Fi 断开或后台强制取消时先关闭底层 HTTP 连接，不补发任何清理 RPC。

## Send to Smartphone：UPnP 浏览与下载

1. 通过 SSDP 获取设备描述 XML，读取 `ContentDirectory` 与 `XPushList` 的 `controlURL`；只有广播描述缺少对应服务时才探测备用描述路径。
2. `XPushList` 存在时调用 `X_TransferStart`；若明确返回不支持（HTTP 404/405/501 或 UPnP `Invalid Action`），继续浏览目录。
3. 调用 `BrowseDirectChildren` 先查 `PushRoot`，遇到“不存在”类 SOAP fault 时回退 `PhotoRoot`。
4. SOAP `Result` 是二次编码的 DIDL-Lite XML，需再次解析以读取文件夹和照片标题、日期及各 `res` 的 `protocolInfo`、`size`、`resolution`。
5. 按 `protocolInfo` 的冒号与分号分隔字段识别 `DLNA.ORG_PN`（`JPEG_ORG` / `JPEG_ORIGINAL` 或无 `DLNA.ORG_PN` 的非空 `protocolInfo` 识别为原尺寸 JPEG，`JPEG_LRG` 为大图，`JPEG_SM` 为小图，`JPEG_TN` 为缩略图）；`.ARW` 条目下的 JPEG 预览保持真实 `.JPG` 类型，不伪装成 ARW。
6. 按 `NumberReturned` / `TotalMatches` 分页并限制最大页数；若单页返回 0 条但尚未达到总数，直接报错拒绝零进展，防止无限循环。
7. 正常断开或初始化浏览失败回滚时，对已成功完成的 `X_TransferStart` 做原子一次性认领并发送单次 `X_TransferEnd`（失败或并发收尾均不重发）；Wi-Fi 断开或后台强制取消时不发送。

## Send to Computer：PTP/IP 原文件流程

[A7R II 官方 Send to Computer 指南](https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087161.html)要求无线接入点环境以及预先向相机登记接收端，不同于手机直连相机热点。

1. 在 TCP 15740 建立 command 与 event 两条连接，发送已登记的 16 字节 GUID 和 UTF-16 主机名完成初始化，随后执行 `OpenSession`、`GetDeviceInfo`、`GetStorageIDs`、带三参数的 `GetObjectHandles`、`GetObjectInfo`、`GetThumb`、`GetObject`。
2. `OpenSession` 若遇到相机端仍保持旧会话并返回 `SessionAlreadyOpen`（`0x201E`，按规范携带 1 个 `SessionID` 参数），解析层会校验该参数并将其作为明确的 `OperationFailed` 抛出（不误判为协议格式损坏，也不伪装成打开成功），提示在机身退出并重新进入“发送到电脑”。
3. 参考 `airmtp` 的 Sony 开始与休眠命令：当 `OperationsSupported` 同时声明支持 `0x9280` 和 `0x9281` 时，连接建立阶段先发 `0x9280`（参数 4，21 字节小端负载 `2, 2, 0, 0, 0x00020001` 加尾字节 0），随后必须发 `0x9281`（参数 4）读取确认（α7R II 实机日志已确认两项均返回 `0x2001 OK`），否则后续请求会持续 busy。不发送语义不明的未知结束或取消指令；正常健康空闲会话断开（含自动重连或切换模式前的会话收尾）默认仅在事务锁空闲时执行标准 `CloseSession`（自动重连与切换模式不尝试相机休眠；扫描/下载进行中取消或断开、断网、后台取消、在途不确定事务或初始化失败时直接强制 `abort` 底层连接，不阻塞等待）。仅当用户在设置中显式开启默认关闭的“断开时让相机休眠（实验性）”、由用户手动点击断开、会话已完成开始握手且协议调度层非阻塞原子获锁（`tryAcquireIdle`）时，才在同一个事务锁与有界宽限期内先成对发送 `airmtp` 记录的唯一 19 字节 Sony 休眠动作 `0x9280`（参数 4，19 字节小端负载 `00 00 00 00 05 00 00 00 00 00 00 00 00 00 00 00 02 00 30`，即 4 个 uint32 `0, 5, 0, 0` 加尾字节 `2, 0, 0x30`）与 `0x9281`（参数 4）确认，成功后再发 `CloseSession`；任一步失败或超时均立即 `abort` 底层连接（不再继续发 `CloseSession`、不重试），若分离关闭协程未在回收预算内退出则记录告警。
4. 同一 PTP/IP 会话严格保持单会话、单在途事务串行执行（不做多请求并发或字节级断点续传）：连接建立后先拉取全部存储卡与句柄总数，随后每个存储卡按相机返回的句柄数组从后往前逆向（`asReversed`，不按句柄数值或文件名排序，利用实测存储卡由旧到新顺序优先读取较新文件，但不宣称协议严格保证所有卡均为新到旧顺序）逐个执行 `GetObjectInfo` 并分批发布平铺文件快照；下载事务在两个扫描事务之间优先抢占，缩略图与预览在事务间隙公平穿插，不会向正在传输的数据流中穿插命令。
5. `libptp` 读取层将大文件数据包按 64 KiB 分块流式写出，写入 MediaStore 时核对总长度，失败则清理未完成文件。

## 参考项目与固定提交

上游源码按以下固定提交核查（机器可读清单见 [source-versions.json](source-versions.json)）：

| 项目与固定提交 | 上游内容 | 本项目采用策略 |
|---|---|---|
| [Rkm1999/alpha_capture_lab](https://github.com/Rkm1999/alpha_capture_lab) `ab0f9c8cb9fee650d0a5f87c9888f746e754f817` | Android Kotlin/Compose；SSDP、JSON-RPC、Live View、`getEvent` 1.2、机身快门队列等；未发现仓库自身开源许可证 | 不复制代码；仅参考其协议状态与版本协商设计 |
| [RealMadScientist/SonyRX10M3Remote](https://github.com/RealMadScientist/SonyRX10M3Remote) `8f0c957dc285579dce473cef827c5e43b44563bb` | Android Kotlin，MIT；`CameraController` 包含完整 HTTP RPC、曝光参数、AF、录像、间隔拍摄 | 作为遥控控制层基础；改为明确错误处理、能力门控、`awaitTakePicture` 等待与 Wi-Fi 绑定 |
| [otonielpv/SonyLiveMonitor](https://github.com/otonielpv/SonyLiveMonitor) `59e967cdbd22b12965834568c9006c71937b98f9` | 含 Live View 数据帧解析；机内相册扩展依赖相机端非官方补丁 | 仅参考数据帧头解析；不采用任何相机补丁方案 |
| [schorschii/ImagingEdge4Linux](https://github.com/schorschii/ImagingEdge4Linux) `5317d416ed5296fc5a3343442b17276afb9ccc77` | Python，GPL-3.0；完整 Start → Browse/递归/分页 → HTTP 下载 → End，`PushRoot` 回退 `PhotoRoot` | 移植为 Kotlin 实现 JPEG 传输模块，保留全部资源字段并加入分页零进展保护 |
| [Morning-Sea/SonyPhotoTransfer](https://github.com/Morning-Sea/SonyPhotoTransfer) `6d39accd8c0b366be2efb4c72f11234aba015d51` | Android Kotlin，MIT，内含 Fimagena `libptp`（LGPL-2.1）；PTP/IP 枚举、缩略图与流式 `GetObject` | 作为 RAW 实验模块基础；修复 `libptp` 流式内存分配、64 KiB 分块、超时与关闭逻辑，要求已登记 GUID |
| [falk0069/sony-pm-alt](https://github.com/falk0069/sony-pm-alt) `9d1d17a57c288250e2181edaf0f55560c2bfa0e4` | Python `MtpNullService` SSDP 监听 + C 语言 USB GUID 写入工具 | 作为 RAW 会话流程与 USB GUID 登记报文参考 |
| [shezi/airmtp](https://github.com/shezi/airmtp) `e69609c6a66f06e5be4e52cbd23a67bd77eb0819` | Python MTP 客户端，含 Sony 分支（SSDP、GUID、三参数枚举、`0x9280`/`0x9281` 配对及 19 字节断开休眠动作） | 参考 Sony 开始命令（成对读取确认）及默认关闭的可选空闲手动断开休眠命令 |
| [gphoto/libgphoto2](https://github.com/gphoto/libgphoto2) `bceecb0f0365d015b564e4b08122e28eb16088db` | C 语言 PTP/IP 与 Sony 特例实现（LGPL） | 作为底层协议参考与兼容性对照 |
| [FireFoxG/sony-guid-setter](https://github.com/FireFoxG/sony-guid-setter) `b281d817321b235112605761e3ff2467b4ae8e00` | C / `libusb`（LGPL-2.1），通过 Mass Storage 命令写入接收端 GUID | 参考其公开 USB 报文结构，实现 Android USB OTG 登记功能 |
| [benioZhang/SonyCamera-Remote-API-Demo](https://github.com/benioZhang/SonyCamera-Remote-API-Demo) `ebfcd8bef25549f1e5d42ea7d4f957b3e5c76fd6` | Sony 官方示例客户端的第三方镜像（含 RPC、Event、`LiveviewSlicer`） | 核对流格式与 API 调用顺序，不复制示例资源或 SDK |
