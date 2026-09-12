import os

# 修改 workflow: 构建前先 flutter create . 自动生成安卓目录
path = '.github/workflows/build.yml'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

old = """      - name: Install dependencies
        run: flutter pub get"""

new = """      - name: Create platform projects
        run: flutter create . --platforms android --org com.wyu.esurfing

      - name: Install dependencies
        run: flutter pub get"""

content = content.replace(old, new)

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print('workflow updated')
