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
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.blue,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('aiqin/client_control');

  bool _monitorRunning = false;
  bool _wifiConnected = false;
  bool _internetOk = false;
  bool _accessibilityOk = false;
  bool _usageAccessOk = false;
  bool _loading = false;
  String _networkDetail = '尚未检测';
  List<String> _logs = const [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshStatus();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshStatus();
    }
  }

  Future<void> _refreshStatus() async {
    if (_loading) return;
    setState(() => _loading = true);
    try {
      final result =
          await _channel.invokeMapMethod<String, dynamic>('getStatus');
      if (!mounted || result == null) return;
      setState(() {
        _wifiConnected = result['wifiConnected'] == true ||
            result['wifiOk'] == true;
        _internetOk = result['internetOk'] == true;
        _monitorRunning = result['monitorRunning'] == true;
        _accessibilityOk = result['accessibilityRunning'] == true;
        _usageAccessOk = result['usageAccessOk'] == true;
        _networkDetail = result['networkDetail'] as String? ?? '尚未检测';
        final rawLogs = result['logs'];
        _logs = rawLogs is List
            ? rawLogs.map((item) => item.toString()).toList()
            : const [];
      });
    } catch (error) {
      _appendLog('读取状态失败：$error');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _appendLog(String message) {
    if (!mounted) return;
    setState(() {
      _logs = <String>[message, ..._logs].take(80).toList();
    });
  }

  Future<void> _startMonitor() async {
    try {
      await _channel.invokeMethod('startMonitor');
      _appendLog('已开启后台监测');
      await _refreshStatus();
    } on PlatformException catch (error) {
      _appendLog('开启失败：${error.message ?? error.code}');
    }
  }

  Future<void> _stopMonitor() async {
    try {
      await _channel.invokeMethod('stopMonitor');
      _appendLog('已停止后台监测');
      await _refreshStatus();
    } on PlatformException catch (error) {
      _appendLog('停止失败：${error.message ?? error.code}');
    }
  }

  Future<void> _openAccessibility() async {
    try {
      await _channel.invokeMethod('openAccessibility');
      _appendLog('已打开无障碍设置，请启用 aiqin');
    } catch (error) {
      _appendLog('打开无障碍设置失败：$error');
    }
  }

  Future<void> _openUsageAccess() async {
    try {
      await _channel.invokeMethod('openUsageAccess');
      _appendLog('已打开使用情况访问设置');
    } catch (error) {
      _appendLog('打开失败：');
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('aiqin · 广东校园助手'),
        centerTitle: true,
        actions: [
          IconButton(
            tooltip: '刷新状态',
            onPressed: _loading ? null : _refreshStatus,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refreshStatus,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _StatusCard(
              wifiConnected: _wifiConnected,
              internetOk: _internetOk,
              networkDetail: _networkDetail,
              monitorRunning: _monitorRunning,
              accessibilityOk: _accessibilityOk,
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: _loading
                    ? null
                    : (_monitorRunning ? _stopMonitor : _startMonitor),
                style: ElevatedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  backgroundColor:
                      _monitorRunning ? Colors.red : Colors.blue,
                  foregroundColor: Colors.white,
                ),
                icon: Icon(
                  _monitorRunning ? Icons.stop : Icons.play_arrow,
                  size: 22,
                ),
                label: Text(
                  _monitorRunning ? '停止后台监测' : '开启后台监测',
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.bold,
                  ),
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
                label: const Text('开启使用情况访问（可选）'),
              ),
            ),
            const SizedBox(height: 16),
            const _InfoPanel(
              title: '运行规则',
              icon: Icons.auto_mode,
              color: Colors.blue,
              text: '开启后台监测后才会开始工作。\n'
                  '启动时检查一次网络；亮屏期间不持续轮询，也不主动点击广东校园。\n'
                  '熄屏超过 30 秒后，后台关闭并重新启动广东校园；持续熄屏每 40 分钟重复一次。\n'
                  '亮屏会立即取消熄屏任务。亮屏拉起广东校园时，需要无障碍权限才能立刻返回原来的应用。',
            ),
            const SizedBox(height: 12),
            const _InfoPanel(
              title: '使用提醒',
              icon: Icons.info_outline,
              color: Colors.orange,
              text: '启动本服务后，aiqin 会保持前台服务运行，广东校园也会被后台启动。\n'
                  '请在系统设置中允许 aiqin 自启动、后台运行，并将电池策略设为“不限制”。\n'
                  '本应用不读取或上传账号、密码、验证码，也不修改广东校园的登录数据。',
              background: Color(0xfffff5e6),
            ),
            const SizedBox(height: 16),
            const Text(
              '运行日志',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Container(
              height: 190,
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              color: const Color(0xff202124),
              child: _logs.isEmpty
                  ? const Text(
                      '暂无日志，开启后台监测后会显示状态。',
                      style: TextStyle(color: Colors.grey, fontSize: 12),
                    )
                  : ListView.builder(
                      itemCount: _logs.length,
                      itemBuilder: (_, index) => Padding(
                        padding: const EdgeInsets.only(bottom: 5),
                        child: Text(
                          _logs[index],
                          style: const TextStyle(
                            color: Colors.greenAccent,
                            fontSize: 12,
                            fontFamily: 'monospace',
                          ),
                        ),
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

  const _StatusCard({
    required this.wifiConnected,
    required this.internetOk,
    required this.networkDetail,
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
              'WiFi',
              wifiConnected ? '已连接' : '未连接',
              wifiConnected ? Colors.green : Colors.red,
            ),
            const SizedBox(height: 10),
            _statusRow(
              '互联网',
              internetOk ? '可用' : '不可用',
              internetOk ? Colors.green : Colors.orange,
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: Padding(
                padding: const EdgeInsets.only(top: 5),
                child: Text(
                  networkDetail,
                  style: const TextStyle(color: Colors.black54, fontSize: 12),
                ),
              ),
            ),
            const Divider(height: 22),
            _statusRow(
              '后台监测',
              monitorRunning ? '运行中' : '未开启',
              monitorRunning ? Colors.green : Colors.grey,
            ),
            const SizedBox(height: 10),
            _statusRow(
              '无障碍',
              accessibilityOk ? '已开启' : '未开启',
              accessibilityOk ? Colors.green : Colors.orange,
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
                color: color,
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 8),
            Text(
              value,
              style: TextStyle(
                color: color,
                fontSize: 15,
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _InfoPanel extends StatelessWidget {
  final String title;
  final IconData icon;
  final Color color;
  final String text;
  final Color? background;

  const _InfoPanel({
    required this.title,
    required this.icon,
    required this.color,
    required this.text,
    this.background,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      color: background ?? const Color(0xffeef6ff),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color, size: 20),
                const SizedBox(width: 8),
                Text(
                  title,
                  style: TextStyle(
                    color: color,
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              text,
              style: const TextStyle(
                color: Colors.black87,
                fontSize: 13,
                height: 1.6,
              ),
            ),
          ],
        ),
      ),
    );
  }
}            const SizedBox(height: 12),
            _statusRow(
                '使用情况访问',
                _usageAccessOk ? '已授权' : '未授权',
                _usageAccessOk ? Colors.green : Colors.orange),

