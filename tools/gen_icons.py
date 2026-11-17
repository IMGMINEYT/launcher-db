#!/usr/bin/env python3
"""Generates assets/icons/*.png: tiny 32x32 pixel-art icons, one per command family. Local, no network."""
import os, struct, zlib

W = H = 32

def png(path, px):
    flat = []
    for c in px: flat.extend(c)
    raw = b"".join(b"\x00" + bytes(flat[y * W * 4:(y + 1) * W * 4]) for y in range(H))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    open(path, "wb").write(data)

def canvas(bg=(24, 26, 32, 255)):
    return [bg for _ in range(W * H)]

def rect(px, x0, y0, x1, y1, c):
    for y in range(max(0, y0), min(H, y1)):
        for x in range(max(0, x0), min(W, x1)):
            px[y * W + x] = c

def disc(px, cx, cy, r, c):
    for y in range(H):
        for x in range(W):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                px[y * W + x] = c

def diamond(px, cx, cy, r, c):
    for y in range(H):
        for x in range(W):
            if abs(x - cx) + abs(y - cy) <= r:
                px[y * W + x] = c

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "assets", "icons")
os.makedirs(OUT, exist_ok=True)

# ic_give: cyan diamond
p = canvas(); diamond(p, 16, 16, 9, (80, 225, 240, 255)); diamond(p, 16, 16, 5, (150, 245, 255, 255)); png(os.path.join(OUT, "ic_give.png"), p)
# ic_summon: creeper-ish face
p = canvas(); rect(p, 6, 6, 26, 26, (60, 170, 70, 255))
rect(p, 9, 11, 13, 15, (10, 20, 10, 255)); rect(p, 19, 11, 23, 15, (10, 20, 10, 255))
rect(p, 14, 15, 18, 23, (10, 20, 10, 255)); rect(p, 11, 19, 14, 24, (10, 20, 10, 255)); rect(p, 18, 19, 21, 24, (10, 20, 10, 255))
png(os.path.join(OUT, "ic_summon.png"), p)
# ic_effect: potion bottle
p = canvas(); rect(p, 13, 5, 19, 9, (200, 200, 210, 255)); rect(p, 11, 9, 21, 12, (160, 160, 175, 255))
disc(p, 16, 19, 8, (120, 70, 190, 255)); disc(p, 16, 20, 6, (190, 120, 240, 255)); rect(p, 13, 15, 15, 18, (230, 180, 250, 255))
png(os.path.join(OUT, "ic_effect.png"), p)
# ic_enchant: book
p = canvas(); rect(p, 7, 7, 25, 25, (110, 60, 150, 255)); rect(p, 9, 9, 23, 23, (240, 235, 220, 255)); rect(p, 15, 9, 17, 23, (180, 90, 220, 255))
png(os.path.join(OUT, "ic_enchant.png"), p)
# ic_block: grass block
p = canvas(); rect(p, 6, 6, 26, 26, (121, 85, 58, 255)); rect(p, 6, 6, 26, 13, (95, 160, 60, 255))
rect(p, 8, 13, 11, 16, (95, 160, 60, 255)); rect(p, 20, 13, 24, 17, (95, 160, 60, 255))
png(os.path.join(OUT, "ic_block.png"), p)
# ic_locate: red map pin
p = canvas(); disc(p, 16, 13, 8, (220, 60, 60, 255)); disc(p, 16, 13, 3, (250, 240, 240, 255))
for i in range(8): rect(p, 16 - (7 - i) // 2, 18 + i, 17 + (7 - i) // 2 + 1, 19 + i, (220, 60, 60, 255))
png(os.path.join(OUT, "ic_locate.png"), p)
# ic_tp: ender pearl
p = canvas(); disc(p, 16, 16, 9, (30, 90, 85, 255)); disc(p, 14, 14, 5, (70, 190, 180, 255)); disc(p, 13, 13, 2, (170, 250, 245, 255))
png(os.path.join(OUT, "ic_tp.png"), p)
# ic_rule: gear
p = canvas(); disc(p, 16, 16, 7, (150, 150, 160, 255)); disc(p, 16, 16, 3, (24, 26, 32, 255))
for a in range(8):
    import math
    x = int(16 + 9.5 * math.cos(a * math.pi / 4)); y = int(16 + 9.5 * math.sin(a * math.pi / 4))
    rect(p, x - 2, y - 2, x + 2, y + 2, (150, 150, 160, 255))
png(os.path.join(OUT, "ic_rule.png"), p)
# ic_score: star
p = canvas()
for y in range(H):
    for x in range(W):
        import math
        dx, dy = x - 16, y - 17
        if dx * dx + dy * dy <= 100 and (abs(dx) < 3 or abs(dy) < 3 or abs(abs(dx) - abs(dy)) < 3):
            p[y * W + x] = (250, 210, 70, 255)
png(os.path.join(OUT, "ic_score.png"), p)
# ic_fx: sparkle
p = canvas(); diamond(p, 16, 16, 4, (255, 255, 255, 255)); rect(p, 14, 4, 18, 28, (255, 235, 140, 255)); rect(p, 4, 14, 28, 18, (255, 235, 140, 255)); diamond(p, 16, 16, 5, (255, 245, 190, 255))
png(os.path.join(OUT, "ic_fx.png"), p)
# ic_text: chat bubble
p = canvas(); rect(p, 5, 8, 27, 21, (235, 235, 240, 255)); rect(p, 9, 21, 13, 26, (235, 235, 240, 255))
rect(p, 8, 12, 24, 14, (90, 95, 110, 255)); rect(p, 8, 16, 19, 18, (90, 95, 110, 255))
png(os.path.join(OUT, "ic_text.png"), p)
# ic_weather: sun + cloud
p = canvas(); disc(p, 11, 11, 5, (250, 210, 80, 255)); rect(p, 12, 16, 27, 24, (220, 225, 235, 255)); disc(p, 17, 16, 5, (220, 225, 235, 255)); disc(p, 23, 17, 4, (220, 225, 235, 255))
png(os.path.join(OUT, "ic_weather.png"), p)
# ic_cmd: command block (orange, >_)
p = canvas(); rect(p, 5, 5, 27, 27, (200, 120, 40, 255)); rect(p, 7, 7, 25, 25, (230, 145, 60, 255))
rect(p, 10, 11, 12, 16, (40, 30, 25, 255)); rect(p, 12, 13, 16, 15, (40, 30, 25, 255)); rect(p, 12, 18, 20, 20, (40, 30, 25, 255))
png(os.path.join(OUT, "ic_cmd.png"), p)
# ic_misc: wrench-ish / slash
p = canvas(); rect(p, 9, 9, 23, 23, (140, 145, 155, 255)); rect(p, 13, 5, 19, 27, (170, 175, 185, 255)); rect(p, 5, 13, 27, 19, (170, 175, 185, 255)); disc(p, 16, 16, 4, (24, 26, 32, 255))
png(os.path.join(OUT, "ic_misc.png"), p)
print("icons written:", sorted(os.listdir(OUT)))
