# 代码复用与许可

项目整体采用 [GPL-3.0](../LICENSE)，以兼容自 `ImagingEdge4Linux`（GPL-3.0）移植的 Kotlin 实现；原有 MIT 与 LGPL-2.1 代码保留各自的版权头与许可证说明（见 [licenses/](../licenses/)）。上游固定提交见 [source-versions.json](source-versions.json) 与 [PROTOCOL_RESEARCH.md](PROTOCOL_RESEARCH.md)。

| 本项目路径 | 上游来源 | 主要改动 |
|---|---|---|
| `protocol/RemoteCamera.kt` | `RealMadScientist/SonyRX10M3Remote` 的 `CameraController.kt`（MIT） | 提取 RPC、曝光参数、快门、AF、录像调用；去掉原工程的 ImageView 和相册耦合；加入明确错误处理、能力门控、事件版本协商、基于 `getVersions`/`getMethodTypes` 与 `getSupportedCameraFunction` 的相机功能切换（`setCameraFunction`）能力探测、长曝光 `awaitTakePicture` 等待、`stopLiveview`、`startRecMode` 显式成功所有权与非录像态 `stopRecMode` 收尾及 Wi-Fi 网络绑定 |
| `protocol/SmartphoneTransfer.kt` | `schorschii/ImagingEdge4Linux` 的 `imaging-edge.py`（Georg Sieber，GPL-3.0） | 将 Python 传输流程移植为 Kotlin；从描述文件读取 `controlURL`；按页返回目录、保留各尺寸资源、加入分页零进展报错、结构化 SOAP fault 与一次性原子认领的 `X_TransferEnd` |
| `com/fimagena/libptp/**` | `Morning-Sea/SonyPhotoTransfer` 携带的 Fimagena `libptp`（Copyright 2017 Fimagena，LGPL-2.1-or-later，含 `SonyPhotoTransfer` 的流式接口修改） | 保留源码版权头与 LGPL-2.1 许可，具体修改见下节 |
| `protocol/ComputerTransfer.kt`、`protocol/PtpTransaction.kt` | 参考 `Morning-Sea/SonyPhotoTransfer` 的 `SonyCameraClient.kt`（MIT）与 `shezi/airmtp` | 不使用随机 GUID，改为校验已登记 GUID；注入网络 `SocketFactory`；通过 `SessionScheduler` 将存储卡、句柄、`GetObjectInfo`、缩略图与流式 `.JPG`/`.ARW` 下载拆分为单事务串行调度（下载优先于扫描）；通过 `SessionGate` / `FinishSequence` / `GracefulCloser` 区分空闲正常关闭（标准 `CloseSession` + 有界超时中止与工作线程回收告警，仅手动空闲断开且开启实验开关时在关闭前尝试 `airmtp` 休眠对）与忙碌/取消/异常强制 `abort` |
| `protocol/SonyGuidRegistration.kt`、`network/UsbGuidRegistrar.kt` | 参考 `falk0069/sony-pm-alt` 与 `FireFoxG/sony-guid-setter`（提交 `b281d817321b235112605761e3ff2467b4ae8e00`，LGPL-2.1） | 按公开的 USB Mass Storage BOT 报文实现 Kotlin 编码与 Android USB Host 传输；USB 权限请求、接口匹配与设置界面由本项目实现 |
| Sony 特有协议常量 | `shezi/airmtp`（固定提交 `e69609c6a66f06e5be4e52cbd23a67bd77eb0819`）、`falk0069/sony-pm-alt`、`gphoto/libgphoto2` | 协议互操作参考（含 `airmtp` 记录的开始握手与可选断开休眠命令，未引入额外第三方许可证） |

## `libptp` 修改记录

- `PtpPacketReader.java`：新增有界 64 KiB 数据包分块，支持单个 wire `Data`/`EndData` 包承载整张大体积照片；非流式元数据增加长度上限。
- `TcpConnection.java`：支持注入 `SocketFactory`；增加连接超时；关闭时真正释放底层 socket 并使用分块 reader；队列投递改为有界超时轮询，并对 `Init*` 握手包日志省略负载（不输出接收端 GUID 与主机名）。
- `PtpIpConnection.java`：减小接收队列上限，加入初始化超时与 `SocketFactory` 注入；实现幂等关闭与首发异常（`mFirstFailure`）保真唤醒，防止后续 `close()` 的 `"Connection closed"` 掩盖真实传输错误；显式校验 `OpenSession` 的 `SessionAlreadyOpen`（`0x201E`，单 `SessionID` 参数）并作为失败返回；支持挂载 `PtpLogObserver`。
- `PtpIpExceptions.java`：`testError` 保留原始 `IOError`、`ProtocolViolation`、`MalformedPacket` 类型及 `cause` 异常链；`OperationFailed` 保存响应码与响应参数供上层诊断。
- `PtpIpSession.java`：流式下载不再按文件总长预分配内存；限制非流式元数据大小；加入事务超时与中断处理；修复空 `EndData` 包与分块进度计算；容忍 `CloseSession` 后对端正常关闭事件通道；日志仅记录操作码、响应码、参数与耗时，不输出数据负载。
- `PtpOperation.java`：修复 `max request parameters` 赋值错误；支持 Sony 私有成对操作；错误响应不再强制读取不存在的 `DATA_IN`；`PtpDataType.Object` 响应仅记录长度，不打印照片字节。
- `PtpLogObserver.java`、`PtpLogRecord.java`：新增不依赖 Android 的结构化协议日志观察者接口与记录模型，脱敏输出阶段、操作码、事务号、响应码、参数、耗时与异常原因链。
- `PtpSession.java`：加入已知的 Sony transfer-start（`0x9280` / `0x9281`）与参考同一 `airmtp` 来源的可选断开休眠调用。
- `PtpConnection.java`：关闭时遍历会话列表副本，避免移除会话时触发并发修改异常。
- `PtpDataType.java`：读取支持的操作码列表供能力判断；PTP 日期固定使用 `Locale.US`，避免手机系统语言影响协议数字格式。
- 删除仅供上游桌面端手动测试的 `PtpTester`。

Live View 分帧读取（`LiveViewReader`）、遥控同连接图库只读协议层（`AvContentTransfer`）、SSDP 与描述文件解析、下载队列与存储管理、日志诊断及 Android 界面均在本项目内独立实现。`alpha_capture_lab`、`SonyLiveMonitor`（固定提交 `37d09e928b16e9984d9500d7a02956f42698bbd5`，未发现项目开源许可证）和 `SonyCamera-Remote-API-Demo` 仅作公开协议行为参考，未复制其代码。构建依赖还包括 Apache-2.0 授权的 AndroidX、Kotlin、Coroutines 和 OkHttp。
