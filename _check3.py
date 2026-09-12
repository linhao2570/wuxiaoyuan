with open('lib/portal_login.dart', 'r', encoding='utf-8') as f:
    content = f.read()
print('File size:', len(content))
has_ocr = 'mlkit' in content.lower() or 'recogniz' in content.lower()
print('Has OCR:', has_ocr)
print('--- first 300 chars ---')
print(content[:300])
