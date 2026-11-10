# 复用清单

项目整体采用 GPL-3.0（LICENSE），以容纳 ImagingEdge4Linux 的 Kotlin 移植。并不改变原有 MIT / LGPL 代码的授权。

| 路径 | 来源 | 改动 |
|---|---|---|
| `protocol/RemoteCamera.kt` | RealMadScientist/SonyRX10M3Remote `CameraController.kt`，MIT；提交见调查矩阵 | 提取 RPC、曝光、快门、AF、录像调用；移除 ImageView 和相册耦合；加入明确错误、能力门控、事件版本/状态、长曝光 await；全部 HTTP 使用绑定的 Wi-Fi |
| `protocol/SmartphoneTransfer.kt` / SOAP Browse 字段 | schorschii/ImagingEdge4Linux `imaging-edge.py`，Georg Sieber，GPL-3.0 | Python 传输流程移植 Kotlin；从 descriptor 读取 controlURL；按页返回列表、保留尺寸；零进展检查、结构化 fault、显式结束 |
| `com/fimagena/libptp/**` | Morning-Sea/SonyPhotoTransfer 携带的 Fimagena libptp，Copyright 2017 Fimagena，LGPL-2.1-or-later；流式修改来自 SonyPhotoTransfer | 保留源码头与许可；更改见下 |
| `protocol/ComputerTransfer.kt` | 适配 libptp 的使用方式参考 SonyPhotoTransfer `SonyCameraClient.kt`，MIT | 不使用任意 GUID；已登记 GUID 校验；网络 SocketFactory；序列化事务；按文件扩展名限定 JPEG/ARW；流式下载 |
| `protocol/SonyGuidRegistration.kt` / `network/UsbGuidRegistrar.kt` | sony-pm-alt / FireFoxG/sony-guid-setter（提交 `b281d817321b235112605761e3ff2467b4ae8e00`）及 USB BOT 报文 | 按已公开报文实现 Kotlin 编码与 Android USB Host 传输；保留原始研究者与 LGPL-2.1 说明。USB Host 授权、接口选择与界面在本项目实现 |
| Sony 特有协议取值 | airmtp/sony-pm-alt/libgphoto2 | 协议互操作参考 |

## libptp 修改记录

- `PtpPacketReader.java`：新增有界 64 KiB 数据包分块，支持一个 wire Data/EndData 包包含整张大照片；元数据有上限。
- `TcpConnection.java`：可注入 Network SocketFactory；连接超时；真正关闭 socket；使用分块 reader。
- `PtpIpConnection.java`：减少队列上限，初始化超时，关闭时通知等待者；提供 SocketFactory 注入。
- `PtpIpSession.java`：流式下载不再预分配全部文件长度；非流式元数据限制；事务超时/中断；空 EndData 修复；分块进度修复；接受 CloseSession 后对端正常关闭事件通道。
- `PtpOperation.java`：修复 max request parameters 赋值错误；允许 Sony 私有配对操作；错误响应不强制解析不存在的 DATA_IN。
- `PtpSession.java`：加入已知 Sony transfer-start set/get 对。
- `PtpConnection.java`：关闭遍历使用副本，避免移除 session 时修改集合。
- `PtpDataType.java`：获取操作列表供能力判断；PTP 日期使用 Locale.US，避免系统语言改变协议数字。
- 删除仅供上游手动测试的 PtpTester。

协议解析、网络/存储整合与新 UI 的其余代码在本项目实现。Alpha Capture Lab、SonyLiveMonitor、Sony 示例仅用于设计/协议核对，没有直接复制其无明确项目授权或补丁依赖代码。

许可文本在 `licenses/`，具体上游固定提交在 PROTOCOL_RESEARCH.md。构建依赖还包括 Apache-2.0 的 AndroidX/Kotlin/Coroutines/OkHttp；JUnit 测试依赖不随产品界面分发。
