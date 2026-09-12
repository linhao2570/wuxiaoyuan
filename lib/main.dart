import 'dart:async';
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
  bool _loggingIn = false;

  final _baseUrlCtrl = TextEditingController();
  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();

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
        if (_logs.length > 80) _logs.removeLast();
      });
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

  Future<void> _manualLogin() async {
    if (_loggingIn) return;
    if (!_config.isValid) {
      await _saveConfig();
      if (!_config.isValid) return;
    }
    setState(() => _loggingIn = true);
    _logs.insert(0, '${_timeNow()} 正在手动登录 (OCR 识别验证码)...');
    final result = await _service.manualLogin();
    if (mounted) {
      setState(() {
        _logs.insert(0, '${_timeNow()} 结果: ${result.success ? "成功" : "失败"} - ${result.message}');
        _loggingIn = false;
      });
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
                    Row(
                      children: [
                        Expanded(
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
                        const SizedBox(width: 12),
                        Expanded(
                          child: OutlinedButton(
                            onPressed: running || _loggingIn ? null : _manualLogin,
                            child: Text(_loggingIn ? '登录中...' : '手动登录'),
                          ),
                        ),
                      ],
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
              height: 240,
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
              '说明: 验证码自动 OCR 识别, 识别失败自动重试。每 90 分钟自动续期一次, 掉线自动重连。请将 App 加入电池优化白名单。',
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
    disposeOCR();
    super.dispose();
  }
}
