# 检查 main.dart 里的 disposeOCR 调用
with open('lib/main.dart', 'r', encoding='utf-8') as f:
    main_content = f.read()

print('=== main.dart ===')
print('import portal_login:', "import 'portal_login.dart';" in main_content or "import 'portal_login.dart'" in main_content)
print('disposeOCR call:', 'disposeOCR' in main_content)
print('manualLogin call:', 'manualLogin' in main_content)

# 检查 auto_login_service.dart
with open('lib/auto_login_service.dart', 'r', encoding='utf-8') as f:
    service_content = f.read()

print()
print('=== auto_login_service.dart ===')
print('import portal_login:', "import 'portal_login.dart';" in service_content)
print('autoLogin call count:', service_content.count('autoLogin('))
print('autologin (lowercase) count:', service_content.count('autologin('))
