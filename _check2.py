path = 'lib/portal_login.dart'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 找 textRecognizer 定义
print('textRecognizer found:', 'textRecognizer' in content)
print('_textRecognizer found:', '_textRecognizer' in content)

# 检查 recognizeCaptcha 里用的什么
for i, line in enumerate(content.split('\n')):
    if 'ecognizer' in line:
        print(f'  {i}: {line.strip()[:120]}')
