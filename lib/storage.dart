import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

class AppConfig {
  String baseUrl;
  String username;
  String password;
  int pollInterval;
  int httpTimeout;

  AppConfig({
    this.baseUrl = '',
    this.username = '',
    this.password = '',
    this.pollInterval = 15,
    this.httpTimeout = 5,
  });

  static Future<AppConfig> load() async {
    final prefs = await SharedPreferences.getInstance();
    return AppConfig(
      baseUrl: prefs.getString('baseUrl') ?? '',
      username: prefs.getString('username') ?? '',
      password: prefs.getString('password') ?? '',
      pollInterval: prefs.getInt('pollInterval') ?? 15,
      httpTimeout: prefs.getInt('httpTimeout') ?? 5,
    );
  }

  Future<void> save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('baseUrl', baseUrl);
    await prefs.setString('username', username);
    await prefs.setString('password', password);
    await prefs.setInt('pollInterval', pollInterval);
    await prefs.setInt('httpTimeout', httpTimeout);
  }

  bool get isValid =>
      baseUrl.isNotEmpty && username.isNotEmpty && password.isNotEmpty;
}
