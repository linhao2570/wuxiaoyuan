import os

# 1. 修改 AndroidManifest.xml: 把 MainActivity 换成 Flutter 默认的
manifest_path = 'android/app/src/main/AndroidManifest.xml'
with open(manifest_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'android:name=".MainActivity"',
    'android:name="io.flutter.embedding.android.FlutterActivity"'
)

with open(manifest_path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

# 2. 删除 kotlin 目录（不需要自己的 MainActivity 了）
import shutil
kotlin_dir = 'android/app/src/main/kotlin'
if os.path.exists(kotlin_dir):
    shutil.rmtree(kotlin_dir)
    print('Removed kotlin directory')

print('AndroidManifest.xml updated')
