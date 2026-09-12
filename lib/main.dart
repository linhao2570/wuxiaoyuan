import 'package:flutter/material.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
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

  final _connectivity = Connectivity();
  String _wifiName = '未连接';

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
        .map((e) => '${e.key}=${e.value}')
        .join('\n');
    _intervalCtrl.text = _config.pollInterval.toString();

    _service.logStream.listen((log) {
      if (!mounted) return;
      setState(() => _logs.insert(0, '${_timeNow()} $log'));
      if (_logs.length > 50) _logs.removeLast();
    });

    _service.statusStream.listen((_) {
      if (mounted) setState(() {});
    });

    _updateWifiName();
    _connectivity.onConnectivityChanged.listen((_) => _updateWifiName());

    setState(() => _loading = false);
  }

  String _timeNow() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';
  }

  Future<void> _updateWifiName() async {
    try {
      final result = await _connectivity.checkConnectivity();
      if (result.contains(ConnectivityResult.wifi)) {
        final name = await _connectivity.getWifiName();
        setState(() => _wifiName = name ?? '已连接 WiFi');
      } else {
        setState(() => _wifiName = '未连接 WiFi');
      }
    } catch (_) {
      setState(() => _wifiName = '未知');
    }
  }

  Map<String, String> _parseFormFields(String text) {
    final map = <String, String>{};
    for (final line in text.split('\n')) {
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
    if (_config.pollInterval < 5) _config.pollInterval = 5; // 下限5秒
    await _config.save();
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('配置已保存')),
      );
    }
  }

  Future<void> _toggleService() async {
    if (_service.status != ServiceStatus.stopped) {
      await _service.stop();
    } else {
      if (!_config.isValid) {
        await _saveConfig();
        if (!_config.isValid) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('请先填写登录地址、账号和密码')),
          );
          return;
        }
      }
      await _saveConfig();
      await _service.start(_config);
    }
    setState(() {});
  }

  Future<void> _testLogin() async {
    await _saveConfig();
    if (!_config.isValid) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('请先填写完整配置')),
      );
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
            // 状态卡片
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('服务状态',
                            style: TextStyle(fontSize: 16)),
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
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text('WiFi'),
                        Text(_wifiName),
                      ],
                    ),
                    const SizedBox(height: 16),
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        onPressed: _toggleService,
                        style: ElevatedButton.styleFrom(
                          backgroundColor:
                              running ? Colors.red : Colors.blue,
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

            // 配置区
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
                hintText: 'createAuthorFlag=0\npageView=pc',
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

            // 日志区
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
              '提示：仅个人自用。首次使用请先抓包获取登录接口地址和表单字段。',
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
