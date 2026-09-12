import os

path = '.github/workflows/build.yml'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 把 release 改成 debug, 绕开 R8 混淆问题
content = content.replace(
    "run: flutter build apk --release",
    "run: flutter build apk --debug"
)

# 同时修改上传路径
content = content.replace(
    "path: build/app/outputs/flutter-apk/app-release.apk",
    "path: build/app/outputs/flutter-apk/app-debug.apk"
)

# artifact 名字也改一下
content = content.replace(
    "name: app-release.apk",
    "name: app-debug.apk"
)

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print('workflow updated to debug build')
