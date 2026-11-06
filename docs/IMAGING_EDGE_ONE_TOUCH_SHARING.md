# Imaging Edge Mobile 7.8.6：一触分享完整行为追踪

分析日期：2026-11-06。对象是用户提供的 APKS 中的 base.apk，包名
`com.sony.playmemories.mobile`，SHA-256：
`c466640e136e2fe4bc1a50a3ab995d028c96f71921b5f973594a7a7114ec3501`。
使用 Androguard DEX 指令检查、DAD 反编译和 resources.arsc / 二进制布局解析。
以下记录独立归纳的控制流及协议，不包含官方反编译代码、私有组件或素材。

## 结论与边界

官方的一触分享是 **Push 导入流程**：连接后确认当前导入尺寸，确认后自动复制相机共享集合，
而不是打开图库让用户逐张选下载。集合可能是一张，也可能多张；NFC 标签中的 SSID / 密码
不携带“总共一张”的承诺。相机的拍摄 / 播放 / 发送到智能手机状态决定启动何种服务。

本报告追踪了 NFC 入口到 Wi-Fi、服务分类、单张 / 列表、尺寸选择、下载、存储、进度、成功、
取消和错误退出的静态控制流。它不等于已在 A7R II 上运行官方 App 验证所有分支，也不代表
已证明所有 Sony 相机行为一致。当前 Companion 已有的 JPEG 手动下载与这个完整 Push 流程
仍有差别。0.1.15 的行为研究及扫码 / 拍摄页面修复没有改动下载；后续 0.1.16 已接入
自动分享导入，具体实现范围见本文最后一节。拍照、RAW 预览和缩略图实现未改。

## 1. NFC 与系统分发

`nfc.NfcUtil.enableDispatchUncatchable` 注册 `application/x-sony-pmm` 前台分发。
`nfc.NdefDescription.getValueFromNfcIntent` 解析该 MIME 的私有 TLV，取得完整 SSID 和密码。
格式、长度编码和版本证据见 [PAIRING.md](PAIRING.md)。

`splash.ScreenController.doContiningProcessForNfc` 用这些凭据构造 ConnectionInfo，调用
`WifiControlUtil.startSearchTouchedCamera`；进入首页时携带 `FromNfcTouched` 启动来源。
这不是把 NFC 中的某个网页 URL 打开后完成配对，也没有发现为 A7R II 凭空生成密码的额外握手。
Android 10+ 路径使用 `WifiConnectionLaterThanQ` 的 WifiNetworkSpecifier / requestNetwork。

用户两张实机标签截图的第一条记录是 Sony MIME，第二条是 AAR，目标包为官方包名。
没有顶层 URI 记录。Android 根据第一条 NDEF 记录形成 MIME / URI Intent，并检查整条消息
中的 AAR；AAR 优先包与 App Links 的域名验证是不同机制。声明支持某 URL、提高 MIME filter
priority，不能保证本 App 在后台 / 冷启动时接管这个 Sony AAR。前台分发可以覆盖 AAR，
Companion 已实现这一点。相机标签显示不可写；本 App 没有修改它或伪装官方包名。

依据：Android NFC basics 的 Android application records / first record 分发规则；
AOSP `NfcDispatcher` 的 `DispatchInfo`、`tryNdef` / AAR package 分支。

## 2. 连接后按相机能力分类

`common.device.DeviceDescription.getControlModel` 根据描述中的 available functions、
default function、相机 / 媒体服务能力分类；它区分 ScalarWebAPI 遥控、ContentDirectory
Pull 和 XPushList Push，不能仅凭 NFC 数据内容相同就判断运行模式相同。

`devicelist.WiFiActivity.onCreate` 为 `DlnaPushContentTransfer` 注册 `ContentsPush`。
`WiFiActivity$5.run` 在连接对应相机、处理器尚未运行时调用其 `run(camera)`。
因此官方不是把所有 NFC 连接无条件当 REMOTE，也不是把所有 ContentDirectory 连接都当
“一触分享并下载全部相册”。

## 3. 单张快速路径与共享列表路径

`devicelist.push.ContentsPush.run` 检查 DID 描述中的 `mCurrentContentUrl.mItemType`：

| 条件 | 处理器 | 内容来源 |
| --- | --- | --- |
| DID 已有可识别的 current content | `DidXmlCopyAction` | 构造单个 `DidXmlItem`，从 `CurrentContentUrl` 取得各尺寸资源 |
| current content 类型未知 | `CdsCopyAction` | 通过 ContentDirectory 获取共享集合 `PushRoot` |

