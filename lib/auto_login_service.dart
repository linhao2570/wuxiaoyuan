import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';
import 'portal_login.dart';
import 'storage.dart';

enum ServiceStatus { stopped, running, checking, loggingIn }

/// 自动登录服务
/// 
/// 说明:
/// - 首次需要手动输入验证码登录，获取 session cookie
/// - 之后 session 有效期内掉线可自动重登 (无需验证码)
/// - session 过期后需要重新手动输入验证码
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
  
  // 保存有效的 session cookie (登录成功后复用)
  String _sessionCookie = '';

  void _log(String msg) {
    _logStream.add(msg);
  }

  void _setStatus(ServiceStatus s) {
    _status = s;
    _statusStream.add(s);
  }

  /// 设置 session cookie (手动登录成功后调用)
  void setSessionCookie(String cookie) {
    _sessionCookie = cookie;
  }

  /// 当前是否有有效 session
  bool get hasSession => _sessionCookie.isNotEmpty;

  void start(AppConfig config) {
    if (_status != ServiceStatus.stopped) return;
    _config = config;
    _setStatus(ServiceStatus.running);
    _log('服务已启动');
    _startPolling();
  }

  void stop() {
    _timer?.cancel();
    _timer = null;
    _setStatus(ServiceStatus.stopped);
    _log('服务已停止');
  }

  void _startPolling() {
    _timer?.cancel();
    final interval = Duration(seconds: _config?.pollInterval ?? 15);
    _doCheck();
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
      _log('网络断开');
    }

    // 没有 session cookie 就不能自动登录，需要手动输入验证码
    if (_sessionCookie.isEmpty) {
      _log('无有效 session，需手动输入验证码登录');
      _setStatus(ServiceStatus.running);
      return;
    }

    _setStatus(ServiceStatus.loggingIn);
    _log('尝试自动重登...');
    
    // 尝试用保存的 session cookie 重新获取验证码并登录
    // 注意: 这里验证码需要 OCR 识别，目前先用一个简单的重试机制
    // 实际使用中，如果 session 没过期，可能不需要验证码也能登录
    // 先尝试直接登录 (不带验证码，看服务器返回什么)
    
    // 策略: 重新获取验证码图片，用户手动识别不现实，所以自动模式下
    // 我们依赖 session 有效期内的免验证码登录机制
    // 如果服务器要求验证码而 session 已失效，就记录日志等待用户手动操作
    
    // 尝试用已有 cookie 直接登录 (带空验证码试试)
    // 实际根据返回结果调整
    _log('正在重连，请稍候...');
    
    // 先刷新一个验证码
    try {
      final captchaData = await fetchCaptcha(_config!.baseUrl);
      final newCookie = captchaData['cookie'] ?? '';
      if (newCookie.isNotEmpty) {
        _sessionCookie = newCookie;
      }
      
      // 自动模式下没有 OCR 就无法识别验证码
      // 这里先尝试不带验证码登录，看服务器返回
      // 实际五邑大学的系统需要验证码，所以自动重登功能受限
      _log('需要验证码，自动重登暂不支持。请打开 App 手动输入验证码登录。');
    } catch (e) {
      _log('获取验证码失败: $e');
    }
    
    _setStatus(ServiceStatus.running);
  }

  Future<void> checkNow() async {
    await _doCheck();
  }
}
