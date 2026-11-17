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

# ---- detailed content icons (v2.6) ----
def line(px, x0, y0, x1, y1, c, w=2):
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx = 1 if x0 < x1 else -1; sy = 1 if y0 < y1 else -1
    err = dx + dy
    x, y = x0, y0
    while True:
        rect(px, x - w // 2, y - w // 2, x - w // 2 + w, y - w // 2 + w, c)
        if x == x1 and y == y1: break
        e2 = 2 * err
        if e2 >= dy: err += dy; x += sx
        if e2 <= dx: err += dx; y += sy

# sword: diagonal blade + hilt
p = canvas(); line(p, 22, 8, 10, 20, (200, 210, 220, 255), 3); line(p, 22, 8, 25, 5, (235, 245, 250, 255), 2)
line(p, 8, 18, 13, 23, (139, 90, 43, 255), 3); rect(p, 13, 17, 18, 20, (90, 60, 30, 255))
png(os.path.join(OUT, "ic_sword.png"), p)
# pickaxe
p = canvas(); line(p, 12, 22, 20, 8, (139, 90, 43, 255), 3); line(p, 8, 10, 24, 14, (190, 200, 210, 255), 3)
png(os.path.join(OUT, "ic_pick.png"), p)
# axe
p = canvas(); line(p, 12, 24, 18, 8, (139, 90, 43, 255), 3); rect(p, 16, 6, 25, 13, (190, 200, 210, 255)); rect(p, 22, 13, 25, 16, (160, 170, 180, 255))
png(os.path.join(OUT, "ic_axe.png"), p)
# shovel/hoe tool
p = canvas(); line(p, 13, 22, 19, 9, (139, 90, 43, 255), 3); rect(p, 10, 20, 17, 27, (180, 190, 200, 255))
png(os.path.join(OUT, "ic_tool.png"), p)
# armor chestplate
p = canvas(); rect(p, 8, 8, 24, 24, (120, 125, 135, 255)); rect(p, 11, 11, 21, 24, (150, 156, 168, 255))
rect(p, 8, 8, 13, 12, (24, 26, 32, 255)); rect(p, 19, 8, 24, 12, (24, 26, 32, 255))
png(os.path.join(OUT, "ic_armor.png"), p)
# bow
p = canvas()
for t in range(28):
    import math
    a = -1.1 + t * (2.2 / 28); x = int(18 + 8 * math.cos(a)); y = int(16 + 9 * math.sin(a))
    rect(p, x - 1, y - 1, x + 1, y + 1, (139, 90, 43, 255))
line(p, 22, 8, 22, 24, (220, 220, 225, 255), 1); line(p, 22, 16, 10, 16, (190, 200, 210, 255), 1); diamond(p, 9, 16, 2, (200, 210, 220, 255))
png(os.path.join(OUT, "ic_bow.png"), p)
# trident
p = canvas(); line(p, 16, 26, 16, 10, (60, 170, 160, 255), 2); line(p, 16, 10, 10, 6, (60, 170, 160, 255), 2); line(p, 16, 10, 22, 6, (60, 170, 160, 255), 2); line(p, 16, 10, 16, 4, (90, 210, 200, 255), 2)
png(os.path.join(OUT, "ic_trident.png"), p)
# shield
p = canvas(); rect(p, 9, 6, 23, 18, (150, 100, 50, 255)); rect(p, 11, 8, 21, 16, (170, 170, 180, 255))
for i in range(7): rect(p, 12 + i // 2, 18 + i, 20 - i // 2, 19 + i, (150, 100, 50, 255))
png(os.path.join(OUT, "ic_shield.png"), p)
# food drumstick
p = canvas(); disc(p, 13, 13, 7, (190, 120, 70, 255)); disc(p, 12, 12, 4, (215, 145, 90, 255)); line(p, 17, 18, 24, 25, (235, 230, 220, 255), 3); disc(p, 25, 26, 2, (235, 230, 220, 255))
png(os.path.join(OUT, "ic_food.png"), p)
# wool swatch
p = canvas(); rect(p, 7, 8, 25, 24, (200, 60, 80, 255))
for y in range(8, 24, 3): line(p, 7, y, 25, y, (170, 45, 62, 255), 1)
png(os.path.join(OUT, "ic_wool.png"), p)
# wood log
p = canvas(); rect(p, 8, 6, 24, 26, (110, 80, 50, 255)); rect(p, 11, 6, 21, 9, (180, 145, 95, 255)); rect(p, 14, 6, 18, 8, (140, 105, 65, 255))
for x in (11, 15, 19): line(p, x, 10, x, 26, (85, 60, 35, 255), 1)
png(os.path.join(OUT, "ic_wood.png"), p)
# ore (diamond ore block)
p = canvas(); rect(p, 6, 6, 26, 26, (125, 128, 132, 255))
for gx, gy in [(11, 11), (20, 9), (15, 18), (22, 21), (9, 20)]: diamond(p, gx, gy, 2, (90, 230, 240, 255))
png(os.path.join(OUT, "ic_ore.png"), p)
# rail
p = canvas(); line(p, 8, 6, 8, 26, (120, 80, 45, 255), 2); line(p, 24, 6, 24, 26, (120, 80, 45, 255), 2)
for y in (9, 16, 23): line(p, 8, y, 24, y, (180, 185, 195, 255), 2)
png(os.path.join(OUT, "ic_rail.png"), p)
# bed
p = canvas(); rect(p, 5, 14, 27, 23, (150, 40, 40, 255)); rect(p, 5, 10, 11, 14, (235, 235, 240, 255)); rect(p, 5, 23, 7, 27, (90, 60, 30, 255)); rect(p, 25, 23, 27, 27, (90, 60, 30, 255))
png(os.path.join(OUT, "ic_bed.png"), p)
# music disc
p = canvas(); disc(p, 16, 16, 10, (30, 30, 34, 255)); disc(p, 16, 16, 4, (200, 200, 210, 255)); disc(p, 16, 16, 2, (24, 26, 32, 255))
png(os.path.join(OUT, "ic_disc.png"), p)
# spawn egg hostile (red) / passive (green)
for nm, base, dot in [("ic_egg_red", (200, 70, 60, 255), (120, 30, 25, 255)), ("ic_egg_green", (90, 180, 90, 255), (40, 100, 40, 255))]:
    p = canvas()
    for y in range(H):
        for x in range(W):
            ex = (x - 16) / 8.5; ey = (y - 17) / 10.5
            if ex * ex + ey * ey <= 1: p[y * W + x] = base
    import random
    r = random.Random(7)
    for _ in range(9):
        x = r.randint(10, 22); y = r.randint(9, 24)
        if ((x - 16) / 8.5) ** 2 + ((y - 17) / 10.5) ** 2 <= 0.8: rect(p, x, y, x + 2, y + 2, dot)
    png(os.path.join(OUT, nm + ".png"), p)
# bucket
p = canvas(); rect(p, 9, 10, 23, 13, (170, 175, 185, 255)); line(p, 9, 13, 12, 25, (150, 155, 165, 255), 2); line(p, 23, 13, 20, 25, (150, 155, 165, 255), 2); rect(p, 12, 24, 20, 26, (170, 175, 185, 255)); rect(p, 11, 13, 21, 16, (70, 140, 220, 255))
png(os.path.join(OUT, "ic_bucket.png"), p)
# torch/light
p = canvas(); rect(p, 14, 14, 18, 27, (120, 80, 40, 255)); diamond(p, 16, 10, 5, (255, 200, 60, 255)); diamond(p, 16, 9, 2, (255, 245, 180, 255))
png(os.path.join(OUT, "ic_light.png"), p)
# glass
p = canvas(); rect(p, 7, 7, 25, 25, (190, 215, 230, 130)); line(p, 10, 22, 22, 10, (240, 250, 255, 255), 2); line(p, 14, 24, 24, 14, (220, 240, 250, 255), 1)
png(os.path.join(OUT, "ic_glass.png"), p)
# elytra (wings)
p = canvas()
for i in range(9):
    rect(p, 8 - i // 2, 8 + i, 12, 10 + i, (200, 205, 215, 255))
    rect(p, 20, 8 + i, 24 + i // 2, 10 + i, (200, 205, 215, 255))
rect(p, 14, 8, 18, 14, (160, 165, 175, 255))
png(os.path.join(OUT, "ic_elytra.png"), p)
# hostile summon (red creeper-ish)
p = canvas(); rect(p, 6, 6, 26, 26, (120, 40, 40, 255))
rect(p, 9, 11, 13, 15, (20, 8, 8, 255)); rect(p, 19, 11, 23, 15, (20, 8, 8, 255))
rect(p, 14, 15, 18, 23, (20, 8, 8, 255)); rect(p, 11, 19, 14, 24, (20, 8, 8, 255)); rect(p, 18, 19, 21, 24, (20, 8, 8, 255))
png(os.path.join(OUT, "ic_summon_red.png"), p)
print("detailed icons:", len(os.listdir(OUT)))
# More specific building/utility motifs, drawn locally.
p=canvas();rect(p,10,9,22,27,(110,72,40,255));rect(p,12,11,20,25,(160,108,55,255));rect(p,17,18,19,20,(245,206,78,255));png(os.path.join(OUT,'ic_door.png'),p)
p=canvas();rect(p,5,19,27,26,(145,150,160,255));rect(p,12,12,27,19,(160,167,175,255));rect(p,20,6,27,12,(185,193,200,255));png(os.path.join(OUT,'ic_stairs.png'),p)
p=canvas();rect(p,14,15,18,28,(114,76,43,255));disc(p,16,12,9,(40,125,48,255));disc(p,12,11,5,(78,164,65,255));png(os.path.join(OUT,'ic_tree.png'),p)
p=canvas();rect(p,15,13,17,28,(55,160,60,255));line(p,16,23,9,19,(80,175,67,255));disc(p,16,10,6,(220,65,95,255));disc(p,16,10,2,(250,215,84,255));png(os.path.join(OUT,'ic_flower.png'),p)
p=canvas();rect(p,7,16,25,24,(123,77,37,255));rect(p,10,23,22,26,(99,60,31,255));line(p,7,19,3,12,(160,113,61,255),2);line(p,25,19,29,12,(160,113,61,255),2);png(os.path.join(OUT,'ic_boat.png'),p)
p=canvas();rect(p,7,13,25,21,(214,158,47,255));rect(p,10,10,22,13,(253,216,92,255));line(p,7,21,25,21,(145,93,20,255),2);png(os.path.join(OUT,'ic_ingot.png'),p)
p=canvas();diamond(p,16,17,8,(180,25,35,255));diamond(p,14,14,3,(246,66,73,255));line(p,6,25,12,21,(125,25,25,255),2);line(p,22,20,28,23,(125,25,25,255),2);png(os.path.join(OUT,'ic_redstone.png'),p)
# Pixel shading: exposed edges and modest checker highlights preserve a readable small sprite.
from PIL import Image
for name in os.listdir(OUT):
    if not name.endswith('.png'):continue
    im=Image.open(os.path.join(OUT,name)).convert('RGBA');orig=im.copy();pixels=im.load();bg=(24,26,32)
    for y in range(1,31):
        for x in range(1,31):
            r,g,b,a=orig.getpixel((x,y))
            if (r,g,b)==bg:continue
            above=orig.getpixel((x,y-1))[:3];below=orig.getpixel((x,y+1))[:3]
            factor=1.15 if above==bg else .72 if below==bg else 1.03 if (x+y)%4==0 else 1
            pixels[x,y]=(min(255,int(r*factor)),min(255,int(g*factor)),min(255,int(b*factor)),a)
    im.save(os.path.join(OUT,name))
print('Shaded motifs:',len(os.listdir(OUT)))

# Material-specific tools/armor and dye-specific building items. Original procedural artwork.
def read_generated(name):
    return list(Image.open(os.path.join(OUT,name+'.png')).convert('RGBA').getdata())
materials={'wooden':(160,116,64),'stone':(124,128,135),'iron':(205,215,224),'golden':(247,201,58),'diamond':(58,221,212),'netherite':(93,77,87),'leather':(161,92,53),'chainmail':(137,150,162),'gold':(247,201,58)}
# Distinct silhouettes for each armor slot.
p=canvas();rect(p,8,8,24,23,(155,160,172,255));rect(p,11,13,21,23,(24,26,32,255));rect(p,9,10,23,12,(205,211,224,255));png(os.path.join(OUT,'ic_helmet.png'),p)
p=canvas();rect(p,10,6,22,16,(155,160,172,255));rect(p,10,16,15,26,(155,160,172,255));rect(p,17,16,22,26,(155,160,172,255));line(p,12,8,12,24,(205,211,224,255),1);png(os.path.join(OUT,'ic_leggings.png'),p)
p=canvas();rect(p,8,10,14,24,(155,160,172,255));rect(p,18,10,24,24,(155,160,172,255));rect(p,5,21,14,26,(205,211,224,255));rect(p,18,21,27,26,(205,211,224,255));png(os.path.join(OUT,'ic_boots.png'),p)
for name,rgb in materials.items():
    for motif in ('sword','pick','axe','tool','armor','helmet','leggings','boots'):
        px=read_generated('ic_'+motif);out=[]
        for r,g,b,a in px:
            # Gray metal only: preserve wooden handles, outlines and background.
            if min(r,g,b)>60 and max(r,g,b)-min(r,g,b)<40:
                light=(r+g+b)/3/190;out.append(tuple(min(255,int(v*light)) for v in rgb)+(a,))
            else:out.append((r,g,b,a))
        png(os.path.join(OUT,'ic_'+motif+'_'+name+'.png'),out)
dyes={'white':(225,226,221),'orange':(230,124,38),'magenta':(190,63,177),'light_blue':(86,173,211),'yellow':(239,205,59),'lime':(123,195,44),'pink':(234,142,171),'gray':(72,77,82),'light_gray':(161,163,154),'cyan':(31,142,153),'purple':(130,60,178),'blue':(61,73,163),'brown':(120,82,54),'green':(81,112,33),'red':(192,54,62),'black':(43,45,51)}
for name,rgb in dyes.items():
    for motif in ('wool','glass','bed'):
        px=read_generated('ic_'+motif);out=[]
        for r,g,b,a in px:
            matched=(motif=='wool' and r>g*1.5) or (motif=='bed' and r>g*1.5) or (motif=='glass' and b>120 and r<220)
            out.append(tuple(min(255,int(v*(.85 if (r+g+b)%3==0 else 1))) for v in rgb)+(a,) if matched else (r,g,b,a))
        png(os.path.join(OUT,'ic_'+motif+'_'+name+'.png'),out)
# Distinct symbols for the most-used actions.
p=canvas();disc(p,16,16,7,(248,206,60,255))
for x,y in ((16,4),(16,28),(4,16),(28,16),(7,7),(25,7),(7,25),(25,25)):line(p,int(16+(x-16)*.7),int(16+(y-16)*.7),x,y,(242,174,43,255),2)
png(os.path.join(OUT,'ic_day.png'),p)
p=canvas();disc(p,16,16,11,(221,227,239,255));disc(p,21,11,9,(24,26,32,255));rect(p,23,22,25,24,(247,212,93,255));png(os.path.join(OUT,'ic_night.png'),p)
p=canvas();disc(p,12,11,6,(170,190,210,255));disc(p,20,12,7,(190,205,220,255));rect(p,6,12,27,17,(190,205,220,255))
for x in (9,16,23):line(p,x,21,x-2,26,(69,158,238,255),2)
png(os.path.join(OUT,'ic_rain.png'),p)
p=canvas();disc(p,11,12,5,(229,56,80,255));disc(p,21,12,5,(229,56,80,255))
for y in range(13,26):rect(p,5+(y-13)//2,y,27-(y-13)//2,y+1,(209,40,66,255))
rect(p,8,8,11,11,(255,154,165,255));png(os.path.join(OUT,'ic_health.png'),p)
p=canvas();line(p,5,16,25,16,(112,228,236,255),4);line(p,20,9,26,16,(112,228,236,255),3);line(p,20,23,26,16,(112,228,236,255),3)
for y in (8,24):line(p,6,y,13,y,(90,146,188,255),2)
png(os.path.join(OUT,'ic_speed.png'),p)
print('Detailed assets total:',len(os.listdir(OUT)))
