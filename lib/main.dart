import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

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

class _HomePageState extends State<HomePage>
    with WidgetsBindingObserver {
  static const _channel = MethodChannel('aiqin/client_control');

  bool _monitorRunning = false;
  bool _wifiConnected = false;
  bool _internetOk = false;
  bool _accessibilityOk = false;
  bool _usageAccessOk = false;
  String _networkDetail = '尚未检测';
  bool _loading = false;
  final _logs = <String>[];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _appendLog('应用已启动');
    _refreshStatus();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshStatus();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _refreshStatus() async {
    if (_loading) return;
    setState(() => _loading = true);
    try {
      final result =
          await _channel.invokeMapMethod<String, dynamic>('getStatus');
      if (!mounted) return;
      if (result != null) {
        setState(() {
          _wifiConnected =
              result['wifiConnected'] == true || result['wifiOk'] == true;
          _internetOk = result['internetOk'] == true;
          _monitorRunning = result['monitorRunning'] == true;
          _accessibilityOk = result['accessibilityRunning'] == true;
          _usageAccessOk = result['usageAccessOk'] == true;
          _networkDetail = result['networkDetail'] as String? ?? '检测中';
          final rawLogs = result['logs'] as List<dynamic>?;
          if (rawLogs != null) {
            _logs.clear();
            for (final l in rawLogs) {
              _logs.add(l.toString());
            }
          }
        });
      }
    } catch (e) {
      _appendLog('状态读取失败：');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _appendLog(String msg) {
    if (!mounted) return;
    final now = DateTime.now();
    final time = ':'
        ':'
        '';
    setState(() {
      _logs.insert(0, ' ');
      if (_logs.length > 60) _logs.removeLast();
    });
  }

  Future<void> _startMonitor() async {
    try {
      final result = await _channel.invokeMethod('startMonitor');
      final ok = result == true;
      _appendLog('开启后台监测：');
      if (ok) setState(() => _monitorRunning = true);
    } on PlatformException catch (e) {
      _appendLog('开启失败：');
    } catch (e) {
      _appendLog('开启异常：');
    }
    _refreshStatus();
  }

  Future<void> _stopMonitor() async {
    try {
      await _channel.invokeMethod('stopMonitor');
      _appendLog('已停止后台监测');
      setState(() => _monitorRunning = false);
    } on PlatformException catch (e) {
      _appendLog('停止失败：');
    } catch (e) {
      _appendLog('停止异常：');
    }
    _refreshStatus();
  }

  Future<void> _openAccessibility() async {
    try {
      await _channel.invokeMethod('openAccessibility');
      _appendLog('已打开无障碍设置');
    } catch (e) {
      _appendLog('打开失败：');
    }
  }

  Future<void> _openUsageAccess() async {
    try {
      await _channel.invokeMethod('openUsageAccess');
      _appendLog('已打开使用情况访问设置');
    } catch (e) {
      _appendLog('打开失败：');
    }
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
              wifiConnected: _wifiConnected,
              internetOk: _internetOk,
              networkDetail: _networkDetail,
              monitorRunning: _monitorRunning,
              accessibilityOk: _accessibilityOk,
              usageAccessOk: _usageAccessOk,
              onRefresh: _refreshStatus,
              loading: _loading,
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
                icon: Icon(_monitorRunning ? Icons.stop : Icons.play_arrow,
                    size: 22),
                label: Text(
                  _monitorRunning ? '停止后台监测' : '开启后台监测',
                  style: const TextStyle(
                      fontSize: 17, fontWeight: FontWeight.bold),
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
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: _openUsageAccess,
                icon: const Icon(Icons.history),
                label: const Text('开启使用情况访问（推荐）'),
              ),
            ),
            const SizedBox(height: 20),
            Card(
              color: Colors.blue[50],
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    Text('怎么工作的',
                        style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: Colors.blue)),
                    SizedBox(height: 10),
                    Text(
                      '亮屏时：只在开启时检查一次网络，不打扰你。\n'
                      '熄屏 30 秒后：自动点击断开网络 → 重启广东校园 → 回到原来的应用。\n'
                      '每 40 分钟重置一次（仅熄屏时）。\n'
                      '亮屏后：立即取消所有重置和返回动作。',
                      style: TextStyle(
                          color: Colors.black87, fontSize: 13, height: 1.6),
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
                  children: const [
                    Text('温馨提示',
                        style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: Colors.orange)),
                    SizedBox(height: 10),
                    Text(
                      '开启后，aiqin 会一直在后台运行，广东校园也会保持运行。\n'
                      '请给 aiqin 开启：自启动、后台活动、电池优化不限制、通知权限。\n'
                      '开启"使用情况访问"后，熄屏重置后能回到你之前使用的应用。',
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
  final bool wifiConnected;
  final bool internetOk;
  final String networkDetail;
  final bool monitorRunning;
  final bool accessibilityOk;
  final bool usageAccessOk;
  final VoidCallback onRefresh;
  final bool loading;

  const _StatusCard({
    required this.wifiConnected,
    required this.internetOk,
    required this.networkDetail,
    required this.monitorRunning,
    required this.accessibilityOk,
    required this.usageAccessOk,
    required this.onRefresh,
    required this.loading,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            _statusRow('WiFi 连接', wifiConnected ? '已连接' : '未连接',
                wifiConnected ? Colors.green : Colors.red),
            const SizedBox(height: 12),
            _statusRow('互联网', internetOk ? '可用' : '不可用',
                internetOk ? Colors.green : Colors.orange),
            const SizedBox(height: 12),
            _statusRow('后台监测', monitorRunning ? '运行中' : '未启动',
                monitorRunning ? Colors.green : Colors.grey),
            const SizedBox(height: 12),
            _statusRow('无障碍权限', accessibilityOk ? '已开启' : '未开启',
                accessibilityOk ? Colors.green : Colors.orange),
            const SizedBox(height: 12),
            _statusRow('使用情况访问', usageAccessOk ? '已授权' : '未授权',
                usageAccessOk ? Colors.green : Colors.orange),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('网络详情', style: TextStyle(fontSize: 15)),
                Expanded(
                  child: Text(
                    networkDetail,
                    textAlign: TextAlign.end,
                    style: const TextStyle(fontSize: 13, color: Colors.black54),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: 8),
                TextButton(
                  onPressed: loading ? null : onRefresh,
                  child: const Text('刷新'),
                ),
              ],
            ),
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
                    fontSize: 15,
                    color: color,
                    fontWeight: FontWeight.bold)),
          ],
        ),
      ],
    );
  }
}
