import os

# 更新 main.dart - 增加验证码显示、Portal 基础 URL 配置
new_content = '''import 'dart:async';
import 'dart:typed_data';
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

  final _baseUrlCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _captchaCtrl = TextEditingController();
  final _intervalCtrl = TextEditingController(text: '15');

  Uint8List? _captchaBytes;
  String _captchaCookie = '';

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    _config = await AppConfig.load();
    _baseUrlCtrl.text = _config.baseUrl;
    _userCtrl.text = _config.username;
    _passCtrl.text = _config.password;
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
    _refreshCaptcha();
  }

  String _timeNow() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';
  }

  Future<void> _refreshCaptcha() async {
    if (_baseUrlCtrl.text.trim().isEmpty) return;
    try {
      final data = await fetchCaptcha(_baseUrlCtrl.text.trim());
      if (mounted) {
        setState(() {
          _captchaBytes = data['bytes'];
          _captchaCookie = data['cookie'] ?? '';
        });
      }
    } catch (e) {
      // 忽略
    }
  }

  Future<void> _saveConfig() async {
    _config = AppConfig(
      baseUrl: _baseUrlCtrl.text.trim(),
      username: _userCtrl.text.trim(),
      password: _passCtrl.text.trim(),
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
              const SnackBar(content: Text('请先填写 Portal 地址和账号密码')),
            );
          }
          return;
        }
      }
      await _saveConfig();
      // 启动前需要先有一个验证码 cookie
      if (_captchaCookie.isEmpty) {
        await _refreshCaptcha();
      }
      _service.start(_config);
    }
    setState(() {});
  }

  Future<void> _testLogin() async {
    await _saveConfig();
    if (!_config.isValid || _captchaCtrl.text.trim().isEmpty || _captchaCookie.isEmpty) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('请先填写完整配置并输入验证码')),
        );
      }
      return;
    }
    setState(() => _logs.insert(0, '${_timeNow()} 测试登录中...'));
    
    final loginKey = generateLoginKey(
      _config.username,
      _config.password,
      _captchaCtrl.text.trim(),
    );
    
    final result = await doPortalLogin(
      baseUrl: _config.baseUrl,
      loginKey: loginKey,
      cookie: _captchaCookie,
    );
    
    if (!mounted) return;
    setState(() {
      _logs.insert(
          0, '${_timeNow()} 测试结果: ${result.success ? "成功" : "失败"} - ${result.message}');
    });
    // 刷新验证码 (不论成败都刷新)
    _refreshCaptcha();
    _captchaCtrl.clear();
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
              controller: _baseUrlCtrl,
              decoration: const InputDecoration(
                labelText: 'Portal 基础地址',
                hintText: 'http://enet.10000.gd.cn:10001',
                border: OutlineInputBorder(),
                isDense: true,
              ),
              enabled: !running,
              onChanged: (_) => _refreshCaptcha(),
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
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                GestureDetector(
                  onTap: _refreshCaptcha,
                  child: Container(
                    width: 120,
                    height: 40,
                    decoration: BoxDecoration(
                      border: Border.all(color: Colors.grey),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: _captchaBytes != null
                        ? Image.memory(_captchaBytes!, fit: BoxFit.fill)
                        : const Center(child: Text('验证码', style: TextStyle(color: Colors.grey))),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _captchaCtrl,
                    decoration: const InputDecoration(
                      labelText: '验证码 (点图片刷新)',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    enabled: !running,
                  ),
                ),
              ],
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
              '提示：首次需手动输入验证码登录，登录成功后 session 内可自动重登。',
              style: TextStyle(color: Colors.grey, fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    _baseUrlCtrl.dispose();
    _userCtrl.dispose();
    _passCtrl.dispose();
    _captchaCtrl.dispose();
    _intervalCtrl.dispose();
    super.dispose();
  }
}
'''

with open('lib/main.dart', 'w', encoding='utf-8', newline='\n') as f:
    f.write(new_content)
print('main.dart updated')
