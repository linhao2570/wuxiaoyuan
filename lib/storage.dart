import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

/// 应用配置
class AppConfig {
  String loginPostUrl;
  String username;
  String password;
  Map<String, String> formFields;
  int pollInterval; // 秒
  int httpTimeout;  // 秒

  AppConfig({
    this.loginPostUrl = '',
    this.username = '',
    this.password = '',
    Map<String, String>? formFields,
    this.pollInterval = 15,
    this.httpTimeout = 5,
  }) : formFields = formFields ?? {};

  /// 从 JSON 解析 form_fields
  static Map<String, String> _parseFormFields(String? jsonStr) {
    if (jsonStr == null || jsonStr.isEmpty) return {};
    try {
      final map = jsonDecode(jsonStr) as Map<String, dynamic>;
      return map.map((k, v) => MapEntry(k, v.toString()));
    } catch (_) {
      return {};
    }
  }

  /// 从本地存储加载
  static Future<AppConfig> load() async {
    final prefs = await SharedPreferences.getInstance();
    return AppConfig(
      loginPostUrl: prefs.getString('loginPostUrl') ?? '',
      username: prefs.getString('username') ?? '',
      password: prefs.getString('password') ?? '',
      formFields: _parseFormFields(prefs.getString('formFields')),
      pollInterval: prefs.getInt('pollInterval') ?? 15,
      httpTimeout: prefs.getInt('httpTimeout') ?? 5,
    );
  }

  /// 保存到本地存储
  Future<void> save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('loginPostUrl', loginPostUrl);
    await prefs.setString('username', username);
    await prefs.setString('password', password);
    await prefs.setString('formFields', jsonEncode(formFields));
    await prefs.setInt('pollInterval', pollInterval);
    await prefs.setInt('httpTimeout', httpTimeout);
  }

  /// 配置是否有效
  bool get isValid =>
      loginPostUrl.isNotEmpty && username.isNotEmpty && password.isNotEmpty;
}
