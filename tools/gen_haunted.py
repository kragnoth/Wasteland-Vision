#!/usr/bin/env python3
"""
Generates the 'haunted' example vision's textures from a reference resource pack (same technique as
gen_textures.py: gradient-map the reference's shading onto cold, near-colourless spirit palettes).

usage: python3 tools/gen_haunted.py <path/to/pack/assets/minecraft> [out_dir]
"""
import os, random, sys
from PIL import Image

REF = sys.argv[1] if len(sys.argv) > 1 else 'minecraft'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'extras/visions/haunted/assets/haunted/textures/block'
os.makedirs(OUT, exist_ok=True)

def ref(name):
    return Image.open(os.path.join(REF, 'textures/block', name)).convert('RGBA').crop((0, 0, 16, 16))
def hexrgb(h): return tuple(int(h[i:i+2], 16) for i in (1, 3, 5))
def pal(*c): return [hexrgb(x) for x in c]
def lum(p): return (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]) / 255.0
def gmap(l, p):
    l = max(0.0, min(1.0, l)) * (len(p) - 1); i = min(int(l), len(p) - 2); t = l - i
    return tuple(int(p[i][k] + (p[i+1][k] - p[i][k]) * t + 0.5) for k in range(3))
def norm(img):
    px = img.load(); ls = [lum(px[x, y]) for y in range(16) for x in range(16) if px[x, y][3] > 0]
    lo, hi = (min(ls), max(ls)) if ls else (0, 1)
    return lambda p: (lum(p) - lo) / (hi - lo if hi > lo else 1)
def remap(img, p, gamma=1.0, bias=0.0, contrast=0.8):
    im = img.copy(); px = im.load(); n = norm(img)
    for y in range(16):
        for x in range(16):
            q = px[x, y]
            if q[3] == 0: continue
            px[x, y] = gmap(0.5 + ((n(q) ** gamma) - 0.5) * contrast + bias, p) + (255,)
    return im
def shade(px, x, y, f):
    r, g, b, a = px[x % 16, y % 16]
    if a: px[x % 16, y % 16] = (int(r * f), int(g * f), int(b * f), a)
def save(im, name): im.save(os.path.join(OUT, name + '.png'))

# Palettes: shadows lean blue, highlights stay pale and colourless - the shader adds the rest.
GRAVE_BARK  = pal('#0b0d11', '#181c23', '#272d36', '#3b434f', '#535c69')
BONE        = pal('#262a31', '#454b54', '#6a7079', '#90969e', '#b6bbc1')
GHOST_LEAF  = pal('#11151b', '#212833', '#353e4b', '#4d5765', '#6b7684')
GRAVE_PLANK = pal('#0d0f13', '#1a1e25', '#2a2f37', '#3c434d', '#525a65')
GRAVE_SOIL  = pal('#111214', '#1e2023', '#2d2f33', '#3f3d3d', '#534e4a')
DEAD_TURF   = pal('#1b2026', '#2c333b', '#414952', '#576069', '#6f7880')
GRAVE_MOSS  = pal('#141a1d', '#243034', '#354449', '#48595e', '#5e7075')
DEAD_STRAW  = pal('#2a2e34', '#474c53', '#676c73', '#898e94', '#aaaeb3')
DYING       = pal('#202723', '#363f38', '#4f5a4d', '#6a7564', '#86917c')
BLACK_PETAL = pal('#09090b', '#16161b', '#25242b', '#35333b', '#47434d')
BLIGHT      = pal('#171512', '#2b2721', '#443d34', '#5e5548', '#7b705f')

r = random.Random(1666)

# Gravewood: near-black bark with long splits; stripped = bone-pale dead grain.
log = remap(ref('oak_log.png'), GRAVE_BARK, contrast=0.8); px = log.load()
for _ in range(3):
    x, y0 = r.randrange(16), r.randrange(16)
    for k in range(r.randrange(4, 8)): shade(px, x, y0 + k, 0.5)
