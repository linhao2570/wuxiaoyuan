import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const AiQinApp());
}

class AiQinApp extends StatelessWidget {
  const AiQinApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'aiqin',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.blue,
      ),
      debugShowCheckedModeBanner: false,
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const _channel = MethodChannel('aiqin/client_control');
  final _logs = <String>[];
  bool _monitoring = false;
  bool _autoStart = true;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_handleNativeCall);
    _init();
  }

  Future<void> _init() async {
    final prefs = await SharedPreferences.getInstance();
    _autoStart = prefs.getBool('auto_start') ?? true;
    _appendLog('应用已启动');
    if (_autoStart) {
      await _call('startMonitor');
    }
    if (mounted) setState(() {});
  }

  Future<dynamic> _handleNativeCall(MethodCall call) async {
    switch (call.method) {
      case 'log':
        _appendLog(call.arguments?.toString() ?? '');
        break;
      case 'status':
        final running = call.arguments == true;
        if (mounted) setState(() => _monitoring = running);
        break;
      default:
        break;
    }
  }

  void _appendLog(String msg) {
    if (!mounted) return;
    final now = DateTime.now();
    final time = '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';
    setState(() {
      _logs.insert(0, '$time $msg');
      if (_logs.length > 120) _logs.removeLast();
    });
  }

  Future<void> _call(String method) async {
    try {
      final result = await _channel.invokeMethod(method);
      _appendLog('执行 $method: ${result == true ? "成功" : result ?? "已执行"}');
      if (method == 'startMonitor' || method == 'stopMonitor') {
        setState(() => _monitoring = method == 'startMonitor');
      }
    } on PlatformException catch (e) {
      _appendLog('$method 失败: ${e.message ?? e.code}');
    } catch (e) {
      _appendLog('$method 异常: $e');
    }
  }

  Future<void> _toggleAutoStart(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('auto_start', value);
    setState(() => _autoStart = value);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('aiqin - 广东校园助手'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _StatusCard(monitoring: _monitoring),
            const SizedBox(height: 16),
            const Text('快捷操作', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _Btn(icon: Icons.play_arrow, label: '开启后台监测', onTap: () => _call('startMonitor')),
                _Btn(icon: Icons.stop, label: '停止后台监测', onTap: () => _call('stopMonitor')),
                _Btn(icon: Icons.open_in_new, label: '打开广东校园', onTap: () => _call('openClient')),
                _Btn(icon: Icons.accessibility_new, label: '开启无障碍权限', onTap: () => _call('openAccessibility')),
              ],
            ),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('设置', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 8),
                    SwitchListTile(
                      title: const Text('启动时自动开启监测'),
                      value: _autoStart,
                      onChanged: _toggleAutoStart,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Text('运行日志', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
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
                  ? const Text('暂无日志', style: TextStyle(color: Colors.grey, fontSize: 12))
                  : ListView.builder(
                      reverse: true,
                      itemCount: _logs.length,
                      itemBuilder: (_, i) => Text(
                        _logs[_logs.length - 1 - i],
                        style: const TextStyle(color: Colors.greenAccent, fontSize: 12, fontFamily: 'monospace'),
                      ),
                    ),
            ),
            const SizedBox(height: 12),
            const Text(
              '说明：本应用仅作为广东校园客户端的辅助工具，通过系统无障碍服务识别并点击页面中的登录按钮。仅限个人自用，请遵守校园网使用规定。',
              style: TextStyle(color: Colors.grey, fontSize: 12, height: 1.5),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  final bool monitoring;
  const _StatusCard({required this.monitoring});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              width: 12,
              height: 12,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: monitoring ? Colors.green : Colors.grey,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(monitoring ? '监测运行中' : '监测未启动',
                      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 4),
                  Text(
                    monitoring ? 'WiFi 未验证时自动唤起广东校园' : '点击下方按钮开启后台监测',
                    style: TextStyle(color: Colors.grey[600], fontSize: 12),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Btn extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  const _Btn({required this.icon, required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: onTap,
      icon: Icon(icon, size: 18),
      label: Text(label),
    );
  }
}