单张是官方显式支持的一种优化分支，不能由 NFC Tools 的可见文本推定一定走该分支。
`DidXmlCopyAction.onInitialized` 显示进度、发送 X_TransferStart / X_GetPushRoot，
初始化 Copy，再复制只有一个 DidXmlItem 的列表。

`CdsCopyAction.onInitialized` 注册浏览器回调；浏览器可用后取得共享容器。
`cds.browse.GetPushRootContainer` 先 GetSortCapabilities，再调用其 action 类，
实际以 **ContentDirectory BrowseDirectChildren(ObjectID=PushRoot, StartingIndex=0,
RequestedCount=1)** 获取计数，建立一个代表共享集合的容器。
注意：这个名为 GetPushRootContainer 的 action 是 Browse 的子类，不能仅按类名误认成
XPushList 的 `X_GetPushRoot`。后者是另一个真实的 SOAP 动作。

`CdsCopyAction$2$1.run` 取得共享容器后显示复制进度、发送 X_TransferStart / X_GetPushRoot，
初始化 Copy，读取共享容器对象计数，复制集合中的对象。`Copy$1` 按内容数安排复制；
`CopyObjectsRunnable` 同时支持 item 和 container；容器与内容任务使用独立队列。
空共享列表 / 无法取得容器进入错误处理，而不是随意改成下载整个 PhotoRoot。

## 4. 下载前确实有导入尺寸确认

两条路径都继承 `AbstractCopyAction.initialize`。它重置本次结果，按能力可能先显示
HEIF / XAVC S 注意事项，随后创建 `DlnaConfirmSettingDialog`。

已核对 `res/layout/dlna_confirm_setting_dialog_layout.xml` 和资源：该弹窗显示当前导入尺寸，
说明可在设置中修改，提供 OK / Cancel，且不可在框外取消。OK 回调 `$6` 才调用
`onInitialized()` 继续复制；Cancel 回调 `$7` 断开连接并显示取消结果。
这是导入参数确认，不是让用户再次从手机图库逐张选共享文件。

`common.setting.EnumTransferImageSize.readImageSize` 读取已保存设置；没有设置时默认
**TwoMegaPixels（约 2 MP）**。它支持 Original / TwoMegaPixels / Vga。
`Copy$CopyContentRunnable` 的已确认规则：

| 当前设置 / 类型 | 官方选择 |
| --- | --- |
| Original，普通 JPEG | Original 资源 |
| Original，RAW / MPO | TwoMegaPixels 资源，不能据此宣称下载 ARW 原文件 |
| TwoMegaPixels | 有对应资源则使用，否则尝试 VGA |
| Vga | VGA 资源 |
| HEIF | 此 DLNA 复制路径标记跳过，记入失败 / 不支持提示 |
| 视频 | 按类型和机型能力分别复制原视频、代理或两者；不支持的高码率 / AVCHD 分支会跳过或失败 |

视频 / HEIF 分支是 APK 的静态证据，并不表示已在 ILCE-7RM2 上实测这些组合。
在 DID 单张路径，`DidXmlItem.getTransferImageUrl` 对 Original / TwoMegaPixels / Vga
分别选 JpegOriginal / JpegLarge / JpegSmall；这些再由 profile 映射到 UNKNOWN / JPEG_LRG / JPEG_SM。
Companion 当前 JPEG 路径优先最佳 JPEG 资源，与官方默认约 2 MP 并不等价。

## 5. 取资源、下载与保存

`Copy.copy` 从 ICdsItem 按尺寸取 URL 和文件名，请求缩略图后进入下载任务。
`Copy` 用复制中标记和队列串行处理内容，维护 total / copied / failed。
`Copy.runContentDownloader` 创建 `ContentDownloader`，此次复制路径传入 60 秒连接 / 读取超时。
`ContentDownloader` 用 P2P 网络的 NetworkUtil.openConnection 发 HTTP 请求，检查响应和长度，
流式写入，并报告字节进度及结果。这里没有经 NFC 传输 JPEG。

DID 路径按资源 URL 取文件名，去除 LRG_ / ORG_ / SM_ / TN_ 前缀，RAW / MPO 的传输
JPEG 文件名相应改为 .JPG。保存位置由 `SavingDestinationSettingUtil` 决定，支持普通路径和
Android URI / SAF 路径。
`ContentFile.getUniqueFilePathFromFileName` 选择不冲突的名字；该路径不是按文件内容 hash
去重后必然跳过已存照片。`retryCopyFileWithAnotherName` 针对媒体数据库命名冲突换名重试；
不能把它误说成 Wi-Fi 失败后自动恢复下载或断点续传。

