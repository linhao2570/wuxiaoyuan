# aiqin · 广东校园后台助手

这是一个仅供个人使用的轻量 Android 工具，用于配合官方「广东校园」客户端。
它不实现网页 Portal 登录，不读取账号密码，不抓取验证码，也不修改官方客户端的登录数据。

## 当前版本的目标

核心原则是少打扰：

1. 用户主动点击“开启后台监测”后，aiqin 才开始工作。
2. 服务启动时只检查一次网络。
3. 亮屏期间不注册网络监听，不持续轮询，也不定时打开广东校园。
4. 启动检查发现互联网不可用时：
   - 拉起官方广东校园；
   - 不点击广东校园界面；
   - 如果已开启 aiqin 无障碍服务，广东校园窗口出现后立即发送一次返回操作，尽快回到原来使用的页面。
5. 熄屏后记录日志，并等待 30 秒。
6. 熄屏持续超过 30 秒，后台请求关闭广东校园，再等待约 0.8 秒重新启动。
7. 持续熄屏期间每 40 分钟重复一次重置。
8. 用户亮屏时，立即取消尚未执行的熄屏任务。

> Android 不允许普通应用无条件强制结束另一个应用。这里使用系统允许的
> `killBackgroundProcesses` 请求关闭广东校园；如果官方客户端运行着前台服务，
> 系统可能不会完全杀掉它，这是 Android 权限边界，不是脚本错误。

## 安装后使用

1. 安装并打开 `aiqin`。
2. 第一次使用时点击“开启无障碍权限”，在系统设置中启用 `aiqin`。
3. 返回 aiqin，点击“开启后台监测”。
4. 在手机系统设置中给 aiqin 允许：
   - 自启动；
   - 后台活动；
   - 电池使用“不限制”；
   - 通知权限。
5. 官方「广东校园」客户端仍需先按学校正常流程登录。aiqin 只负责启动和熄屏重置，
   不代替账号密码输入。

启动服务后，aiqin 会保持一个低占用前台服务通知；广东校园也可能被后台启动。
这是 Android 为了允许应用持续运行所要求的可见通知。

## 网络状态判断

状态页面会分别显示：

- `WiFi`：手机是否连接到 WiFi；
- `互联网`：当前网络是否能访问外网；
- 详细文字：例如“WiFi 已连接，互联网可用”或“WiFi 已连接，但互联网不可用”。

判断流程是：

1. 使用 Android `ConnectivityManager` 读取当前 active network；
2. 确认传输类型为 WiFi；
3. 读取 `NET_CAPABILITY_INTERNET`；
4. 必要时访问极小的 204 探测地址确认外网。

不会把 `NET_CAPABILITY_VALIDATED` 作为唯一条件，因为部分校园网和手机 ROM
会把实际可用的网络错误报告为“未验证”。

## 权限说明

AndroidManifest 中使用的权限只服务于本地功能：

- 网络状态权限：读取当前 WiFi 和网络能力；
- 前台服务权限：让后台监测在系统允许的方式下持续运行；
- 无障碍服务绑定权限：在 aiqin 自己请求返回时，把广东校园窗口返回到前一个页面；
- `KILL_BACKGROUND_PROCESSES`：请求系统关闭官方客户端后台进程。

无障碍服务只接收官方广东校园包名的窗口事件，不扫描其它应用，也不读取输入框。

## 构建 APK

本项目默认通过 GitHub Actions 构建，不要求本地安装 Flutter SDK 或 Android Studio。

1. 修改代码后提交并推送到 `main` 分支。
2. 打开 GitHub 仓库的 `Actions`。
3. 等待 `Build APK` 工作流完成。
4. 打开本次运行记录，在 `Artifacts` 下载 `app-debug.apk`。
5. 解压下载文件后，把 APK 发送到手机安装。

也可以在 Actions 页面手动点击 `Run workflow`。

## 项目结构

```text
lib/main.dart
  Flutter 页面、状态展示和按钮。

android_custom/app/src/main/AndroidManifest.xml
  前台服务、无障碍服务、网络权限和官方客户端包名声明。

android_custom/app/src/main/java/com/wyu/esurfing/MainActivity.java
  Flutter MethodChannel，以及一次性状态查询。

android_custom/app/src/main/java/com/wyu/esurfing/MonitorService.java
  网络启动检查、亮/熄屏广播、30 秒和 40 分钟调度、广东校园启动/重置。

android_custom/app/src/main/java/com/wyu/esurfing/ClientAccessibilityService.java
  广东校园窗口出现后的立即返回操作。

android_custom/app/src/main/res/xml/accessibility_service_config.xml
  无障碍服务配置。
```

GitHub Actions 会先重新生成标准 Flutter Android 工程，再把 `android_custom` 中的
原生文件注入进去。因此原生 Java 修改必须放在 `android_custom`，不要只改生成后的
`android` 临时目录。

## 测试建议

建议先把代码中的 30 秒临时改为 10 秒测试，确认日志出现：

```text
检测到熄屏，30 秒后重置广东校园
熄屏已超过 30 秒，开始重置广东校园
已请求关闭广东校园后台进程
已启动广东校园
```

测试完成后恢复为 30 秒。40 分钟逻辑可以在测试时临时改短，确认后再改回。

亮屏返回测试需要：

1. aiqin 无障碍服务已启用；
2. 广东校园已安装；
3. 在其它应用页面开启后台监测；
4. 让启动检查判定网络不可用；
5. 观察广东校园是否短暂出现后回到原页面。

不同手机厂商对后台启动、后台进程和无障碍服务的限制不同。若日志显示已启动，
但广东校园没有出现或没有返回，请先把 aiqin 和广东校园的电池策略改为“不限制”。

## 合规与风险提示

- 仅限本人账号和本人设备使用。
- 不得代他人登录、共享账号、批量运行、多账号代理。
- 不得使用本项目绕过学校认证策略、验证码、设备限制或访问控制。
- 请遵守五邑大学及校园网运营方的网络使用协议。
- 项目不会上传账号密码，不包含网页抓包、Cookie 窃取、破解或 root/ADB 强制停止代码。
- 官方广东校园客户端的包名如果发生变化，启动和无障碍返回功能会失效，需要由用户
  自行确认官方包名后再进行合法的兼容更新。
