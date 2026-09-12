# 校园网自动登录 App（Flutter 安卓版）

给自己用的、超轻量的校园网 ESurfing Portal 自动登录 App。
后台前台服务保活，连接校园网 WiFi 后自动检测，断网自动重登。

## 功能

- 填写账号密码 + 登录接口 URL 即可使用
- 前台服务后台运行，状态栏常驻通知（防止被系统杀掉）
- 定时检测网络，断开自动重新登录
- 配置本地保存，开箱即用
- 测试登录按钮，方便验证配置是否正确

## 仅 4 个依赖

- `http` — 网络请求
- `shared_preferences` — 本地存储配置
- `connectivity_plus` — WiFi 状态监听
- `flutter_foreground_task` — 前台服务保活

## 打包步骤

### 环境准备

1. 安装 Flutter SDK: https://docs.flutter.dev/get-started/install
2. 安装 Android Studio（含 Android SDK）
3. 验证环境:
   ```bash
   flutter doctor
   ```

### 打包 APK

```bash
cd wyu_esurfing_app

# 安装依赖
flutter pub get

# 构建 release 版 APK（已开启代码混淆和资源压缩，体积最小）
flutter build apk --release
```

打包完成后，APK 在：
```
build/app/outputs/flutter-apk/app-release.apk
```

传到手机安装即可。一般 release 包体积约 5~8 MB。

## 使用方法

1. 打开 App，填写配置：
   - **登录接口 URL**：抓包获取的登录 POST 接口地址
   - **账号**：校园网账号
   - **密码**：校园网密码
   - **额外表单字段**：抓包得到的除账号密码外的所有字段，每行一个 `key=value`
   - **轮询间隔**：默认 15 秒，最小 5 秒
2. 点「测试登录」验证配置是否正确
3. 点「启动服务」开始后台运行
4. 授予通知权限（Android 13+）和忽略电池优化（可选，更稳定）

## 抓包教程（电脑端）

> 手机上也可以用 HttpCanary / Charles 抓包，但电脑上用 Chrome 更方便。

1. 电脑连接校园网 WiFi（未登录状态）
2. 打开 Chrome，按 F12 → Network → 勾选 Preserve log → 过滤 Fetch/XHR
3. 手动输入账号密码点登录
4. 在请求列表里找到 POST 请求
5. 复制 Request URL → 填入 App 的「登录接口 URL」
6. 看 Payload / Form Data，把除了账号密码外的所有字段填到「额外表单字段」

## 注意事项

- **仅限个人自用**，不要分享给他人
- 轮询间隔不要低于 5 秒，避免给服务器造成压力
- App 需要保持后台运行，建议在系统设置里把此 App 的电池优化设为「不限制」
- 密码明文保存在本地 SharedPreferences 中（自己用没问题），不要把手机借给陌生人
- 如果登录失败，打开日志区域看具体错误信息

## 项目结构

```
lib/
├── main.dart                # UI 主页面
├── portal_login.dart        # 核心登录逻辑（HTTP POST）
├── auto_login_service.dart  # 前台服务 + 轮询检测
└── storage.dart             # 本地配置存储
```

核心登录逻辑 `doPortalLogin()` 与 Python 版 `do_portal_login()` 函数参数结构完全一致，便于对照调试。
