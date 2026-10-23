# 协议与整合调查

调查日期：2026-10-23。主要代码已按下列固定提交检查；未在本环境编译全部上游工程，也没有连接相机。

## 三种模式

| 相机入口 | 手机协议 | 发现与端点 | 文件边界 | 本项目策略 |
|---|---|---|---|---|
| Smart Remote Control / Embedded | ScalarWebAPI / Camera Remote API，HTTP JSON-RPC | SSDP UDP 1900；`urn:schemas-sony-com:service:ScalarWebAPI:1`；从 LOCATION 描述文件读取 ActionList URL | Live View JPEG 流；拍照返回或 getEvent 报告的 JPEG 预览。不能据此承诺整卡 RAW | 复用 MIT 遥控实现，按能力启用功能 |
| Send to Smartphone | 旧款 Sony UPnP/SOAP + HTTP 资源下载 | SSDP；`DmsDescPush.xml` 是已知候选；读取 ContentDirectory / XPushList 的 controlURL | DIDL-Lite 中实际 `res` 资源；RAW 条目可能只提供 JPEG | 移植 ImagingEdge4Linux，不混用 PTP/IP 假设 |
| Send to Computer | MTP over PTP/IP；两条 TCP 通道 | SSDP `MtpNullService:1`；TCP 15740 | GetStorageIDs / GetObjectHandles / GetObjectInfo / GetThumb / GetObject；可含原始 ARW/JPEG | libptp 传输基础 + Sony 特有开始命令；已登记 GUID |

三个模式由机身手动切换，无需修改相机固件或额外安装相机端程序。

## 参考项目功能矩阵

“上游测试”指维护者描述，不代表本项目实测。编译状态列记录实际核查范围，不将存在 Gradle 文件等同于编译通过。

