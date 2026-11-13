#!/usr/bin/env python3
"""
Generates the 'soot_and_tar' (dieselpunk smog) vision's stand-in textures from a reference resource
pack: the reference's shading gradient-mapped onto soot/tar palettes, then dusted with ash flecks,
cinder specks and glossy tar.

usage: python3 tools/gen_soot.py <path/to/pack/assets/minecraft> [out_dir]

Only ground and plants are generated. Wood, leaves, stone, metal, glass, cloth and crops are
recoloured at load time by the vision's recolor_sets, so every modded variant follows automatically.
"""
import os, random, sys
from PIL import Image

REF = sys.argv[1] if len(sys.argv) > 1 else 'minecraft'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'src/main/resources/wastelandvision_defaults/visions/soot_and_tar/assets/soot/textures/block'
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
def put(px, x, y, c):
    if px[x % 16, y % 16][3]: px[x % 16, y % 16] = c + (255,)
def speckle(im, colours, chance, rng, only_opaque=True):
    px = im.load()
    for y in range(16):
        for x in range(16):
            if px[x, y][3] and rng.random() < chance: px[x, y] = rng.choice(colours) + (255,)
    return im
def save(im, name): im.save(os.path.join(OUT, name + '.png'))

# --- palettes (dark -> light) ---
TURF    = pal('#15140e', '#24231a', '#353425', '#4a4833', '#615e44')   # soot-choked grass, grey-olive
SOIL    = pal('#100d0a', '#1b1611', '#28211a', '#372d23', '#463a2e')   # oily black-brown earth
MUD     = pal('#080706', '#120f0c', '#1e1914', '#2b241d', '#3a3128')   # tar-soaked mud
MOSS    = pal('#111410', '#1c2019', '#292f24', '#373f30', '#46503d')
GRASS   = pal('#3e3a2c', '#5a5440', '#7a7256', '#998f6c', '#b4a982')   # dry, dusty straw-grey: reads on dark ground
WEED    = pal('#3a2c1f', '#564230', '#735a42', '#8f7356', '#a88b6a')
PETAL   = pal('#4a1e12', '#6e2f1c', '#8f4428', '#ab5c36', '#c27748')   # dull, sooted rust-red
THORN   = pal('#050404', '#0e0c0b', '#1a1714', '#28231f', '#38312b')
ASH     = pal('#5f5b55', '#77726a', '#8e897f')
CINDER  = pal('#4a2a18', '#5c3520')
TAR     = (6, 5, 4)
GLOSS   = [(96, 90, 82), (120, 112, 100)]

r = random.Random(1923)

# Ground -----------------------------------------------------------------------------------------
soil = speckle(remap(ref('dirt.png'), SOIL, contrast=0.6, bias=0.02), CINDER, 0.035, r)
save(soil, 'soot_soil')

turf = remap(ref('grass_block_top.png'), TURF, gamma=1.1, contrast=0.65)
speckle(turf, ASH, 0.07, r)                     # ash settled on the grass
speckle(turf, [(10, 9, 7)], 0.05, r)            # soot specks
save(turf, 'sooted_turf_top')

side = soil.copy(); sp = side.load(); ov = ref('grass_block_side_overlay.png'); op = ov.load(); on = norm(ov)
for y in range(16):
    for x in range(16):
        if op[x, y][3] > 0: sp[x, y] = gmap(0.5 + (on(op[x, y]) - 0.5) * 0.6, TURF) + (255,)
for x in range(16):                             # thin ash crust along the top edge
    if r.random() < 0.55: sp[x, 0] = r.choice(ASH) + (255,)
save(side, 'sooted_turf_side')

mud = remap(ref('mud.png'), MUD, contrast=0.6); mp = mud.load()
for _ in range(4):                              # tar puddles with a glossy rim on the light side
    cx, cy, rad = r.randrange(16), r.randrange(16), r.choice([1.6, 2.2, 2.8])
    for y in range(-3, 4):
        for x in range(-3, 4):
            d = (x * x + y * y) ** 0.5
            if d <= rad: put(mp, cx + x, cy + y, TAR)
            if rad - 0.9 < d <= rad and x < 0 and y < 0: put(mp, cx + x, cy + y, r.choice(GLOSS))
save(mud, 'tar_mud')

save(speckle(remap(ref('moss_block.png'), MOSS, contrast=0.7), ASH[:1], 0.04, r), 'soot_moss')

# Plants ------------------------------------------------------------------------------------------
def ash_tips(im, chance=0.5):
    """Pale ash on the uppermost opaque pixel of each column."""
    px = im.load()
    for x in range(16):
        for y in range(16):
            if px[x, y][3]:
                if r.random() < chance: px[x, y] = r.choice(ASH) + (255,)
                break
    return im

save(ash_tips(remap(ref('short_grass.png'), GRASS, contrast=1.0)), 'soot_grass')
save(ash_tips(remap(ref('fern.png'), WEED, contrast=1.0), 0.3), 'cinder_weed')
save(remap(ref('tall_grass_bottom.png'), GRASS, contrast=1.0), 'soot_tall_grass_bottom')
save(ash_tips(remap(ref('tall_grass_top.png'), GRASS, contrast=1.0)), 'soot_tall_grass_top')

# Choked flower: a poppy whose petals have gone dull rust under a crust of soot.
im = ref('poppy.png'); px = im.load(); n = norm(im)
for y in range(16):
    for x in range(16):
        q = px[x, y]
        if q[3] == 0: continue
        if q[0] > q[1] + 40:                        # red petals
            c = gmap(0.25 + n(q) * 0.6, PETAL)
            if r.random() < 0.25: c = (14, 12, 10)  # soot crust
            px[x, y] = c + (255,)
        else:
            px[x, y] = gmap(n(q) * 0.85, GRASS) + (255,)
save(im, 'choked_flower')

# Tar thorns: the hazard marker (berry bushes, modded thorns...) - black brambles beaded with tar.
im = remap(ref('sweet_berry_bush_stage1.png'), THORN, contrast=1.0); px = im.load()
for y in range(16):
    for x in range(16):
        if px[x, y][3] and r.random() < 0.08: px[x, y] = r.choice(GLOSS) + (255,)
save(im, 'tar_thorns')
print('soot_and_tar textures written to', OUT)
