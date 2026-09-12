import 'dart:async';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:flutter_foreground_task/models/service_request_result.dart';
import 'portal_login.dart';
import 'storage.dart';

/// 服务状态
enum ServiceStatus { stopped, running, checking, loggingIn }

/// 自动登录服务（前台服务保活）
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
  Future<void> start(AppConfig config) async {
    if (_status != ServiceStatus.stopped) return;
    _config = config;

    // 初始化前台服务
    await FlutterForegroundTask.init(
      androidNotificationOptions: AndroidNotificationOptions(
        channelId: 'esurfing_autologin',
        channelName: '校园网自动登录',
        channelImportance: NotificationChannelImportance.LOW,
        priority: NotificationPriority.LOW,
      ),
      foregroundTaskOptions: const ForegroundTaskOptions(
        interval: 5000,
        autoRunOnBoot: true,
        allowWifiLock: true,
      ),
      iosNotificationOptions: const IOSNotificationOptions(),
      notificationContent: const NotificationContent(
        id: 1001,
        title: '校园网自动登录',
        body: '后台运行中，断网自动重连',
      ),
    );

    final result = await FlutterForegroundTask.startService(
      notificationTitle: '校园网自动登录',
      notificationText: '后台运行中，断网自动重连',
      callback: _foregroundTaskCallback,
    );

    if (result == ServiceRequestResult.success) {
      _setStatus(ServiceStatus.running);
      _log('服务已启动');
      _startPolling();
    }
  }

  /// 停止服务
  Future<void> stop() async {
    _timer?.cancel();
    _timer = null;
    await FlutterForegroundTask.stopService();
    _setStatus(ServiceStatus.stopped);
    _log('服务已停止');
  }

  /// 主轮询循环
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

    // 离线，尝试登录
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

/// 前台任务回调（在独立 isolate 中运行，这里仅做保活用）
/// 实际检测逻辑在主 isolate 中通过 Timer 执行
@pragma('vm:entry-point')
void _foregroundTaskCallback() {
  FlutterForegroundTask.updateService(
    notificationTitle: '校园网自动登录',
    notificationText: '后台运行中',
  );
}