成功条目加入官方本地内容索引，通知系统媒体扫描 / 更新 Android 媒体集合。
`AbstractCopyAction.scanFile` 收集保存文件的 URI，并对注册失败给出提示。
没有发现这条一触复制链在成功后调用“删除相机原片”的动作。
对于 DAD 输出的大型下载异常处理块，没有把反编译产生的不完整分支当成
“任何失败都必定删除临时文件”或“支持断点续传”的证据。

## 6. 相机侧通知与顺序

服务：`urn:schemas-sony-com:service:XPushList:1`。

| SOAP action | 参数 / 用途 | 调用证据 |
| --- | --- | --- |
| X_TransferStart | 无参数；进入传输状态 | 两个 CopyAction 的 onInitialized / run |
| X_GetPushRoot | 无参数；与开始复制一起通知相机 | 同上；独立于前述 Browse 计数 |
| X_TransferProgress | NumTotal / **NumTransferd** | AbstractCopyAction$8.copyObjectsProgressUpdated |
| X_TransferEnd | ErrCode | AbstractCopyAction.notifyTransferEnd |

`NumTransferd` 是 APK 使用的实际字段拼写。进度里的“已处理”计数包括成功和部分失败，
不要将它当作已成功导入数量。普通字节进度更新手机进度条；无字节的计数更新发送上述
相机侧 SOAP 进度。界面同时更新缩略图、类型图标、当前项和总项数。

开始 / GetPushRoot / 进度调用使用的 `$3` callback 对成功和失败都不做业务处理；它们
通过异步 SOAP 调度发出。不能把静态调用顺序描述为“必须等 Start 成功，再验证
GetPushRoot 响应，才允许发送第一字节”。结束 callback `$4` 则无论 SOAP 成功或失败
都会继续执行断开 runnable。

## 7. 完成、取消与错误

`Copy.copyNextContent` 按结果区分全部成功、部分失败、全部失败。
`AbstractCopyAction$8` 收尾、扫描已保存文件、显示对应提示，再通知传输结束：

| 结果 | X_TransferEnd ErrCode | APK 行为 |
| --- | --- | --- |
| Succeeded | 0 | 扫描保存结果，结束，断开 |
| PartiallyFailed | **0** | 显示部分未复制提示，保留成功项，结束，断开 |
| Cancelled | 2 | 显示取消结果，扫描已成功保存项，结束，断开 |
| CompletelyFailed / 存储故障 / 一般错误等已映射错误 | 1 | 显示相应故障，结束，断开 |

这是实际枚举映射；**不能按直觉把所有非全部成功都说成 ErrCode=1**。
特别地，CannotWrite（ordinal 8）在该桥接 switch 中走默认断言分支，不属于上述正常
ErrCode=1 映射；静态证据不足以保证这个官方异常分支成功发出结束通知。
`AbstractCopyAction.cancel` 设置 Copy 取消状态；进度条取消与导入确认框 Cancel 的位置不同，
后者发生在复制尚未开始时。存储满、共享中、只读、未挂载、SAF 无效等有单独错误。
一般条目下载错误可以计失败后继续下一项，存储类错误可停止整批。
初始化 / 无内容错误有自己的 Start -> End(1) 通知分支；Start 失败的该错误回调不会
执行后续通知，不能承诺异常网络中任何分支都必定成功收尾。

成功和失败收尾 runnable `$8$2` / `$8$1` 都调用
`WifiControlUtil.disconnectFromCamera()`；它清理 NdefDescription、切换 Wi-Fi 控制状态，
委托连接层断开并通知 UI。它不是下载完后自动转成 REMOTE 取景。
Activity 停止 / 连接断开也会取消所持有的 camera function，destroy 进度和确认弹窗；
不能把 Android 进程被杀后的所有系统恢复行为仅凭这条代码链一概保证。

## 8. 与本次新手机日志对照

文件：`AlphaCompanion-jpeg-20261106-194814-721.zip`，App 0.1.14-preview / code 15，
Xiaomi 23127PN0CC，SDK 35。日志没有 WARN / ERROR、丢弃记录或写入失败。

