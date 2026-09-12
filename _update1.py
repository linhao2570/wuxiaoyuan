import os

# 更新 pubspec.yaml - 加 OCR 依赖
pubspec = '''name: wyu_esurfing
description: 五邑大学天翼校园网自动登录
publish_to: 'none'
version: 1.0.0+1

environment:
  sdk: '>=3.0.0 <4.0.0'

dependencies:
  flutter:
    sdk: flutter
  http: ^1.2.0
  shared_preferences: ^2.2.0
  google_mlkit_text_recognition: ^0.14.0

dev_dependencies:
  flutter_lints: ^3.0.0

flutter:
  uses-material-design: true
'''

with open('pubspec.yaml', 'w', encoding='utf-8', newline='\n') as f:
    f.write(pubspec)
print('pubspec.yaml updated')