save(log, 'gravewood_log')
top = remap(ref('oak_log_top.png'), GRAVE_BARK, contrast=0.75); px = top.load()
for y in range(6, 10):
    for x in range(6, 10):
        if r.random() < 0.75: shade(px, x, y, 0.4)
save(top, 'gravewood_log_top')
sl = remap(ref('stripped_oak_log.png'), BONE, contrast=0.6); px = sl.load()
for _ in range(4): shade(px, r.randrange(16), r.randrange(16), 0.5)
save(sl, 'gravewood_stripped_log')
st = remap(ref('stripped_oak_log_top.png'), BONE, contrast=0.65); px = st.load()
for y in range(6, 10):
    for x in range(6, 10):
        if r.random() < 0.6: shade(px, x, y, 0.55)
save(st, 'gravewood_stripped_log_top')

# Ghost leaves: ashen clumps, a third knocked out in 4x4 clumps (keeps the pack's chunky look).
lv = remap(ref('oak_leaves.png'), GHOST_LEAF, contrast=0.9); px = lv.load()
for by in range(0, 16, 4):
    for bx in range(0, 16, 4):
        if r.random() < 0.33:
            for dy in range(4):
                for dx in range(4):
                    if r.random() < 0.8: px[bx + dx, by + dy] = (0, 0, 0, 0)
save(lv, 'ghost_leaves')

# Grave planks: dark slate wood from the pack's dark oak planks, with rot pits.
pl = remap(ref('dark_oak_planks.png'), GRAVE_PLANK, contrast=0.8, bias=0.05); px = pl.load()
for _ in range(6): shade(px, r.randrange(16), r.randrange(16), 0.45)
save(pl, 'grave_planks')

# Ground: one shared soil base so dead turf sides and grave soil always match.
soil = remap(ref('dirt.png'), GRAVE_SOIL, contrast=0.6, bias=0.03)
save(soil, 'grave_soil')
turf = remap(ref('grass_block_top.png'), DEAD_TURF, gamma=1.15, contrast=0.55, bias=-0.05)
save(turf, 'dead_grass_block_top')
side = soil.copy(); sp = side.load(); ov = ref('grass_block_side_overlay.png'); op = ov.load(); n = norm(ov)
for y in range(16):
    for x in range(16):
        if op[x, y][3] > 0: sp[x, y] = gmap(0.5 + (n(op[x, y]) - 0.5) * 0.6 - 0.05, DEAD_TURF) + (255,)
save(side, 'dead_grass_block_side')
save(remap(ref('moss_block.png'), GRAVE_MOSS, contrast=0.7), 'grave_moss')

# Plants.
save(remap(ref('short_grass.png'), DEAD_STRAW, contrast=1.0), 'dead_grass')
save(remap(ref('short_grass1.png') if os.path.exists(os.path.join(REF, 'textures/block/short_grass1.png')) else ref('short_grass.png'),
           DYING, contrast=1.0), 'dying_grass')
save(remap(ref('fern.png'), DEAD_STRAW, contrast=1.0, bias=-0.08), 'dead_fern')
save(remap(ref('tall_grass_bottom.png'), DEAD_STRAW, contrast=1.0), 'dead_tall_grass_bottom')
save(remap(ref('tall_grass_top.png'), DEAD_STRAW, contrast=1.0), 'dead_tall_grass_top')
for half in ('bottom', 'top'):          # rose bush -> blackened, petal-dropped stalks
    im = ref(f'rose_bush_{half}.png'); px = im.load(); n = norm(im)
    for y in range(16):
        for x in range(16):
            q = px[x, y]
            if q[3] == 0: continue
            if q[0] > q[1] + 25:
                px[x, y] = (0, 0, 0, 0) if r.random() < 0.35 else gmap(n(q), BLACK_PETAL) + (255,)
            else:
                px[x, y] = gmap(n(q) * 0.85, DEAD_STRAW) + (255,)
    save(im, f'withered_tall_flower_{half}')
save(remap(ref('wheat_stage7.png'), BLIGHT, contrast=1.0), 'withered_crop')
print('haunted textures written to', OUT)
