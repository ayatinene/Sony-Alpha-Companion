# 二维码与 NFC 配对说明

首页保留原有三种手动连接入口，并提供 **扫描相机二维码** 与 **NFC 配对**。一触分享的协议细节见 [IMAGING_EDGE_ONE_TOUCH_SHARING.md](IMAGING_EDGE_ONE_TOUCH_SHARING.md)。

## 1. 连接与操作步骤

### 扫描相机二维码（遥控拍摄）
1. 在相机上打开机内 `Smart Remote Control`，停留在二维码界面，并保持手机 Wi-Fi 开启。
2. 在应用首页点击 **扫描相机二维码**，按提示授予相机权限与 Wi-Fi 配对权限。
3. 扫描机身屏幕上的二维码，并在 Android 系统弹窗中确认连接相机热点。
4. Wi-Fi 连接并发现相机服务后自动进入遥控拍摄界面。等待期间可在首页点击 **取消连接** 中止。

### NFC 一触连接（遥控拍摄 / 一触分享自动导图）
1. 开启手机与相机的 NFC，**先打开本应用并保持在前台**。
   - 相机内置 NFC 标签包含指向索尼官方应用（`com.sony.playmemories.mobile`）的 Android 应用记录（AAR）；本应用在前台时通过前台分发（Foreground Dispatch）优先接收标签，未打开或退至后台时触碰可能启动官方应用。
2. 将手机 NFC 感应区靠近机身 N 标识约 2 秒，按系统提示确认连接相机 Wi-Fi：
   - **相机处于拍摄状态**（机内一触功能设为智能遥控）：识别 `ScalarWebAPI` 服务后进入遥控拍摄。
   - **相机处于回放或“发送到智能手机”状态**：识别 `ContentDirectory` 与 `XPushList` 服务后，自动按**最大可用尺寸**导入当前单张或机内勾选的共享 JPEG 至 `DCIM/AlphaCompanion`，导入完成后自动通知相机并断开连接。
   - **仅提供目录浏览而无分享服务**：进入相机图库，由用户手动选择照片下载。

### 手动连接
原有连接方式不变：先在系统 Wi-Fi 设置中连接相机热点或同一局域网，再点击首页对应模式卡片连接。

## 2. 系统权限与平台行为

| Android 版本 | 配对所需权限与系统条件 |
|---|---|
| Android 10–11（API 29–30） | 使用 `WifiNetworkSpecifier` 请求相机热点；需授予前台位置权限（含精确位置）并开启系统定位开关。 |
| Android 12 / 12L（API 31–32） | 需同时授予大致位置与精确位置权限（仅大致位置无法发起本地 Wi-Fi 请求），并开启系统定位开关。 |
| Android 13–16（API 33–36） | 仅请求“附近 Wi-Fi 设备”权限（声明 `neverForLocation`），无需位置权限。 |

- **相机权限**：仅在打开二维码扫描页（`CameraQrActivity`）时按需使用，暂停或退出扫描立即释放相机，不保存任何扫码画面。
- **不采集位置、不改动系统网络配置**：配对过程不读取手机地理位置，不申请后台位置权限，也不修改或保存系统 Wi-Fi 列表。
- **网络绑定范围**：应用仅对系统 `onAvailable` 返回的相机 `Network` 发起 SSDP 发现与 HTTP 请求，不做进程级全局网络绑定。支持多网络的设备可同时保持移动数据上网；单 Wi-Fi 通道手机在连接相机热点期间会暂时切换掉原有路由器 Wi-Fi。

## 3. 限制与边界

- **临时凭据生命周期**：扫码或 NFC 解析出的 SSID 与密码仅保存在当前会话内存中，供连接存续期间点击“重新连接相机”复用；断开连接、配对失败或进程结束后立即清除，后续连接需重新扫码或触碰 NFC。切换另一台相机前请先断开当前连接。
- **发现与超时**：Wi-Fi 连接建立后，SSDP 发现最多重试 3 轮（每轮 4 秒，间隔 750 毫秒，总计不超过 20 秒），忽略设置页为手动模式填写的自定义描述文件 URL。系统 Wi-Fi 请求等待上限为 60 秒；Android 公共回调无法区分用户点取消、密码错误或热点未开启，失败时统一提示可能原因并释放网络请求。
- **NFC 一触分享边界**：
  - 优先读取 `DigitalImagingDesc.xml` 中的当前单张内容，不存在时枚举 `PushRoot` 及其子目录，**绝不回退读取整张存储卡（`PhotoRoot`）**。
  - 仅自动导入相机提供的最大可用 JPEG（RAW/MPO 条目仅导入其衍生 JPEG，不会将原始 ARW 误存为 JPEG）；遇到视频、HEIF、仅有缩略图、空选集或目录异常时直接报错停止，不会冒充导入成功。
  - 单次最多支持 2,000 张照片、256 个子目录、16 层目录，且在入队前检查 2,000 条队列上限；历史未完成下载任务保持“待恢复”状态，不自动混入本次分享。
  - 导入期间由前台服务维持运行，可在界面顶部或系统通知中点击 **取消导入**（已保存的照片保留，未完成临时文件自动清理）。网络中断或系统强制停止时仅保证本地清理，无法保证相机收到结束通知。

