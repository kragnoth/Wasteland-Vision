#!/usr/bin/env python3
"""
Generates the 'wasteland' (the Zone - S.T.A.L.K.E.R. Call of Pripyat golden autumn) vision's textures
from a reference resource pack: the reference's shading gradient-mapped onto Zone palettes.

usage: python3 tools/gen_zone.py <path/to/pack/assets/minecraft> [out_dir]
"""
import os, random, sys
from PIL import Image

REF = sys.argv[1] if len(sys.argv) > 1 else 'minecraft'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'extras/visions/wasteland/assets/zone/textures/block'
os.makedirs(OUT, exist_ok=True)

def ref(name): return Image.open(os.path.join(REF, 'textures/block', name)).convert('RGBA').crop((0, 0, 16, 16))
def pal(*c): return [tuple(int(x[i:i+2], 16) for i in (1, 3, 5)) for x in c]
def lum(p): return (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]) / 255.0
def gmap(l, p):
    l = max(0.0, min(1.0, l)) * (len(p) - 1); i = min(int(l), len(p) - 2); t = l - i
    return tuple(int(p[i][k] + (p[i+1][k] - p[i][k]) * t + 0.5) for k in range(3))
def norm(img):
    px = img.load(); ls = [lum(px[x, y]) for y in range(16) for x in range(16) if px[x, y][3] > 0]
    lo, hi = (min(ls), max(ls)) if ls else (0, 1)
    return lambda q: (lum(q) - lo) / (hi - lo if hi > lo else 1)
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

# --- Call of Pripyat palettes (dark -> light) ---
BARK       = pal('#1b1915', '#302c25', '#4a443a', '#675f51', '#857c6a')   # weathered grey-brown
RINGS      = pal('#24201a', '#423a2f', '#625747', '#82755f', '#a09178')
BIRCH      = pal('#121212', '#3d3d3a', '#8c8a82', '#bdbab0', '#dcd8cc')   # bleached, keeps black marks
RED_BARK   = pal('#1a0e0a', '#33190f', '#4f2716', '#6e3a1f', '#8c4f2c')   # Red Forest pines
STRIPPED   = pal('#2c2821', '#4a443a', '#6e665a', '#958b7c', '#b4a997')
YELLOW     = pal('#2a2208', '#5a4a10', '#9a7f1c', '#c9a52c', '#e8c64a')
ORANGE     = pal('#2a1406', '#5c2c0c', '#9a4c16', '#c86e22', '#e0913a')
BROWN      = pal('#1e160d', '#3d2c19', '#5e4426', '#7d5c35', '#987349')
RUST       = pal('#1c0c06', '#3d1a0b', '#663012', '#8c4a1e', '#ad6a30')
PLANK      = pal('#1c1b19', '#33312d', '#4f4c46', '#6d6962', '#8a857c')   # sun-greyed boards
TURF       = pal('#2c2a12', '#4f4a1c', '#7a6f2a', '#a39338', '#c7b450')   # golden dry turf
SOIL       = pal('#17140f', '#2a241b', '#3f362a', '#56493a', '#6e5e4b')
MUD        = pal('#151310', '#2a2620', '#423c33', '#5d554a', '#7a7063')
DRY_MOSS   = pal('#1e2010', '#33361b', '#4d5129', '#686d37', '#848a47')
GOLD_GRASS = pal('#3a3312', '#665a1e', '#9a872c', '#c4ad3c', '#e3cb58')
OLIVE_GOLD = pal('#2a2c12', '#4d521e', '#78802c', '#a0a63c', '#c2c652')
RUST_FERN  = pal('#2a1508', '#552c10', '#874a1c', '#b0682a', '#cf8a3c')
FLUFF      = pal('#6b665a', '#8f897b', '#b3ad9e', '#d1ccbe', '#ebe7dc')

r = random.Random(1986)

def log_pair(side, top, pside, ptop, name, splits=3):
    im = remap(ref(side), pside, contrast=0.75); px = im.load()
    for _ in range(splits):
        x, y0 = r.randrange(16), r.randrange(16)
        for k in range(r.randrange(3, 7)): shade(px, x, y0 + k, 0.6)
    save(im, name)
    t = remap(ref(top), ptop, contrast=0.7); tp = t.load()
    for y in range(6, 10):
        for x in range(6, 10):
            if r.random() < 0.5: shade(tp, x, y, 0.6)
    save(t, name + '_top')

# Logs, leaves and planks are NOT generated: the vision's recolor_sets recolour every species at load time.

soil = remap(ref('dirt.png'), SOIL, contrast=0.6, bias=0.03); save(soil, 'zone_soil')
save(remap(ref('grass_block_top.png'), TURF, gamma=1.1, contrast=0.6, bias=0.02), 'zone_turf_top')
side = soil.copy(); sp = side.load(); ov = ref('grass_block_side_overlay.png'); op = ov.load(); on = norm(ov)
for y in range(16):
    for x in range(16):
        if op[x, y][3] > 0: sp[x, y] = gmap(0.5 + (on(op[x, y]) - 0.5) * 0.6, TURF) + (255,)
save(side, 'zone_turf_side')
save(remap(ref('coarse_dirt.png'), MUD, contrast=0.65), 'zone_mud')
save(remap(ref('moss_block.png'), DRY_MOSS, contrast=0.7), 'dry_moss')

save(remap(ref('short_grass.png'), GOLD_GRASS, contrast=1.0), 'golden_grass')
save(remap(ref('short_grass1.png'), OLIVE_GOLD, contrast=1.0), 'olive_grass')
save(remap(ref('tall_grass_bottom.png'), GOLD_GRASS, contrast=1.0), 'golden_tall_grass_bottom')
save(remap(ref('tall_grass_top.png'), GOLD_GRASS, contrast=1.0), 'golden_tall_grass_top')
save(remap(ref('fern.png'), RUST_FERN, contrast=1.0), 'rust_fern')

# Seed heads: dandelion -> gone-to-seed fluff on a straw stem
im = ref('dandelion.png'); px = im.load(); n = norm(im)
for y in range(16):
    for x in range(16):
        q = px[x, y]
        if q[3] == 0: continue
        if q[0] > q[2] + 40 and q[1] > q[2] + 40:      # yellow petals -> pale fluff
            px[x, y] = gmap(0.4 + n(q) * 0.6, FLUFF) + (255,)
        else:
            px[x, y] = gmap(n(q) * 0.9, GOLD_GRASS) + (255,)
save(im, 'seed_head')
print('zone textures written to', OUT)
