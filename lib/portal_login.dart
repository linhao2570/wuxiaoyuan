import 'dart:convert';
import 'package:http/http.dart' as http;

/// 登录结果
class LoginResult {
  final bool success;
  final String message;

  LoginResult({required this.success, required this.message});
}

/// 天翼校园网 Portal 登录（核心函数）
///
/// 参数结构与 Python 版 do_portal_login 完全对应，方便对照调试。
Future<LoginResult> doPortalLogin({
  required String loginPostUrl,
  required String username,
  required String password,
  Map<String, String>? formFields,
  Duration timeout = const Duration(seconds: 5),
}) async {
  // 合并表单字段：额外字段 + 账号密码
  final Map<String, String> data = Map<String, String>.from(formFields ?? {});
  data['username'] = username;
  data['password'] = password;

  try {
    final response = await http
        .post(
          Uri.parse(loginPostUrl),
          body: data,
          headers: {
            'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Mobile) '
                'AppleWebKit/537.36 (KHTML, like Gecko) '
                'Chrome/120.0.0.0 Mobile Safari/537.36',
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
          },
        )
        .timeout(timeout);

    final raw = utf8.decode(response.bodyBytes);
    bool success = false;
    String message = '';

    try {
      final result = jsonDecode(raw) as Map<String, dynamic>;
      // 兼容多种常见返回格式
      if (result['result']?.toString().toLowerCase() == 'success' ||
          result['result']?.toString() == '1') {
        success = true;
      } else if (result['code']?.toString() == '0' ||
          result['code']?.toString() == '200' ||
          result['code']?.toString() == '0000') {
        success = true;
      } else if (result['status']?.toString().toLowerCase() == 'success' ||
          result['status']?.toString() == 'ok') {
        success = true;
      }
      message = (result['message'] ??
              result['msg'] ??
              result['info'] ??
              result['result'] ??
              '')
          .toString();
    } catch (_) {
      // 非 JSON，按页面关键字判断
      if (raw.contains('成功') || raw.toLowerCase().contains('success')) {
        success = true;
        message = '登录成功';
      } else {
        message = '返回格式异常';
      }
    }

    if (message.isEmpty) {
      message = raw.length > 200 ? raw.substring(0, 200) : raw;
    }

    return LoginResult(success: success, message: message);
  } catch (e) {
    return LoginResult(success: false, message: '网络错误: ${e.toString().split('\n').first}');
  }
}

/// 网络连通性检测（HTTP 探测，比 ping 更准确判断 Portal 是否放行）
/// 能访问百度且不被跳转到登录页 = 已认证在线
Future<bool> isOnline({Duration timeout = const Duration(seconds: 4)}) async {
  try {
    final response = await http
        .get(Uri.parse('http://www.baidu.com'))
        .timeout(timeout);
    // 百度正常返回会包含 "baidu"，如果被 Portal 拦截跳转就不含
    final body = utf8.decode(response.bodyBytes);
    return body.toLowerCase().contains('baidu') &&
        !body.toLowerCase().contains('eportal') &&
        !body.toLowerCase().contains('esurfing') &&
        !body.toLowerCase().contains('portal');
  } catch (_) {
    return false;
  }
}
