import os
with open('_portal_content.py', 'r', encoding='utf-8') as f:
    content = f.read()
# 去掉 Python 的 outer wrapper, 只取 $script = @' ... '@ 中间的内容
# 其实直接用 PowerShell 已经写入了, 我们直接读出来验证
with open('lib/portal_login.dart', 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
print('Written, size:', len(content))
print('Has path_provider:', 'path_provider' in content)
print('Has fromFilePath:', 'fromFilePath' in content)
print('Has disposeOCR:', 'disposeOCR' in content)
print('Has autoLogin:', 'autoLogin' in content)
