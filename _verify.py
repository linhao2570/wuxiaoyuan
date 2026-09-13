with open('lib/portal_login.dart', 'r', encoding='utf-8') as f:
    content = f.read()

# 检查关键内容
checks = [
    'preprocessCaptcha',
    'instantiateImageCodec',
    'FilterQuality.high',
    'maxRetries = 8',
    'Origin',
    'Accept',
    'generateLoginKey',
    'autoLogin',
    'isOnline',
    'disposeOCR',
]

print('=== 检查关键函数/变量 ===')
for c in checks:
    found = c in content
    status = 'OK' if found else 'MISSING'
    print(f'  {status}: {c}')

# 检查语法 (基本括号匹配)
open_braces = content.count('{')
close_braces = content.count('}')
print(f'\n括号匹配: {{={open_braces} }}={close_braces} {"OK" if open_braces == close_braces else "MISMATCH"}')
