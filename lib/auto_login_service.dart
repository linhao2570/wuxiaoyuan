import 'dart:async';
import 'portal_login.dart';
import 'storage.dart';

enum ServiceStatus { stopped, running, checking, loggingIn }

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

  Timer? _checkTimer;
  Timer? _renewTimer;
  AppConfig? _config;
  bool _online = false;
  
  DateTime _lastLoginTime = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime get lastLoginTime => _lastLoginTime;
  set lastLoginTime(DateTime t) => _lastLoginTime = t;

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
    _checkTimer?.cancel();
    _renewTimer?.cancel();
    _checkTimer = Timer.periodic(const Duration(minutes: 3), (_) => _doCheck());
    _renewTimer = Timer.periodic(const Duration(minutes: 90), (_) => _doRenew());
  }

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

    if (_online) {
      _online = false;
      _log('检测到掉线, 正在重连...');
    }
    await _doLogin('掉线重连');
    if (!wasChecking) _setStatus(ServiceStatus.running);
  }

  Future<void> _doRenew() async {
    if (_config == null) return;
    _log('执行定时续期登录...');
    await _doLogin('定时续期');
  }

  Future<bool> _doLogin(String reason) async {
    if (_config == null) return false;
    _setStatus(ServiceStatus.loggingIn);

    final result = await autoLogin(
      baseUrl: _config!.baseUrl,
      username: _config!.username,
      password: _config!.password,
      maxRetries: 8,
    );

    if (result.success) {
      _online = true;
      _lastLoginTime = DateTime.now();
      _log('[$reason] 登录成功');
    } else {
      _log('[$reason] 登录失败: ${result.message}');
    }

    _setStatus(ServiceStatus.running);
    return result.success;
  }

  Future<LoginResult> manualLogin() async {
    if (_config == null) {
      return LoginResult(success: false, message: '请先配置');
    }
    _setStatus(ServiceStatus.loggingIn);
    final result = await autoLogin(
      baseUrl: _config!.baseUrl,
      username: _config!.username,
      password: _config!.password,
      maxRetries: 5,
    );
    if (result.success) {
      _online = true;
      _lastLoginTime = DateTime.now();
    }
    _setStatus(ServiceStatus.running);
    return result;
  }

  Future<void> checkNow() async {
    await _doCheck();
  }
}
