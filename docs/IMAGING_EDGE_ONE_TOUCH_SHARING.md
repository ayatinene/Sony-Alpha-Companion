# 一触分享（XPushList）协议与行为参考

本文记录 Sony 一触分享（One-touch sharing）的无线协议流程，以及本应用在 `0.1.16-preview` 中的独立适配方案与边界。协议依据来自对 `com.sony.playmemories.mobile`（7.8.6，`base.apk` SHA-256 `c466640e136e2fe4bc1a50a3ab995d028c96f71921b5f973594a7a7114ec3501`）的静态控制流分析及 α7R II（ILCE-7RM2）实机描述文件响应，未复制官方代码或资源。操作步骤与 NFC 标签格式见 [PAIRING.md](PAIRING.md)。

## 1. 协议概览与模式判定

- **NFC 仅传递 Wi-Fi 凭据**：机身 NFC 标签（`application/x-sony-pmm`）只提供相机热点的 SSID 和密码，不包含当前工作模式、照片 URL 或共享张数。
- **按 SSDP 广播服务区分模式**：手机连上相机热点后，通过当前 SSDP 广播的设备描述区分服务：
  - 广播 `ScalarWebAPI`：相机处于拍摄模式（一触遥控），进入遥控拍摄。
  - 广播 `ContentDirectory` 与 `urn:schemas-sony-com:service:XPushList:1`：相机处于回放单张分享或“发送到智能手机”一触分享状态，执行 Push 自动导入。
  - 仅广播 `ContentDirectory`（无 `XPushList`）：不触发自动导入，打开相机图库供用户手动浏览和下载。

## 2. 共享内容的两种获取路径

一触分享启动后，先向 `XPushList` 发送 `X_TransferStart`，随后按相机提供的描述获取待导入列表：

| 路径 | 触发条件 | 内容获取方式 |
|---|---|---|
| DID 单张快速路径 | `DigitalImagingDesc.xml` 中存在有效的 `<X_CurrentContent_URL>` | 直接从 `<X_CurrentContent_URL_URL>` 读取当前单张照片的各尺寸 URL（`profileID` 含 `UNKNOWN`、`JPEG_LRG`、`JPEG_SM`、`JPEG_TN`），无需调用 `ContentDirectory#Browse`。 |
| `PushRoot` 共享列表路径 | 未提供 `DigitalImagingDesc.xml` 或无当前内容条目 | 调用 `ContentDirectory#Browse`（`BrowseDirectChildren`）枚举 `ObjectID=PushRoot` 及其子容器，读取机内已勾选的共享照片集合。 |

- **实机报文佐证**：α7R II 在单张一触分享时，既在 `PushRoot` 下返回该张照片（`DSC02200.JPG`），也在 `DigitalImagingDesc.xml` 的 `X_CurrentContent_URL` 中列出同一照片的 `JPEG_TN`、`JPEG_SM`、`JPEG_LRG` 与 `UNKNOWN`（`type=JPG`，`2,490,368` 字节，`7952×5304`）。
- **禁止回退 `PhotoRoot`**：手动“发送到智能手机”在 `PushRoot` 不可用时可回退浏览整卡 `PhotoRoot`；但 NFC 自动导入若遇到 `PushRoot` 为空或报错必须直接作为失败结束，避免在未识别到共享列表时误将整张存储卡全部下载。

## 3. XPushList SOAP 动作与结束码

服务类型：`urn:schemas-sony-com:service:XPushList:1`

| SOAP Action | 请求参数 | 说明 |
|---|---|---|
| `X_TransferStart` | 无 | 进入分享传输状态；若相机明确不支持该动作，自动导入立即中止。 |
| `X_GetPushRoot` | 无 | 开始复制时发送的辅助通知，超时或失败不阻塞下载。 |
| `X_TransferProgress` | `<NumTotal>`、`<NumTransferd>` | 通知已处理的条目数（协议字段名固定拼写为 `NumTransferd`），超时或失败不阻塞下载。 |
| `X_TransferEnd` | `<ErrCode>` | 结束本次分享传输，每个已启动会话最多发送一次，发送后释放相机 Wi-Fi 连接。 |

`X_TransferEnd` 的 `<ErrCode>` 映射规则：

| 导入结果 | `ErrCode` | 行为说明 |
|---|---|---|
| 全部成功（含本地已存在跳过） | `0` | 正常结束并断开相机 Wi-Fi。 |
| 部分失败（至少 1 张成功） | `0` | 保留已保存照片，界面提示成功/已存在/失败张数，发送 `0` 后断开。 |
| 用户取消（准备阶段或下载途中） | `2` | 清理未完成的临时文件，保留已保存照片，发送 `2` 后断开。 |
| 全部失败 / 准备或校验失败 | `1` | 无照片成功导入，界面报告具体错误原因，发送 `1` 后断开。 |

注：若相机 Wi-Fi 已断开或应用被系统强制终止，仅执行本地清理与网络释放，不保证相机端收到 `X_TransferEnd`。

## 4. 本应用实现与官方客户端的差异

| 维度 | 官方客户端（Imaging Edge Mobile 7.8.6） | 本应用（Alpha Companion `0.1.16-preview`） |
|---|---|---|
| 导入前确认弹窗 | 弹出导入尺寸确认框（OK / Cancel）后才开始复制 | 触碰 NFC 并确认系统 Wi-Fi 后直接开始自动导入，不额外弹窗 |
| 默认导入尺寸 | 未修改设置时默认 `TwoMegaPixels`（约 2 MP） | 固定选择相机提供的**最大可用 JPEG**（原尺寸优先） |
| RAW / MPO 处理 | Original 设置下对 RAW/MPO 选 2 MP JPEG 衍生图 | 排除 `UNKNOWN` 原始非 JPEG 资源，选择相机提供的最大可用衍生 JPEG 并以 `.JPG` 保存 |
| 不支持的格式 | 视频按机型分支复制或跳过，HEIF 跳过并提示 | 遇到视频、HEIF、仅含缩略图或无有效 JPEG 资源的条目时整批拒绝并明确报错 |
| 文件去重与队列 | 本地文件重名时换名保存 | 复用应用下载队列与 `PhotoStore` 去重；历史未完成任务保持“待恢复”，不自动混入本次分享 |

## 5. 验证状态

- **实机已验证（`0.1.14-preview` 日志）**：α7R II 在回放分享状态下通过 NFC 连接 Wi-Fi、识别 JPEG 服务、`X_TransferStart`、`Browse PushRoot`、下载单张原尺寸 JPEG（`2,490,368` 字节）及 `X_TransferEnd` 正常断开。
- **自动化测试已验证（`0.1.16-preview`）**：DID 单张解析、端口变化稳定性、RAW/MPO 衍生 JPEG 过滤、`PushRoot` 多文件与子目录有界遍历、空选集/循环目录/超限拒绝、历史队列隔离、部分失败/全部失败/取消的 `ErrCode` 映射及 MediaStore 落盘与清理。
- **待实机验证**：`0.1.16-preview` 接入的 NFC 触碰后全自动单张导入、机内多选批量导入及中途点击“取消导入”。
