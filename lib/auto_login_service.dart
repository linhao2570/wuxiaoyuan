import 'dart:async';
import 'portal_login.dart';
import 'storage.dart';

enum ServiceStatus { stopped, running, checking, loggingIn }

/// 自动登录服务
/// 
/// 工作模式:
/// - 定时续期: 默认每 90 分钟自动登录一次, 维持 session 活跃
/// - 掉线检测: 每隔 1 分钟检测一次网络, 掉线立即重登
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

  Timer? _checkTimer;   // 检测定时器 (短间隔)
  Timer? _renewTimer;   // 续期定时器 (长间隔)
  AppConfig? _config;
  bool _online = false;
  DateTime _lastLogin = DateTime.fromMillisecondsSinceEpoch(0);

  void _log(String msg) {
    _logStream.add(msg);
  }

  void _setStatus(ServiceStatus s) {
    _status = s;
    _statusStream.add(s);
  }

  void start(AppConfig config) {
    if (_status != ServiceStatus.stopped) return;
    _config = config;
    _setStatus(ServiceStatus.running);
    _log('服务已启动');
    _startTimers();
    // 启动后立即检测一次
    _doCheck();
  }

  void stop() {
    _checkTimer?.cancel();
    _renewTimer?.cancel();
    _checkTimer = null;
    _renewTimer = null;
    _setStatus(ServiceStatus.stopped);
    _log('服务已停止');
  }

  void _startTimers() {
    // 掉线检测: 每 3 分钟检测一次网络
    _checkTimer?.cancel();
    _checkTimer = Timer.periodic(const Duration(minutes: 3), (_) => _doCheck());

    // 定时续期: 每 90 分钟主动登录一次 (2小时有效期, 提前半小时续)
    _renewTimer?.cancel();
    _renewTimer = Timer.periodic(const Duration(minutes: 90), (_) => _doRenew());
  }

  /// 检测网络状态 (快速检测)
  Future<void> _doCheck() async {
    if (_config == null || _status == ServiceStatus.loggingIn) return;
    
    final wasChecking = _status == ServiceStatus.checking;
    if (!wasChecking) _setStatus(ServiceStatus.checking);

    final online = await isOnline(
      timeout: Duration(seconds: _config!.httpTimeout),
    );

    if (online) {
      if (!_online) {
        _online = true;
        _log('网络正常');
      }
      if (!wasChecking) _setStatus(ServiceStatus.running);
      return;
    }

    // 掉线了, 立即重登
    if (_online) {
      _online = false;
      _log('检测到掉线, 正在重连...');
    }
    await _doLogin('掉线重连');
    if (!wasChecking) _setStatus(ServiceStatus.running);
  }

  /// 定时续期登录
  Future<void> _doRenew() async {
    if (_config == null) return;
    _log('执行定时续期登录...');
    await _doLogin('定时续期');
  }

  /// 执行登录 (带 OCR 自动识别验证码, 重试 5 次)
  Future<bool> _doLogin(String reason) async {
    if (_config == null) return false;
    _setStatus(ServiceStatus.loggingIn);

    final result = await autoLogin(
      baseUrl: _config!.baseUrl,
      username: _config!.username,
      password: _config!.password,
      maxRetries: 5,
    );

    if (result.success) {
      _online = true;
      _lastLogin = DateTime.now();
      _log('[$reason] 登录成功');
    } else {
      _log('[$reason] 登录失败: ${result.message}');
    }

    _setStatus(ServiceStatus.running);
    return result.success;
  }

  /// 手动立即登录 (供测试按钮调用)
  Future<LoginResult> manualLogin() async {
    if (_config == null) {
      return LoginResult(success: false, message: '请先配置');
    }
    _setStatus(ServiceStatus.loggingIn);
    final result = await autoLogin(
      baseUrl: _config!.baseUrl,
      username: _config!.username,
      password: _config!.password,
      maxRetries: 3,
    );
    if (result.success) {
      _online = true;
      _lastLogin = DateTime.now();
    }
    _setStatus(ServiceStatus.running);
    return result;
  }

  Future<void> checkNow() async {
    await _doCheck();
  }
}
