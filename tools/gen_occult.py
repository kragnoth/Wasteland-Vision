#!/usr/bin/env python3
"""
Stand-in textures for the 'occult_foundry' vision (occult steampunk/dieselpunk: spirits, brass and
copper machines, concrete, rusted iron, tar).

usage: python3 tools/gen_occult.py <path/to/pack/assets/minecraft> [out_dir]

Only four stand-ins are drawn here; everything else in the vision is a load-time recolour.
  tar_pool      - mud gone to black pitch with an oily sheen (Tarbound's tar seeping up)
  tar_soil      - its side/bottom: earth soaked dark
  wisp_bloom    - a poppy turned spirit flower: pale, cold, faintly luminous petals
  brass_thorns  - the hazard marker: verdigris-crusted brass wire brambles
"""
import os, random, sys
from PIL import Image

REF = sys.argv[1] if len(sys.argv) > 1 else 'minecraft'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'src/main/resources/wastelandvision_defaults/visions/occult_foundry/assets/occult/textures/block'
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
def remap(img, p, contrast=0.8, bias=0.0):
    im = img.copy(); px = im.load(); n = norm(img)
    for y in range(16):
        for x in range(16):
            q = px[x, y]
            if q[3]: px[x, y] = gmap(0.5 + (n(q) - 0.5) * contrast + bias, p) + (255,)
    return im
def save(im, name): im.save(os.path.join(OUT, name + '.png'))

PITCH  = pal('#050405', '#0a090b', '#121014', '#1b181d', '#252127')   # tar with a faint violet cast
SOAKED = pal('#0e0b0a', '#181311', '#231c18', '#30261f', '#3d3128')
SHEEN  = [(58, 64, 70), (76, 70, 92), (64, 84, 80)]                    # oil-slick blue/violet/teal
PETAL  = pal('#3c4a5c', '#5f7690', '#8aa6bf', '#b6d0e0', '#e2f2f6')   # cold spirit light
STEM   = pal('#1e2622', '#2f3b35', '#44544b', '#5a6c61', '#71857a')
BRASS  = pal('#2a1c0c', '#4f3816', '#7c5a24', '#a67e38', '#caa55a')
VERDI  = [(62, 122, 104), (84, 150, 128), (110, 172, 150)]

r = random.Random(1888)

soil = remap(ref('dirt.png'), SOAKED, contrast=0.6)
save(soil, 'tar_soil')

pool = remap(ref('mud.png'), PITCH, contrast=0.55); pp = pool.load()
for _ in range(5):                                   # oily rainbow sheen in soft arcs
    cx, cy, rad = r.randrange(16), r.randrange(16), r.choice([1.8, 2.4, 3.0])
    col = r.choice(SHEEN)
    for y in range(-4, 5):
        for x in range(-4, 5):
            d = (x * x + y * y) ** 0.5
            if rad - 0.8 < d <= rad and x <= 0 and y <= 0:
                pp[(cx + x) % 16, (cy + y) % 16] = col + (255,)
save(pool, 'tar_pool')

im = ref('poppy.png'); px = im.load(); n = norm(im)
for y in range(16):
    for x in range(16):
        q = px[x, y]
        if q[3] == 0: continue
        if q[0] > q[1] + 40:                          # petals -> pale spirit light, brightest at the edge
            px[x, y] = gmap(0.35 + n(q) * 0.65, PETAL) + (255,)
        else:
            px[x, y] = gmap(n(q) * 0.9, STEM) + (255,)
save(im, 'wisp_bloom')

im = remap(ref('sweet_berry_bush_stage1.png'), BRASS, contrast=1.0); px = im.load()
for y in range(16):
    for x in range(16):
        if px[x, y][3] and r.random() < 0.14: px[x, y] = r.choice(VERDI) + (255,)
save(im, 'brass_thorns')
print('occult_foundry textures written to', OUT)