## 4. 配对数据格式与依赖许可

以下协议格式根据公开 Android API、实机标签及官方客户端 `com.sony.playmemories.mobile`（7.8.6，`base.apk` SHA-256 `c466640e136e2fe4bc1a50a3ab995d028c96f71921b5f973594a7a7114ec3501`）静态分析独立实现，未包含任何索尼代码或资源：

- **二维码格式（W01）**：
  - 前缀固定为 `W01:`，后接以 `;` 结尾的 `Key:Value` 字段，支持 `\` 转义 `; , : \`。
  - 字段包括 `S`（4 字符前缀）、`C`（相机名）、`P`（8–63 位可打印 ASCII WPA2 密码）及可选 `M`（本应用忽略 `M`，不将其作为 BSSID 或认证凭据）；完整 SSID 为 `DIRECT-<S>:<C>`（不超过 32 个 UTF-8 字节）。不支持旧版 10 字符短码、`W02+` 或蓝牙配对码。
- **NFC NDEF 格式（`application/x-sony-pmm`）**：
  - 标准 NFC Forum Type 3（FeliCa RC-S926）只读 NDEF 消息，包含一条 `application/x-sony-pmm` MIME 记录和一条指向 `com.sony.playmemories.mobile` 的 AAR 记录。
  - MIME 负载由多段私有 TLV 组成：2 字节 Tag + 2 字节长度（按 `unsigned(len[0]) * 10 + unsigned(len[1])` 计算字节数，非十六进制 16 位整数）+ 对应值：
    - `00 01`：版本，单字节 `0x10`
    - `10 00`：完整 SSID（UTF-8）
    - `10 01`：WPA2 密码（UTF-8 可打印字符）
    - `10 04`：Wi-Fi 配置可用标志（可选，存在时需为 `0x01`）
  - 诊断日志仅记录配对来源、Wi-Fi 可用状态与识别到的服务模式，不输出密码或原始标签负载。
- **第三方组件许可**：
  - 二维码扫描使用嵌入式 `com.journeyapps:zxing-android-embedded:4.3.0`（及 `com.google.zxing:core`），采用 Apache-2.0 许可证，不依赖 Google Play 服务或外部扫码应用。

## 5. 验证状态

- **自动化测试**：JVM 单元测试与 Android 运行时集成测试已覆盖 W01 二维码与 NFC 二进制长度解析、密码脱敏、模拟 Wi-Fi 审批/拒绝/超时/断网、生命周期重建、SSDP 服务选择、DID 单张与 `PushRoot` 多选/子目录遍历、RAW/MPO 衍生 JPEG 筛选、去重、取消及 `X_TransferEnd` 结束码。
- **实机验证（Sony ILCE-7RM2）**：
  - 已在实机确认：二维码配对进入遥控拍摄并显示实时取景（vivo V2502A，Android SDK 37）；NFC 触碰进入遥控拍摄、NFC 触碰进入 JPEG 传图并下载单张原尺寸 JPEG（`DSC02200.JPG`，`2,490,368` 字节）后正常发送 `X_TransferEnd` 断开（Xiaomi 23127PN0CC，Android SDK 35）。
  - 待实机验证：`0.1.16-preview` 新增的 NFC 一触分享自动导入协调流程（触碰后免点下载直接落盘、机内多选批量自动导入、导入途中点击“取消导入”）。

## 参考资料

- [Android Wi-Fi Network Request API (WifiNetworkSpecifier)](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)
- [Android Wi-Fi 权限说明](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)
- [Android NFC 应用记录 (AAR) 与前台分发](https://developer.android.com/develop/connectivity/nfc/advanced-nfc)
- [Sony ILCE-7RM2 一触遥控与一触分享指南](https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087160.html)
- [zxing-android-embedded (Apache-2.0)](https://github.com/journeyapps/zxing-android-embedded)
