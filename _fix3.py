# 完全重写 auto_login_service.dart - 最简实现，只用 Timer，不用前台服务框架
# 先用一个简单的全局服务类
content = '''import 'dart:async';
import 'portal_login.dart';
import 'storage.dart';

/// 服务状态
enum ServiceStatus { stopped, running, checking, loggingIn }

/// 自动登录服务（Timer 轮询 + 状态管理）
/// 说明：为了保持轻量，这里只用 Timer 做轮询。
/// 安卓后台保活需要用户手动将 App 加入电池优化白名单并锁定后台。
class AutoLoginService {
  static final AutoLoginService _instance = AutoLoginService._internal();
  factory AutoLoginService() => _instance;
  AutoLoginService._internal();

  ServiceStatus _status = ServiceStatus.stopped;
  ServiceStatus get status => _status;

  final _statusStream = StreamController<ServiceStatus>.broadcast();
  Stream<ServiceStatus> get statusStream => _statusStream.stream;

  final _logStream = StreamController<String>.broadcast();
  Stream<String> get logStream => _logStream.stream;

  Timer? _timer;
  AppConfig? _config;
  bool _online = false;

  void _log(String msg) {
    _logStream.add(msg);
  }

  void _setStatus(ServiceStatus s) {
    _status = s;
    _statusStream.add(s);
  }

  /// 启动服务
  void start(AppConfig config) {
    if (_status != ServiceStatus.stopped) return;
    _config = config;
    _setStatus(ServiceStatus.running);
    _log('服务已启动');
    _startPolling();
  }

  /// 停止服务
  void stop() {
    _timer?.cancel();
    _timer = null;
    _setStatus(ServiceStatus.stopped);
    _log('服务已停止');
  }

  void _startPolling() {
    _timer?.cancel();
    final interval = Duration(seconds: _config?.pollInterval ?? 15);
    _doCheck(); // 立即执行一次
    _timer = Timer.periodic(interval, (_) => _doCheck());
  }

  Future<void> _doCheck() async {
    if (_config == null || _status == ServiceStatus.loggingIn) return;
    if (_status != ServiceStatus.checking) {
      _setStatus(ServiceStatus.checking);
    }

    final online = await isOnline(
      timeout: Duration(seconds: _config!.httpTimeout),
    );

    if (online) {
      if (!_online) {
        _online = true;
        _log('网络已恢复');
      }
      _setStatus(ServiceStatus.running);
      return;
    }

    if (_online) {
      _online = false;
      _log('网络断开，尝试重新登录...');
    }

    _setStatus(ServiceStatus.loggingIn);
    final result = await doPortalLogin(
      loginPostUrl: _config!.loginPostUrl,
      username: _config!.username,
      password: _config!.password,
      formFields: _config!.formFields,
      timeout: Duration(seconds: _config!.httpTimeout),
    );

    if (result.success) {
      _online = true;
      _log('登录成功: ${result.message}');
    } else {
      _log('登录失败: ${result.message}');
    }
    _setStatus(ServiceStatus.running);
  }

  /// 手动立即检测一次
  Future<void> checkNow() async {
    await _doCheck();
  }
}
'''

with open('lib/auto_login_service.dart', 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
print('auto_login_service.dart rewritten')
