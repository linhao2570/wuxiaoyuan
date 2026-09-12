import os

filepath = 'lib/main.dart'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# 加上 dart:async 的 import
if "import 'dart:async';" not in content:
    content = content.replace(
        "import 'package:flutter/material.dart';",
        "import 'dart:async';\nimport 'package:flutter/material.dart';"
    )

with open(filepath, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print('Fixed: added dart:async import')
