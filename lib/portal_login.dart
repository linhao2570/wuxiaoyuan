import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';
import 'dart:ui';
import 'package:http/http.dart' as http;
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:path_provider/path_provider.dart';
import 'dart:io';
import 'dart:ui' as ui;

class LoginResult {
  final bool success;
  final String message;
  LoginResult({required this.success, required this.message});
}

const String _rsaModulusHex =
    "b2867727e19e1163cc084ea57b9fa8406a910c6703413fa7df96c1acdca7b983a262e005af35f9485d92cd4c622eca4a14d6fd818adca5cae73d9d228b4ef05d732b41fb85f80af578a150ebd9a2eb5ececb853372ca4731ca1c8686892987409be3247f9b26cae8e787d8c135fc0652ec0678a5eda0c3d95cc1741517c0c9c3";
const int _rsaExponent = 0x10001;

final _textRecognizer = TextRecognizer(script: TextRecognitionScript.latin);

void disposeOCR() {
  _textRecognizer.close();
}

Future<Map<String, dynamic>> fetchCaptcha(String baseUrl) async {
  final time = DateTime.now().millisecondsSinceEpoch;
  final url = '\/common/image_code.jsp?time=';
  final resp = await http.get(
    Uri.parse(url),
    headers: {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
    },
  ).timeout(const Duration(seconds: 5));
  String cookie = '';
  final setCookie = resp.headers['set-cookie'];
  if (setCookie != null) cookie = setCookie.split(';')[0];
  return {'cookie': cookie, 'bytes': resp.bodyBytes};
}

Future<Uint8List> preprocessCaptcha(Uint8List bytes) async {
  try {
    final codec = await instantiateImageCodec(bytes);
    final frame = await codec.getNextFrame();
    final image = frame.image;
    const scale = 3;
    final newWidth = image.width * scale;
    final newHeight = image.height * scale;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      Rect.fromLTWH(0, 0, newWidth.toDouble(), newHeight.toDouble()),
      Paint()..filterQuality = FilterQuality.high,
    );
    final pic = recorder.endRecording();
    final scaledImage = await pic.toImage(newWidth, newHeight);
    final byteData = await scaledImage.toByteData(format: ImageByteFormat.png);
    image.dispose();
    scaledImage.dispose();
    return byteData!.buffer.asUint8List();
  } catch (e) {
    return bytes;
  }
}

Future<String> recognizeCaptcha(Uint8List imageBytes) async {
  final processed = await preprocessCaptcha(imageBytes);
  final tempDir = await getTemporaryDirectory();
  final file = File('\/captcha_\.png');
  await file.writeAsBytes(processed);
  try {
    final inputImage = InputImage.fromFilePath(file.path);
    final recognized = await _textRecognizer.processImage(inputImage);
    var text = recognized.text.trim();
    text = text.replaceAll(RegExp(r'[^a-zA-Z0-9]'), '').toUpperCase();
    if (text.length > 6) text = text.substring(0, 4);
    return text;
  } catch (e) {
    return '';
  } finally {
    try { await file.delete(); } catch (_) {}
  }
}

String _rsaEncrypt(String plaintext) {
  final plainBytes = utf8.encode(plaintext);
  final n = BigInt.parse(_rsaModulusHex, radix: 16);
  final e = BigInt.from(_rsaExponent);
  final modLen = (n.bitLength + 7) >> 3;
  final padLen = modLen - plainBytes.length - 3;
  if (padLen < 8) throw Exception('plaintext too long');
  final random = Random.secure();
  final padding = <int>[];
  while (padding.length < padLen) {
    final b = random.nextInt(255) + 1;
    padding.add(b);
  }
  final padded = <int>[0x00, 0x02, ...padding, 0x00, ...plainBytes];
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

String generateLoginKey(String username, String password, String captcha) {
  final plain = '{"userName":"","password":"","rand":""}';
  return _rsaEncrypt(plain);
}

Future<LoginResult> doPortalLogin({
  required String baseUrl,
  required String loginKey,
  required String cookie,
  String wlanuserip = '',
  String wlanacip = '',
  Duration timeout = const Duration(seconds: 5),
}) async {
  final url = '\/ajax/login';
  final body = <String, String>{
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
            'Referer': '\/qs/',
            'Origin': baseUrl,
            'Accept': 'application/json, text/javascript, */*; q=0.01',
          },
        )
        .timeout(timeout);
    final raw = utf8.decode(response.bodyBytes);
    bool success = false;
    String message = '';
    try {
      final result = jsonDecode(raw) as Map<String, dynamic>;
      final code = result['resultCode']?.toString() ?? '';
      if (code == '0' || code == '13002000') success = true;
      message = (result['resultInfo'] ?? result['resultCode'] ?? '').toString();
    } catch (_) {
      message = 'bad response';
    }
    return LoginResult(success: success, message: message);
  } catch (e) {
    return LoginResult(success: false, message: 'error: ');
  }
}

Future<LoginResult> autoLogin({
  required String baseUrl,
  required String username,
  required String password,
  int maxRetries = 8,
}) async {
  String lastMsg = '';
  for (int i = 0; i < maxRetries; i++) {
    try {
      final captchaData = await fetchCaptcha(baseUrl);
      final cookie = captchaData['cookie'] ?? '';
      final bytes = captchaData['bytes'] as Uint8List?;
      if (bytes == null || cookie.isEmpty) {
        await Future.delayed(const Duration(seconds: 1));
        continue;
      }
      final captcha = await recognizeCaptcha(bytes);
      if (captcha.length < 3) continue;
      final loginKey = generateLoginKey(username, password, captcha);
      final result = await doPortalLogin(
        baseUrl: baseUrl,
        loginKey: loginKey,
        cookie: cookie,
      );
      if (result.success) return result;
      lastMsg = result.message;
      await Future.delayed(const Duration(milliseconds: 800));
    } catch (e) {
      lastMsg = e.toString().split('\n').first;
      await Future.delayed(const Duration(seconds: 1));
    }
  }
  return LoginResult(success: false, message: '重试 \ 次失败, 最后: ');
}

Future<bool> isOnline({Duration timeout = const Duration(seconds: 4)}) async {
  try {
    final response = await http.get(Uri.parse('http://www.baidu.com')).timeout(timeout);
    final body = utf8.decode(response.bodyBytes);
    return body.toLowerCase().contains('baidu') && !body.toLowerCase().contains('enet.10000');
  } catch (_) {
    return false;
  }
}
