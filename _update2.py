import os

# 更新 portal_login.dart - 加 OCR 识别 + 自动重试登录
new_content = '''import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';
import 'package:http/http.dart' as http;
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';

/// 登录结果
class LoginResult {
  final bool success;
  final String message;

  LoginResult({required this.success, required this.message});
}

// ============== RSA 公钥 (从天翼校园网登录页提取 ==============
const String _rsaModulusHex =
    "b2867727e19e1163cc084ea57b9fa8406a910c6703413fa7df96c1acdca7b983a262e005af35f9485d92cd4c622eca4a14d6fd818adca5cae73d9d228b4ef05d732b41fb85f80af578a150ebd9a2eb5ececb853372ca4731ca1c8686892987409be3247f9b26cae8e787d8c135fc0652ec0678a5eda0c3d95cc1741517c0c9c3";
const int _rsaExponent = 0x10001; // 65537

final _textRecognizer = TextRecognizer(script: TextRecognitionScript.latin);

/// 释放 OCR 资源
void disposeOCR() {
  _textRecognizer.close();
}

/// 获取验证码图片和 cookie
Future<Map<String, dynamic>> fetchCaptcha(String baseUrl) async {
  final time = DateTime.now().millisecondsSinceEpoch;
  final url = '$baseUrl/common/image_code.jsp?time=$time';
  
  final resp = await http.get(
    Uri.parse(url),
    headers: {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
    },
  ).timeout(const Duration(seconds: 5));

  String cookie = '';
  final setCookie = resp.headers['set-cookie'];
  if (setCookie != null ? cookie = setCookie.split(';')[0] : null;
  return {'cookie': cookie, 'bytes': resp.bodyBytes};
}

/// OCR 识别验证码
Future<String> recognizeCaptcha(Uint8List imageBytes) async {
  final inputImage = InputImage.fromBytes(
    bytes: imageBytes,
    metadata: const InputImageMetadata(
      size: Size(120, 40), // 验证码图片大概尺寸
      rotation: InputImageRotation.rotation0deg,
      format: InputImageFormat.jpeg,
    ),
  );
  
  try {
    final recognized = await _textRecognizer.processImage(inputImage);
    var text = recognized.text.trim();
    // 清理: 只保留字母数字, 去空格, 转大写
    text = text.replaceAll(RegExp(r'[^a-zA-Z0-9]'), '').toUpperCase();
    return text;
  } catch (e) {
    return '';
  }
}

/// RSA PKCS#1 v1.5 加密 (纯 Dart 实现
String _rsaEncryptPkcs1v15(String plaintext) {
  final plainBytes = utf8.encode(plaintext);
  final n = BigInt.parse(_rsaModulusHex, radix: 16);
  final e = BigInt.from(_rsaExponent);
  final modLen = (n.bitLength + 7) >> 3;

  final padLen = modLen - plainBytes.length - 3;
  if (padLen < 8) {
    throw Exception('明文太长');
  }

  final random = Random.secure();
  final padding = <int>[];
  while (padding.length < padLen) {
    final b = random.nextInt(255) + 1;
    padding.add(b);
  }

  final padded = <int>[
    0x00, 0x02,
    ...padding,
    0x00,
    ...plainBytes,
  ];

  final m = _bytesToBigInt(Uint8List.fromList(padded));
  final c = m.modPow(e, n);

  var result = c.toRadixString(16);
  result = result.padLeft(modLen * 2, '0');
  return result;
}

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

/// 执行登录请求
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
    return LoginResult(success: false, message: '网络错误: ${e.toString().split('\n').first}');
  }
}

/// 自动登录 (带 OCR 验证码识别, 自动重试)
///
/// [maxRetries] 最大重试次数
Future<LoginResult> autoLogin({
  required String baseUrl,
  required String username,
  required String password,
  int maxRetries = 5,
}) async {
  for (int i = 0; i < maxRetries; i++) {
    try {
      // 1. 获取验证码
      final captchaData = await fetchCaptcha(baseUrl);
      final cookie = captchaData['cookie'] ?? '';
      final bytes = captchaData['bytes'] as Uint8List?;
      
      if (bytes == null || cookie.isEmpty) {
        await Future.delayed(const Duration(seconds: 1));
        continue;
      }
      
      // 2. OCR 识别
      final captcha = await recognizeCaptcha(bytes);
      if (captcha.length < 3) {
        // 识别结果太短, 刷新重试
        continue;
      }
      
      // 3. 生成 loginKey 并登录
      final loginKey = generateLoginKey(username, password, captcha);
      final result = await doPortalLogin(
        baseUrl: baseUrl,
        loginKey: loginKey,
        cookie: cookie,
      );
      
      if (result.success) {
        return result;
      }
      
      // 登录失败, 可能是验证码错误, 重试
      await Future.delayed(const Duration(seconds: 1));
    } catch (e) {
      await Future.delayed(const Duration(seconds: 2));
    }
  }
  
  return LoginResult(success: false, message: '重试 $maxRetries 次均失败');
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

# 修复一下语法错误 (InputImage需要导入dart:ui的Size)
# 先修正代码
new_content = new_content.replaceAll(
  "import 'dart:typed_data';",
  "import 'dart:typed_data';\nimport 'dart:ui';"
);

with open('lib/portal_login.dart', 'w', encoding='utf-8', newline='\n') as f:
    f.write(new_content)
print('portal_login.dart updated with OCR')
