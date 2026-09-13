import 'dart:async';
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
      title: 'aiqin',
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
  bool _loggingIn = false;

  final _baseUrlCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _captchaCtrl = TextEditingController();

  Uint8List? _captchaBytes;
  String _captchaCookie = '';
  String _ocrResult = '';

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

    _service.logStream.listen((log) {
      if (!mounted) return;
      setState(() {
        _logs.insert(0, '${_timeNow()} $log');
        if (_logs.length > 100) _logs.removeLast();
      });
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
      final bytes = data['bytes'] as Uint8List?;
      final cookie = data['cookie'] ?? '';
      
      if (mounted && bytes != null) {
        final ocrText = await recognizeCaptcha(bytes);
        setState(() {
          _captchaBytes = bytes;
          _captchaCookie = cookie;
          _ocrResult = ocrText;
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
      pollInterval: 15,
    );
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
              const SnackBar(content: Text('请先填写完整配置')),
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

  Future<void> _manualLoginWithCaptcha() async {
    if (_loggingIn) return;
    if (_captchaCookie.isEmpty || _captchaCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('请先刷新验证码并输入')),
      );
      return;
    }
    await _saveConfig();
    if (!_config.isValid) return;

    setState(() => _loggingIn = true);
    _logs.insert(0, '${_timeNow()} 手动验证码登录中...');

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

    if (mounted) {
      setState(() {
        _logs.insert(0, '${_timeNow()} 结果: ${result.success ? "成功" : "失败"} - ${result.message}');
        _loggingIn = false;
      });
      if (result.success) {
        _service.lastLoginTime = DateTime.now();
      }
      _refreshCaptcha();
    }
  }

  Future<void> _autoLoginTest() async {
    if (_loggingIn) return;
    await _saveConfig();
    if (!_config.isValid) return;

    setState(() => _loggingIn = true);
    _logs.insert(0, '${_timeNow()} OCR 自动登录中 (最多8次重试)...');

    final result = await autoLogin(
      baseUrl: _config.baseUrl,
      username: _config.username,
      password: _config.password,
    );

    if (mounted) {
      setState(() {
        _logs.insert(0, '${_timeNow()} 结果: ${result.success ? "成功" : "失败"} - ${result.message}');
        _loggingIn = false;
      });
      if (result.success) {
        _service.lastLoginTime = DateTime.now();
      }
      _refreshCaptcha();
    }
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
        title: const Text('aiqin - 校园网自动登录'),
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
                    const SizedBox(height: 8),
                    const Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('续期间隔', style: TextStyle(color: Colors.grey)),
                        Text('每 90 分钟', style: TextStyle(color: Colors.grey)),
                      ],
                    ),
                    const SizedBox(height: 8),
                    const Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('检测间隔', style: TextStyle(color: Colors.grey)),
                        Text('每 3 分钟', style: TextStyle(color: Colors.grey)),
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
                        child: Text(running ? '停止服务' : '启动服务'),
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
            const SizedBox(height: 16),
            const Text('验证码 & 登录测试',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                GestureDetector(
                  onTap: _refreshCaptcha,
                  child: Container(
                    width: 140,
                    height: 50,
                    decoration: BoxDecoration(
                      border: Border.all(color: Colors.grey),
                      borderRadius: BorderRadius.circular(4),
                      color: Colors.white,
                    ),
                    child: _captchaBytes != null
                        ? Image.memory(_captchaBytes!, fit: BoxFit.fill)
                        : const Center(child: Text('点我刷新', style: TextStyle(color: Colors.grey, fontSize: 12))),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('OCR识别: ${_ocrResult.isEmpty ? "无" : _ocrResult}',
                          style: TextStyle(color: _ocrResult.length == 4 ? Colors.green : Colors.orange, fontSize: 13, fontWeight: FontWeight.bold)),
                      const SizedBox(height: 4),
                      TextField(
                        controller: _captchaCtrl,
                        decoration: const InputDecoration(
                          labelText: '手动输入验证码',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                        enabled: !running,
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: running ? null : _refreshCaptcha,
                  child: const Text('刷新'),
                ),
                TextButton(
                  onPressed: (running || _loggingIn) ? null : _autoLoginTest,
                  child: const Text('自动登录测试(OCR)'),
                ),
                TextButton(
                  onPressed: (running || _loggingIn) ? null : _manualLoginWithCaptcha,
                  child: const Text('手动登录测试'),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: running ? null : _saveConfig,
                child: const Text('保存配置'),
              ),
            ),
            const SizedBox(height: 16),
            const Text('日志',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Container(
              height: 220,
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
                            color: Colors.greenAccent, fontSize: 11,
                            fontFamily: 'monospace'),
                      ),
                    ),
            ),
            const SizedBox(height: 12),
            const Text(
              '使用步骤: 1.填配置 2.点手动登录测试(验证RSA加密正确) 3.成功后再启动自动服务',
              style: TextStyle(color: Colors.grey, fontSize: 11),
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
    disposeOCR();
    super.dispose();
  }
}
