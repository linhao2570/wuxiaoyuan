import os

# 用纯 Python 生成一个简单的 PNG 图标（蓝色背景白色 Wifi 图标风格的占位图）
# 不用第三方库，手写一个简单的 PNG

def create_simple_png(filepath, size=192, bg_color=(33, 150, 243), fg_color=(255, 255, 255)):
    """生成一个简单的纯色圆形+字母的PNG图标（不依赖PIL）"""
    import struct, zlib
    
    width = height = size
    # 准备像素数据（RGBA）
    raw = b''
    cx, cy = size/2, size/2
    radius = size * 0.42
    
    for y in range(height):
        raw += b'\x00'  # filter byte
        for x in range(width):
            dx, dy = x - cx, y - cy
            dist = (dx*dx + dy*dy) ** 0.5
            if dist <= radius:
                # 在圆内，画背景色 + 中间一个简单的"W"字母效果（用两条横线模拟）
                r, g, b = bg_color
                a = 255
                # 简单的"W"形状：画三条竖线
                if (size*0.28 <= x <= size*0.36 and size*0.3 <= y <= size*0.7) or \
                   (size*0.46 <= x <= size*0.54 and size*0.4 <= y <= size*0.75) or \
                   (size*0.64 <= x <= size*0.72 and size*0.3 <= y <= size*0.7):
                    r, g, b = fg_color
            else:
                # 透明
                r, g, b, a = 0, 0, 0, 0
            raw += bytes([r, g, b, a])
    
    def chunk(ctype, data):
        c = ctype + data
        crc = zlib.crc32(c) & 0xffffffff
        return struct.pack('>I', len(data)) + c + struct.pack('>I', crc)
    
    sig = b'\x89PNG\r\n\x1a\n'
    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)  # 8-bit RGBA
    idat = zlib.compress(raw)
    
    png = sig + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b'')
    
    with open(filepath, 'wb') as f:
        f.write(png)

# 生成各种尺寸的图标
sizes = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

base = 'android/app/src/main/res'
for folder, size in sizes.items():
    os.makedirs(f'{base}/{folder}', exist_ok=True)
    create_simple_png(f'{base}/{folder}/ic_launcher.png', size=size)
    print(f'Created {folder}/ic_launcher.png ({size}x{size})')

print('All launcher icons generated')
