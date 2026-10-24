# 工作进度

状态：**JPEG 连接闪退修复已完成**。版本 `0.1.8-preview` / versionCode 9。
代码提交：`37e8dc718d56ae2a4107905e8fdecab322e73a7a`。

## 本次修复

设备日志显示发现相机和 SOAP Browse 已成功；在解析返回目录时，JPEG 资源辅助类中的正则触发 Android `PatternSyntaxException`，导致类初始化失败并退出进程。

已移除该正则，按 protocolInfo 的冒号和分号字段读取 DLNA 资源类型。保留原尺寸 JPEG、大图、小图、缩略图的现有选择规则。

新增两项 JVM 边界测试，以及两项 Android 运行时回归测试：资源辅助类初始化 / 分类，JPEG 服务初始化 / PushRoot 到 PhotoRoot 回退 / 文件夹及照片读取。原有四项界面检查继续执行。

## 验证与 APK

- [Android Debug 40065428726](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/40065428726)：82 项单元测试通过，0 失败 / 错误 / 跳过；lint 0 错误、33 警告；固定签名 APK 及证书校验通过。
- [下载 APK](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/40065428726/artifacts/12548858625)。可覆盖安装 0.1.7 固定签名版本。
- [Android 运行时检查 40065428733](https://github.com/ayatinene/Sony-Alpha-Companion/actions/runs/40065428733)：6 项测试全部通过，包括新增的两项协议路径检查和原有四项界面检查。

用户下一次验证只需先检查：JPEG 连接、进入日期目录、打开一张照片预览。

## 已有功能

固定签名、多相机身份与持久队列、横竖屏遥控、相机照片大图预览、本地图库文件删除与记录管理均保留。RAW/JPEG 沿用同来源 / 目录 / 主名的轻量关联，RAW 协议保持原有实验模块。

## 协作

使用 ayatinene GitHub 连接器直接提交 main，push 已授权；不创建分支 / PR。此前备用续作任务已停用。
README 保持产品说明。若需要用户补充材料，先保存代码与进度后集中询问；不索取 RawShare APK。
