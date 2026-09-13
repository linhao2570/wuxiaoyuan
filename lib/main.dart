import 'dart:async';
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

  bool _monitorRunning = false;
  bool _wifiOk = false;
  bool _accessibilityOk = false;
  final _logs = <String>[];

  Timer? _statusTimer;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final prefs = await SharedPreferences.getInstance();
    final autoStart = prefs.getBool('auto_start') ?? true;
    _appendLog('应用已启动');
    await _refreshStatus();

    if (autoStart && !_monitorRunning) {
      await _startMonitor();
    }

    _statusTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      _refreshStatus();
    });

    if (mounted) setState(() {});
  }

  Future<void> _refreshStatus() async {
    try {
      final result = await _channel.invokeMapMethod<String, dynamic>('getStatus');
      if (result != null && mounted) {
        setState(() {
          _wifiOk = result['wifiOk'] == true;
          _monitorRunning = result['monitorRunning'] == true;
          _accessibilityOk = result['accessibilityRunning'] == true;
        });
      }
    } catch (_) {
      // Channel not ready yet, will retry
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
      if (_logs.length > 80) _logs.removeLast();
    });
  }

  Future<void> _startMonitor() async {
    try {
      final result = await _channel.invokeMethod('startMonitor');
      final ok = result == true;
      _appendLog('开启后台监测：${ok ? '成功' : '已执行'}');
      if (ok) setState(() => _monitorRunning = true);
    } on PlatformException catch (e) {
      _appendLog('开启失败：${e.message ?? e.code}');
    } catch (e) {
      _appendLog('开启异常：$e');
    }
    _refreshStatus();
  }

  Future<void> _stopMonitor() async {
    try {
      await _channel.invokeMethod('stopMonitor');
      _appendLog('已停止后台监测');
      setState(() => _monitorRunning = false);
    } on PlatformException catch (e) {
      _appendLog('停止失败：${e.message ?? e.code}');
    } catch (e) {
      _appendLog('停止异常：$e');
    }
    _refreshStatus();
  }

  Future<void> _openAccessibility() async {
    try {
      await _channel.invokeMethod('openAccessibility');
      _appendLog('已打开无障碍设置');
    } catch (e) {
      _appendLog('打开失败：$e');
    }
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
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
            _StatusCard(
              wifiOk: _wifiOk,
              monitorRunning: _monitorRunning,
              accessibilityOk: _accessibilityOk,
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: _monitorRunning ? _stopMonitor : _startMonitor,
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  backgroundColor: _monitorRunning ? Colors.red : Colors.blue,
                  foregroundColor: Colors.white,
                ),
                icon: Icon(_monitorRunning ? Icons.stop : Icons.play_arrow),
                label: Text(
                  _monitorRunning ? '停止后台监测' : '开启后台监测',
                  style: const TextStyle(fontSize: 16),
                ),
              ),
            ),
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: _openAccessibility,
                icon: const Icon(Icons.accessibility_new),
                label: const Text('开启无障碍权限'),
              ),
            ),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    Text('工作原理',
                        style: TextStyle(
                            fontSize: 16, fontWeight: FontWeight.bold)),
                    SizedBox(height: 8),
                    Text(
                      '启动后自动监测网络。掉线时自动拉起广东校园客户端，利用其启动时自动连接的特性保活。定时重启客户端防止掉线。无障碍服务辅助点击登录按钮。',
                      style: TextStyle(color: Colors.grey, fontSize: 12, height: 1.5),
                    ),
                    SizedBox(height: 8),
                    Text(
                      '前 1 分钟每 5 秒检测一次，之后每 5 分钟重置一次客户端。',
                      style: TextStyle(color: Colors.grey, fontSize: 12, height: 1.5),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Text('运行日志',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Container(
              height: 180,
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
                            color: Colors.greenAccent,
                            fontSize: 12,
                            fontFamily: 'monospace'),
                      ),
                    ),
            ),
            const SizedBox(height: 12),
            const Text(
              '提示：无法完全静默操作另一个 App，每次重连时广东校园会短暂出现在前台，连接成功后自动返回桌面。仅限个人自用。',
              style: TextStyle(color: Colors.grey, fontSize: 12, height: 1.5),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  final bool wifiOk;
  final bool monitorRunning;
  final bool accessibilityOk;

  const _StatusCard({
    required this.wifiOk,
    required this.monitorRunning,
    required this.accessibilityOk,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            _statusRow(
                'WiFi 网络',
                wifiOk ? '已连接' : '未连接',
                wifiOk ? Colors.green : Colors.red),
            const SizedBox(height: 10),
            _statusRow(
                '后台监测',
                monitorRunning ? '运行中' : '未启动',
                monitorRunning ? Colors.green : Colors.grey),
            const SizedBox(height: 10),
            _statusRow(
                '无障碍权限',
                accessibilityOk ? '已开启' : '未开启',
                accessibilityOk ? Colors.green : Colors.orange),
          ],
        ),
      ),
    );
  }

  Widget _statusRow(String label, String value, Color color) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: const TextStyle(fontSize: 14)),
        Row(
          children: [
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: color,
              ),
            ),
            const SizedBox(width: 8),
            Text(value,
                style: TextStyle(
                    fontSize: 14, color: color, fontWeight: FontWeight.bold)),
          ],
        ),
      ],
    );
  }
}
