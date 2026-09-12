path = 'lib/portal_login.dart'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 加上 dart:ui
if "import 'dart:ui';" not in content:
    content = content.replace(
        "import 'dart:typed_data';",
        "import 'dart:typed_data';\nimport 'dart:ui';"
    )

# 修复三元运算符语法错误
content = content.replace(
    "if (setCookie != null ? cookie = setCookie.split(';')[0] : null;",
    "if (setCookie != null) cookie = setCookie.split(';')[0];"
)

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print('Fixed')
