# 1. 更新 pubspec.yaml - 去掉 wifi_info_flutter
import os

pubspec = '''name: wyu_esurfing
description: 五邑大学天翼校园网自动登录
publish_to: 'none'
version: 1.0.0+1

environment:
  sdk: '>=3.0.0 <4.0.0'

dependencies:
  flutter:
    sdk: flutter
  http: ^1.2.0
  shared_preferences: ^2.2.0

dev_dependencies:
  flutter_lints: ^3.0.0

flutter:
  uses-material-design: true
'''

with open('pubspec.yaml', 'w', encoding='utf-8', newline='\n') as f:
    f.write(pubspec)
print('pubspec.yaml updated')

# 2. 重写 main.dart - 去掉 wifi_info_flutter 相关代码
main_dart = '''import 'dart:async';
import 'package:flutter/material.dart';
import 'auto_login_service.dart';
import 'portal_login.dart';
import 'storage.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '校园网自动登录',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        useMaterial3: true,
      ),
      home: const HomePage(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _service = AutoLoginService();
  AppConfig _config = AppConfig();
  final _logs = <String>[];
  bool _loading = true;

  final _urlCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _formFieldsCtrl = TextEditingController();
  final _intervalCtrl = TextEditingController(text: '15');

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    _config = await AppConfig.load();
    _urlCtrl.text = _config.loginPostUrl;
    _userCtrl.text = _config.username;
    _passCtrl.text = _config.password;
    _formFieldsCtrl.text = _config.formFields.entries
        .map((e) => '\\${e.key}=\\${e.value}')
        .join('\\n');
    _intervalCtrl.text = _config.pollInterval.toString();

    _service.logStream.listen((log) {
      if (!mounted) return;
      setState(() => _logs.insert(0, '${_timeNow()} $log'));
      if (_logs.length > 50) _logs.removeLast();
    });

    _service.statusStream.listen((_) {
      if (mounted) setState(() {});
    });

    setState(() => _loading = false);
  }

  String _timeNow() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';
  }

  Map<String, String> _parseFormFields(String text) {
    final map = <String, String>{};
    for (final line in text.split('\\n')) {
      final trimmed = line.trim();
      if (trimmed.isEmpty || !trimmed.contains('=')) continue;
      final idx = trimmed.indexOf('=');
      final key = trimmed.substring(0, idx).trim();
      final value = trimmed.substring(idx + 1).trim();
      if (key.isNotEmpty) map[key] = value;
    }
    return map;
  }

  Future<void> _saveConfig() async {
    _config = AppConfig(
      loginPostUrl: _urlCtrl.text.trim(),
      username: _userCtrl.text.trim(),
      password: _passCtrl.text.trim(),
      formFields: _parseFormFields(_formFieldsCtrl.text),
      pollInterval: int.tryParse(_intervalCtrl.text.trim()) ?? 15,
    );
    if (_config.pollInterval < 5) _config.pollInterval = 5;
    await _config.save();
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('配置已保存')),
      );
    }
  }

  void _toggleService() async {
    if (_service.status != ServiceStatus.stopped) {
      _service.stop();
    } else {
      if (!_config.isValid) {
        await _saveConfig();
        if (!_config.isValid) {
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('请先填写登录地址、账号和密码')),
            );
          }
          return;
        }
      }
      await _saveConfig();
      _service.start(_config);
    }
    setState(() {});
  }

  Future<void> _testLogin() async {
    await _saveConfig();
    if (!_config.isValid) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('请先填写完整配置')),
        );
      }
      return;
    }
    setState(() => _logs.insert(0, '${_timeNow()} 测试登录中...'));
    final result = await doPortalLogin(
      loginPostUrl: _config.loginPostUrl,
      username: _config.username,
      password: _config.password,
      formFields: _config.formFields,
    );
    if (!mounted) return;
    setState(() {
      _logs.insert(
          0, '${_timeNow()} 测试结果: ${result.success ? "成功" : "失败"} - ${result.message}');
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    final running = _service.status != ServiceStatus.stopped;
    final statusText = switch (_service.status) {
      ServiceStatus.stopped => '已停止',
      ServiceStatus.running => '运行中',
      ServiceStatus.checking => '检测中...',
      ServiceStatus.loggingIn => '登录中...',
    };

    return Scaffold(
      appBar: AppBar(
        title: const Text('校园网自动登录'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('服务状态', style: TextStyle(fontSize: 16)),
                        Text(
                          statusText,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: running ? Colors.green : Colors.grey,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        onPressed: _toggleService,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: running ? Colors.red : Colors.blue,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                        child: Text(running ? '停止服务' : '启动服务',
                            style: const TextStyle(fontSize: 16)),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Text('配置',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            TextField(
              controller: _urlCtrl,
              decoration: const InputDecoration(
                labelText: '登录接口 URL',
                hintText: 'http://xxx/api/portal/login',
                border: OutlineInputBorder(),
                isDense: true,
              ),
              enabled: !running,
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _userCtrl,
                    decoration: const InputDecoration(
                      labelText: '账号',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    enabled: !running,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _passCtrl,
                    obscureText: true,
                    decoration: const InputDecoration(
                      labelText: '密码',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    enabled: !running,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _formFieldsCtrl,
              maxLines: 4,
              decoration: const InputDecoration(
                labelText: '额外表单字段（每行一个，格式 key=value）',
                hintText: 'createAuthorFlag=0\\npageView=pc',
                border: OutlineInputBorder(),
                isDense: true,
              ),
              enabled: !running,
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                SizedBox(
                  width: 100,
                  child: TextField(
                    controller: _intervalCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: '轮询间隔(秒)',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    enabled: !running,
                  ),
                ),
                const Spacer(),
                TextButton(
                  onPressed: running ? null : _testLogin,
                  child: const Text('测试登录'),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: running ? null : _saveConfig,
                  child: const Text('保存配置'),
                ),
              ],
            ),
            const SizedBox(height: 16),
            const Text('日志',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Container(
              height: 200,
              width: double.infinity,
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Colors.grey[900],
                borderRadius: BorderRadius.circular(4),
              ),
              child: _logs.isEmpty
                  ? const Text('暂无日志',
                      style: TextStyle(color: Colors.grey, fontSize: 12))
                  : ListView.builder(
                      reverse: true,
                      itemCount: _logs.length,
                      itemBuilder: (_, i) => Text(
                        _logs[_logs.length - 1 - i],
                        style: const TextStyle(
                            color: Colors.greenAccent, fontSize: 12,
                            fontFamily: 'monospace'),
                      ),
                    ),
            ),
            const SizedBox(height: 16),
            const Text(
              '提示：仅个人自用。请将 App 加入电池优化白名单并锁定后台。',
              style: TextStyle(color: Colors.grey, fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    _urlCtrl.dispose();
    _userCtrl.dispose();
    _passCtrl.dispose();
    _formFieldsCtrl.dispose();
    _intervalCtrl.dispose();
    super.dispose();
  }
}
'''

with open('lib/main.dart', 'w', encoding='utf-8', newline='\n') as f:
    f.write(main_dart)
print('main.dart updated')

# 3. 更新 AndroidManifest.xml - 去掉不必要的权限
manifest = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

    <application
        android:label="校园网自动登录"
        android:name="${applicationName}"
        android:icon="@mipmap/ic_launcher">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:theme="@style/LaunchTheme"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"
            android:hardwareAccelerated="true"
            android:windowSoftInputMode="adjustResize">
            <meta-data
              android:name="io.flutter.embedding.android.NormalTheme"
              android:resource="@style/NormalTheme"
              />
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <meta-data
            android:name="flutterEmbedding"
            android:value="2" />
    </application>
</manifest>
'''
# 注意：flutter create 会生成完整的 AndroidManifest，这里我们保留旧的（反正会被覆盖）

print('done')
