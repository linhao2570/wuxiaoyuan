import os

# 1. 更新 portal_login.dart - 增加 RSA 加密和验证码获取
new_content = '''import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';
import 'package:http/http.dart' as http;

/// 登录结果
class LoginResult {
  final bool success;
  final String message;

  LoginResult({required this.success, required this.message});
}

// ============== RSA 公钥 (从天翼校园网登录页提取) ==============
// 公钥指数 0x10001 = 65537
// 公钥模数 (hex)
const String _rsaModulusHex =
    "b2867727e19e1163cc084ea57b9fa8406a910c6703413fa7df96c1acdca7b983a262e005af35f9485d92cd4c622eca4a14d6fd818adca5cae73d9d228b4ef05d732b41fb85f80af578a150ebd9a2eb5ececb853372ca4731ca1c8686892987409be3247f9b26cae8e787d8c135fc0652ec0678a5eda0c3d95cc1741517c0c9c3";
const int _rsaExponent = 0x10001; // 65537

/// 获取验证码图片 (返回图片字节)
/// 返回 {cookie, bytes}
Future<Map<String, dynamic>> fetchCaptcha(String baseUrl) async {
  final time = DateTime.now().millisecondsSinceEpoch;
  final url = '$baseUrl/common/image_code.jsp?time=$time';
  
  final resp = await http.get(
    Uri.parse(url),
    headers: {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
    },
  ).timeout(const Duration(seconds: 5));

  // 从响应头提取 cookie (JSESSIONID)
  String cookie = '';
  final setCookie = resp.headers['set-cookie'];
  if (setCookie != null) {
    cookie = setCookie.split(';')[0];
  }

  return {'cookie': cookie, 'bytes': resp.bodyBytes};
}

/// RSA PKCS#1 v1.5 加密
/// 
/// 对应网页版 JS 的 encryptedString()
/// 用原生 Dart 实现，零平台依赖
String _rsaEncryptPkcs1v15(String plaintext) {
  final plainBytes = utf8.encode(plaintext);
  final n = BigInt.parse(_rsaModulusHex, radix: 16);
  final e = BigInt.from(_rsaExponent);
  final modLen = (n.bitLength + 7) >> 3; // 模数字节长度

  final padLen = modLen - plainBytes.length - 3;
  if (padLen < 8) {
    throw Exception('明文太长');
  }

  // PKCS#1 v1.5 填充: 0x00 || 0x02 || 非零随机字节 || 0x00 || 明文
  final random = Random.secure();
  final padding = <int>[];
  while (padding.length < padLen) {
    final b = random.nextInt(255) + 1; // 1~255, 不能为0
    padding.add(b);
  }

  final padded = <int>[
    0x00,
    0x02,
    ...padding,
    0x00,
    ...plainBytes,
  ];

  // BigInt 加密: c = m^e mod n
  final m = _bytesToBigInt(Uint8List.fromList(padded));
  final c = m.modPow(e, n);

  // 转十六进制
  var result = c.toRadixString(16);
  // 补齐到模数字节数 * 2
  result = result.padLeft(modLen * 2, '0');
  return result;
}

/// bytes -> BigInt (大端)
BigInt _bytesToBigInt(Uint8List bytes) {
  BigInt result = BigInt.zero;
  for (final b in bytes) {
    result = (result << 8) | BigInt.from(b);
  }
  return result;
}

/// 生成 loginKey
String generateLoginKey(String username, String password, String captcha) {
  final plain = '{"userName":"$username","password":"$password","rand":"$captcha"}';
  return _rsaEncryptPkcs1v15(plain);
}

/// 天翼校园网 Portal 登录 (核心函数)
///
/// 参数:
///   baseUrl:   Portal 基础地址, 如 http://enet.10000.gd.cn:10001
///   loginKey:  RSA 加密后的登录密钥
///   cookie:    验证码请求返回的 JSESSIONID
///   wlanuserip: 用户IP (可选, 可为空)
///   wlanacip:   AC IP (可选, 可为空)
Future<LoginResult> doPortalLogin({
  required String baseUrl,
  required String loginKey,
  required String cookie,
  String wlanuserip = '',
  String wlanacip = '',
  Duration timeout = const Duration(seconds: 5),
}) async {
  final url = '$baseUrl/ajax/login';
  
  final Map<String, String> body = {
    'loginKey': loginKey,
    'wlanuserip': wlanuserip,
    'wlanacip': wlanacip,
  };

  try {
    final response = await http
        .post(
          Uri.parse(url),
          body: body,
          headers: {
            'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'Cookie': cookie,
            'Referer': '$baseUrl/qs/',
          },
        )
        .timeout(timeout);

    final raw = utf8.decode(response.bodyBytes);
    bool success = false;
    String message = '';

    try {
      final result = jsonDecode(raw) as Map<String, dynamic>;
      // 天翼返回: resultCode == "0" 或 "13002000" 表示成功
      final code = result['resultCode']?.toString() ?? '';
      if (code == '0' || code == '13002000') {
        success = true;
      }
      message = (result['resultInfo'] ?? result['resultCode'] ?? '').toString();
    } catch (_) {
      message = '返回格式异常';
    }

    return LoginResult(success: success, message: message);
  } catch (e) {
    return LoginResult(success: false, message: '网络错误: ${e.toString().split('\\n').first}');
  }
}

/// 网络连通性检测
Future<bool> isOnline({Duration timeout = const Duration(seconds: 4)}) async {
  try {
    final response = await http
        .get(Uri.parse('http://www.baidu.com'))
        .timeout(timeout);
    final body = utf8.decode(response.bodyBytes);
    return body.toLowerCase().contains('baidu') &&
        !body.toLowerCase().contains('enet.10000');
  } catch (_) {
    return false;
  }
}
'''

with open('lib/portal_login.dart', 'w', encoding='utf-8', newline='\n') as f:
    f.write(new_content)
print('portal_login.dart updated')