| 项目 / 固定提交 | 实际实现与上游测试 | 编译与架构 | 可复用内容 / 改动 | 本项目决定 |
|---|---|---|---|---|
| [Alpha Capture Lab](https://github.com/Rkm1999/alpha_capture_lab) `ab0f9c8cb9fee650d0a5f87c9888f746e754f817` | Android Kotlin/Compose；SSDP、XML、JSON-RPC、Live View、getEvent 1.2、机身快门 JPEG 队列、Live ND/Composite、全景/LUT。README 主要验证 A6300。协议类包含录像，Android 产品界面将录像列为 out of scope | 有完整 Gradle/测试；未编译上游；完整业务/界面各约 170 KB 并耦合计算摄影与图像处理依赖 | 协议层设计、状态与版本协商、测试边界有价值；整个 UI / Repository 并非最小基础 | 未发现其自身代码授权许可证，第三方 notices 不等于项目授权；不直接复制。以其行为设计核查 MIT 实现 |
| [SonyRX10M3Remote](https://github.com/RealMadScientist/SonyRX10M3Remote) `8f0c957dc285579dce473cef827c5e43b44563bb` | CameraController 完整 HTTP RPC、参数、AF、录像、连拍、B 门、间隔、avContent。作者主要 RX10 III | Android Kotlin + Compose，MIT；未编译上游 | 可提取控制层。原始 MJPEG 扫描跨 buffer 边界不稳，错误常返回 null，未解决长曝光完成语义 | 遥控模块基础；改为明确错误、能力门控、Sony 帧头解析、await 不重发快门、网络绑定 |
| [SonyLiveMonitor](https://github.com/otonielpv/SonyLiveMonitor) `59e967cdbd22b12965834568c9006c71937b98f9` | Android/iOS/Python Live View 与辅助工具，Android SonyCamera/LiveviewStream 可独立研究；机内 gallery/avContent 拓展有相机补丁 | 有 Gradle；未编译；上游主要 a6000 及补丁路径，不是 A7R2 验证 | 数据帧解析、低延迟显示、兼容性判断可参考；补丁依赖不移植 | 不安装相机补丁，不承诺原版 Smart Remote 整卡 ARW |
| [ImagingEdge4Linux](https://github.com/schorschii/ImagingEdge4Linux) `5317d416ed5296fc5a3343442b17276afb9ccc77` | `imaging-edge.py` 完整 Start → Browse/递归/分页 → HTTP 下载 → End；PushRoot 故障回退 PhotoRoot；GPL-3.0 | Python requests；未在相机运行；无需把 Python 放进 Android | 所有关键传输流程直接移植 Kotlin；从描述文件读实际 URL，限定页数并拒绝零进展，保留全部资源信息 | JPEG 模块主要来源 |
| [SonyPhotoTransfer](https://github.com/Morning-Sea/SonyPhotoTransfer) `6d39accd8c0b366be2efb4c72f11234aba015d51` | Kotlin/Compose，PTP/IP；枚举/缩略图/GetObject 到流/MediaStore。README 主要 ZV-E10 2.02，广泛机型列表不能替代 A7R2 测试 | Gradle，MIT；内含 Fimagena libptp LGPL-2.1；未编译上游 | 直接集成 libptp Java，复用其流式调用方式；不能采用“任意 GUID 都行”在旧款 Send to Computer 上的泛化 | RAW 实验模块基础；修复流式内存分配、TCP 包分块、队列、超时、SocketFactory 与关闭逻辑 |
| [sony-pm-alt](https://github.com/falk0069/sony-pm-alt) `9d1d17a57c288250e2181edaf0f55560c2bfa0e4` | Python 实际是 MtpNullService SSDP 监听器，调用外部 `gphoto2 -P --port ptpip:...`。含 C USB GUID setter；上游早期 ILCE-5000 | Python + C + libgphoto2，不是独立 Android 协议库；未编译 | GUID 绑定、局域网模式、会话开始/结束说明；不能只移植 Python 得到 RAW | 作为 RAW 流程与注册参考，不引入 Python/gphoto 子进程 |
| [airmtp](https://github.com/shezi/airmtp) `e69609c6a66f06e5be4e52cbd23a67bd77eb0819` | **包含 Sony 分支**：SSDP、GUID、对象三参数枚举、0x9280/9281 配对、会话结束/休眠、过滤/缓存/重试/续传；Sony 机型表属于泛称，无 A7R2 4.01 明确结果 | 完整 Python MTP 客户端；未运行上游 | Sony 开始命令以及必须成对 ack 的状态行为；续传/缓存可后续移植，不提前宣称已实现 | 重点协议参考；本版仅实现已知开始命令且需设备声明支持，不发未知结束/休眠命令 |
| [libgphoto2](https://github.com/gphoto/libgphoto2) `bceecb0f0365d015b564e4b08122e28eb16088db` | 原生 C PTP/IP、Sony/MTP 特例、设备配置；检查 ptpip.c、README.Sony 与 library.c 的 Sony 分支 | autotools/C/LGPL；Android NDK 集成需移植 build 和 I/O；未编译 | 原始协议参考与后续兼容性对照；首版不同时维护两套 PTP 实现 | 参考，先用已集成 Java libptp |
| [sony-guid-setter](https://github.com/FireFoxG/sony-guid-setter) `b281d817321b235112605761e3ff2467b4ae8e00` | C/libusb，Mass Storage/USB 命令设置接收端 GUID；增添设备检测；包含电脑预编译工具 | Windows/macOS 工具，非 Android USB Host 项目；未实际执行 | 未来 OTG 注册可移植 USB 包与传输步骤；现阶段电脑一次性登记 | 不冒充 Android 端已实现初始化 |
| [Camera Remote API Demo 镜像](https://github.com/benioZhang/SonyCamera-Remote-API-Demo) `ebfcd8bef25549f1e5d42ea7d4f957b3e5c76fd6` | Sony 示例 Java 客户端，SSDP/RPC/Event/LiveviewSlicer；这是第三方镜像，不是 Sony 官方仓库 | 旧版 Android 工程，含 Sony 版权示例；未尝试旧 AGP 编译 | 流格式与 API 使用顺序核对；不复制示例资源或 SDK | 协议参考 |

## Smart Remote：能力而非机型硬编码

官方对 [Embedded 与完整版的说明](https://www.sony.com/electronics/support/e-mount-body-ilce-7-series/ilce-7rm2/articles/00100204) 指出 ISO、光圈、快门控制取决于机内 Smart Remote Control，而不仅是固件。
固件版本不能推出机内应用版本；本版调用 getApplicationInfo / getAvailableApiList / getAvailable 参数查询。
不要求用户安装新的相机端程序；若 Embedded 未开放某项控制，UI 禁用，不绕过机内限制。

- JSON-RPC 请求：`method/params/id/version`，请求 ID 递增；明确处理 error。
- getEvent 先询问 getVersions / getMethodTypes；选有声明的版本，不支持时回退 1.0。
- Live View 不是普通 multipart MJPEG：8 字节公共头 + 128 字节 payload 头；magic `24 35 68 79`；24 位 payload 长度和 8 位 padding。类型 2 等辅助包必须消费并跳过。
- 遥控拍照返回 JPEG postview；原始文件是否仍在卡内由相机设置决定。预览与原尺寸不能混称。
- 长曝光返回 40403 / HTTP 响应超时后，若支持 awaitTakePicture 则等待结果；不重发 actTakePicture。
- AF 使用已声明的半按/取消对；录像只在 API 允许的相机模式启用。

## JPEG：真实完整流程与边界

1. 发现并请求设备描述 XML；读取 ContentDirectory、XPushList 的 `controlURL`。
2. PushList 存在时调用 X_TransferStart。
3. BrowseDirectChildren PushRoot；已知“不存在”类 SOAP fault 时尝试 PhotoRoot。
4. SOAP Result 是再次编码的 DIDL-Lite XML，需要两次解析；保留 item/container、标题、日期、res protocolInfo/size/resolution。
5. 按 NumberReturned/TotalMatches 分页，保留文件夹浏览；防止 0 返回但总数未到的无限循环。
6. 从实际 JPEG res 选择尺寸下载；不得把 `.ARW` 条目里的 JPEG 改后缀伪装成 ARW。
7. 保持会话以供继续选择，用户显式“结束连接”时 X_TransferEnd。没有多选后立即退出传输模式。

`XPlsDesc.xml` 在这种服务体系中是可见的服务描述线索，本版使用设备 description 中的服务 URL，不把文件路径硬编码为所有机型的事实。

## RAW：原始文件流程

[A7R2 官方 Send to Computer 指南](https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087161.html) 要求无线接入点与电脑登记；它不同于手机直连相机 AP。
参考实现给出的 PTP/IP 是 TCP 15740 上 command/event 两条连接，GUID/UTF-16 friendly name 初始化，然后 GetDeviceInfo、OpenSession、GetStorageIDs、带三参数的 GetObjectHandles、GetObjectInfo、GetThumb、GetObject。

airmtp 中 Sony Start：0x9280 参数 4，21 字节 payload，对应小端 `2,2,0,0,0x00020001` 和尾字节 0；随后必须 0x9281 参数 4 读取 ack。未 ack 会导致后续 busy。
本版只在 OperationsSupported 声明两个 opcode 时使用此配对。
普通 CloseSession 后机身可能要求退出再重进 Send to Computer；不把源项目的自动休眠行为默认搬入手机 UI。

libptp 修改使数据包即使是整张 85 MB ARW，也在 TCP 读取层分成 64 KiB，队列有界。写入 MediaStore 时检查长度，失败删除 pending 文件。
不把“已有 PTP/IP 代码”当作 GUID 登记和 A7R2 原文件传输已经完成。

## 已验证与仍需验证

已完成参考源码核查、协议解析测试、模拟会话和流式文件传输测试。另有 A7R II 原尺寸 JPEG 与 ARW 样本用于文件和元数据核对；样本检查不等于相机传输通过。
本地编译、自动测试、lint 的最终结果独立记录于 STATUS.md。

仍需实机：A7R2 的 Smart Remote 应用版本和能力、实际描述 XML/资源尺寸、getEvent 机身快门行为、AF/录像、OriginOS 存储与后台传输、已登记 GUID 的 PTP/IP 原文件校验。
下一步根据实际设备诊断确认兼容性；遇到资料不足时，明确记录问题并获取对应设备响应。
