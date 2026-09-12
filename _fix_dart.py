# 修复 auto_login_service.dart 中的类型错误
import os

filepath = 'lib/auto_login_service.dart'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# 修复 1: started 变量类型
old1 = """    final started = await FlutterForegroundTask.startService(
      notificationTitle: '校园网自动登录',
      notificationText: '后台运行中，断网自动重连',
      callback: _foregroundTaskCallback,
    );

    if (started) {"""

new1 = """    final result = await FlutterForegroundTask.startService(
      notificationTitle: '校园网自动登录',
      notificationText: '后台运行中，断网自动重连',
      callback: _foregroundTaskCallback,
    );

    if (result == ServiceRequestResult.success) {"""

content = content.replace(old1, new1)

# 修复 2: 加上 import
if "import 'package:flutter_foreground_task/models/service_request_result.dart';" not in content:
    content = content.replace(
        "import 'package:flutter_foreground_task/flutter_foreground_task.dart';",
        "import 'package:flutter_foreground_task/flutter_foreground_task.dart';\nimport 'package:flutter_foreground_task/models/service_request_result.dart';"
    )

with open(filepath, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print('Fixed auto_login_service.dart')
print('Verifying...')
with open(filepath, 'r', encoding='utf-8') as f:
    text = f.read()
    if 'ServiceRequestResult' in text:
        print('ServiceRequestResult import found: OK')
    if 'result == ServiceRequestResult.success' in text:
        print('startService check fixed: OK')
