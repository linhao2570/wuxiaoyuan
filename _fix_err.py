# 修复 auto_login_service.dart 中的函数名大小写问题
path = 'lib/auto_login_service.dart'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 检查一下实际调用的函数名
print('=== 搜索 autoLogin / autologin ===')
import re
for i, line in enumerate(content.split('\n')):
    if 'login' in line.lower() and ('await' in line or 'def ' in line):
        print(f'  {i}: {line.strip()[:100]}')

print()
print('=== 修复 ===')
# 修复函数调用: autologin -> autoLogin
content = content.replace('await autologin(', 'await autoLogin(')

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
print('auto_login_service.dart fixed')

# 检查 portal_login.dart 里 disposeOCR 是否存在
path2 = 'lib/portal_login.dart'
with open(path2, 'r', encoding='utf-8') as f:
    content2 = f.read()

print()
print('=== portal_login.dart 中 disposeOCR ===')
print('disposeOCR found:', 'disposeOCR' in content2)
print('函数定义:')
for i, line in enumerate(content2.split('\n')):
    if 'dispose' in line.lower():
        print(f'  {i}: {line.strip()[:100]}')