| 时间 | 实际事件 |
| --- | --- |
| 19:45:52 -> 19:46:22 | NFC 请求 -> Android Network 110 可用 |
| 19:46:26 -> 19:46:35 | 识别 REMOTE -> 现有遥控初始化完成，有实时取景 |
| 19:46:48 | 用户断开，资源释放 |
| 19:47:08 -> 19:47:30 | 第二次 NFC 请求 -> Network 112 可用 |
| 19:47:35 -> 19:47:36 | 识别 JPEG，X_TransferStart，Browse PushRoot 返回一项 |
| 19:47:51 -> 19:47:52 | 用户启动下载，DSC02200.JPG 保存成功，2,490,368 字节 |
| 19:48:00 | X_TransferEnd 成功，正常断开 |

这份日志还保留了 DigitalImagingDesc.xml：`X_CurrentContent_URL` 内有同一张照片的
`type=JPG`，以及 JPEG_TN / JPEG_SM / JPEG_LRG / UNKNOWN 四类资源；UNKNOWN 明示
2,490,368 字节、7952×5304。将这个实际描述输入官方 `CurrentContentUrl.setContentType`
会识别为 jpeg，满足 `ContentsPush.run` 的 DID 单张分支条件。因此，**按静态代码和该次
相机响应推断，官方在同一相机状态会走 DidXmlCopyAction**；这不是已运行官方 App 的抓包结论。
Companion 此次从 PushRoot 浏览同一张文件，是不同但已经实机成功的获取路径。

这里共享集合恰好一张，下载资源为 7952×5304 的 JPEG，不代表官方默认下载尺寸或所有共享
集合都只有一张。约 14 秒后才开始下载也与当前 Companion 需要用户手动开始相符。
这份日志验证的是小米上的 NFC / Wi-Fi / 服务选择 / 下载，不含主力机 NFC 未读到标签时
的射频或系统分发证据，因此不能从它诊断另一台手机的硬件或系统 NFC 故障。

## 9. 后续适配应复用的边界

完整一触分享应限于已证实的 Push 模式 / 相机共享集合。可优先使用当前 DID 内容；没有
可识别 DID 内容时再枚举 PushRoot。确认导入后，将共享条目交给已有
SmartphoneTransfer、队列、PhotoStore 和 SessionIdentity；不能将 PhotoRoot 回退当成
“用户要分享整张卡”，不能顺带自动恢复其他历史未完成下载，也不能固定只取第一项。
取消 / 断线 / 新会话必须使旧任务失效；完成后发结束通知并释放本次 Wi-Fi lease。
以上是研究得到的适配边界；0.1.15 的 UI 修复没有把它们标记成已实现或已实机验收。

参考：
- https://developer.android.com/develop/connectivity/nfc/nfc
- https://developer.android.com/develop/connectivity/nfc/advanced-nfc
- https://android.googlesource.com/platform/packages/apps/Nfc/+/0ebea21/src/com/android/nfc/NfcDispatcher.java
- https://helpguide.sony.net/ilc/1520/v1/en/contents/TP0001087160.html


## 10. 后续接入：0.1.16-preview

NFC 发起且相机提供 XPushList 时，现已自动执行 DID 单张 / PushRoot 选集导入。
`protocol.OneTouchSharing` 独立解析 DID、筛选 JPEG 并有界遍历选集；
`SmartphoneTransfer.initialize(oneTouch=true)` 禁止 PhotoRoot 回退。
`CompanionController` 的代次绑定协调任务交给原下载队列保存，保留未选择的历史待恢复任务，
后台服务与现有 MediaStore 清理 / 去重逻辑复用。UI 显示导入进度并可取消，完成后断开。

与官方的有意差别：不额外弹导入尺寸确认框，直接按 Companion 原有的“最大可用 JPEG”导入；
RAW/MPO 原文件资源排除，只导入相机提供的 JPEG。视频、HEIF、仅有缩略图、冲突或无效的
选集会明确失败，不能冒充全部导入。成功 / 部分失败 / 取消 / 全部失败分别显示不同结果，
结束码沿用已观察到的官方映射。GetPushRoot / Progress 是有界的可失败通知，End 最多发送一次。
连接中断或强制取消时只保证本地释放，不虚构相机已经收到结束通知。

自动测试以模拟 Wi-Fi 回调、本地 HTTP 服务和真实 Android 队列 / 前台服务 / MediaStore 验证
软件路径。实机仍需验证新增自动导入单张、相机多选集和取消；此前人工下载成功的日志
不能当作新功能已经实机通过。
