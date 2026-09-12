import os

# 更新 pubspec.yaml - 改 name 和加 path_provider
pubspec = '''name: aiqin
description: 校园网自动登录
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
  path_provider: ^2.1.0

dev_dependencies:
  flutter_lints: ^3.0.0

flutter:
  uses-material-design: true
'''

with open('pubspec.yaml', 'w', encoding='utf-8', newline='\n') as f:
    f.write(pubspec)

# 更新 storage.dart - 改类名和 import
# 其实类名不需要改, 只改项目名和 app 名即可
# workflow 里 flutter create 会用 pubspec 的 name

# 更新 workflow 里的 org, 让生成的包名是 com.aiqin.esurfing -> 不用, 保持默认也行

print('pubspec.yaml updated, app name = aiqin')
