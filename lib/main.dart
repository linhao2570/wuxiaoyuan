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
    final autoStart = prefs.getBool('auto_start') ?? false;
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
    } catch (_) {}
  }

  void _appendLog(String msg) {
    if (!mounted) return;
    final now = DateTime.now();
    final time = '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';
    setState(() {
      _logs.insert(0, '$time $msg');
      if (_logs.length > 60) _logs.removeLast();
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
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  backgroundColor: _monitorRunning ? Colors.red : Colors.blue,
                  foregroundColor: Colors.white,
                ),
                icon: Icon(_monitorRunning ? Icons.stop : Icons.play_arrow, size: 22),
                label: Text(
                  _monitorRunning ? '停止后台监测' : '开启后台监测',
                  style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
                ),
              ),
            ),
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: _openAccessibility,
                icon: const Icon(Icons.accessibility_new),
                label: const Text('开启无障碍权限（可选）'),
              ),
            ),
            const SizedBox(height: 20),
            Card(
              color: Colors.blue[50],
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: const [
                        Icon(Icons.auto_mode, color: Colors.blue, size: 20),
                        SizedBox(width: 8),
                        Text('怎么工作的',
                            style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                                color: Colors.blue)),
                      ],
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      '亮屏时：只在刚开启时检查一次网络，之后不打扰你。\n'
                      '熄屏 30 秒后：自动重置一次广东校园，保持联网。\n'
                      '持续熄屏：每 40 分钟重置一次。\n'
                      '亮屏后：立刻停止重置，回到你之前用的应用。',
                      style:
                          TextStyle(color: Colors.black87, fontSize: 13, height: 1.6),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            Card(
              color: Colors.orange[50],
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: const [
                        Icon(Icons.info_outline, color: Colors.orange, size: 20),
                        SizedBox(width: 8),
                        Text('温馨提示',
                            style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                                color: Colors.orange)),
                      ],
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      '开启后，aiqin 会一直在后台运行，广东校园也会保持运行。\n'
                      '请在系统设置中给 aiqin 开启：自启动、后台活动、电池优化不限制、通知权限。',
                      style: TextStyle(
                          color: Colors.black87, fontSize: 13, height: 1.6),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Text('日志',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Container(
              height: 160,
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
                'WiFi 状态',
                wifiOk ? '已连接' : '未连接',
                wifiOk ? Colors.green : Colors.red),
            const SizedBox(height: 12),
            _statusRow(
                '后台监测',
                monitorRunning ? '运行中' : '未启动',
                monitorRunning ? Colors.green : Colors.grey),
            const SizedBox(height: 12),
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
        Text(label, style: const TextStyle(fontSize: 15)),
        Row(
          children: [
            Container(
              width: 10,
              height: 10,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: color,
              ),
            ),
            const SizedBox(width: 10),
            Text(value,
                style: TextStyle(
                    fontSize: 15, color: color, fontWeight: FontWeight.bold)),
          ],
        ),
      ],
    );
  }
}
