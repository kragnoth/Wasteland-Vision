#!/usr/bin/env python3
"""
Regenerates Wasteland Vision's dead/rotten textures FROM A REFERENCE RESOURCE PACK, so they match
its style (shape, shading, pixel clusters) while being re-palette'd to dead/rotten.

usage: python3 tools/gen_textures.py <path/to/pack/assets/minecraft> [out_dir]
       out_dir defaults to src/main/resources/assets/wastelandvision/textures/block

Technique: gradient-map. Each reference texture's luminance is mapped onto a hand-picked dark->light
palette, so the reference artist's shading survives but the colour story is ours. Damage (cracks,
rot, holes) is added deterministically on top.
"""
import os, random, sys
from PIL import Image

REF = sys.argv[1] if len(sys.argv) > 1 else 'minecraft'
OUT = sys.argv[2] if len(sys.argv) > 2 else 'extras/visions/dustbowl/assets/dustbowl/textures/block'
os.makedirs(OUT, exist_ok=True)

def ref(name):
    im = Image.open(os.path.join(REF, 'textures/block', name)).convert('RGBA')
    return im.crop((0, 0, 16, 16))  # first frame if animated / oversized

def hexrgb(h): return tuple(int(h[i:i+2], 16) for i in (1, 3, 5))

# ---- Palettes (shadow -> highlight). Muted, slightly cool shadows, gothic. ----
ASH_BARK   = [hexrgb(c) for c in ('#1a1817', '#302b28', '#4b433c', '#695e53', '#8b7f70')]
DEAD_HEART = [hexrgb(c) for c in ('#1c1816', '#3a322b', '#5e5246', '#80715f', '#a39079')]
ROT_WOOD   = [hexrgb(c) for c in ('#141210', '#2a241e', '#423829', '#5d4e39', '#776550')]
DRY_EARTH  = [hexrgb(c) for c in ('#231c16', '#3f3226', '#5d4a36', '#7b6447', '#95805e')]
STRAW      = [hexrgb(c) for c in ('#3a2f22', '#5f4d36', '#857050', '#a8916a', '#c4b08a')]
BLEACHED   = [hexrgb(c) for c in ('#2b2927', '#4e4945', '#77706a', '#a19a92', '#c7c0b6')]
SALT       = [hexrgb(c) for c in ('#8f887d', '#b3ac9f', '#cfc8bb', '#e0dbd0', '#ece8df')]
DEAD_LEAF  = [hexrgb(c) for c in ('#1b1714', '#33291f', '#4f3f2c', '#6c5739', '#86704c')]
WITHER     = [hexrgb(c) for c in ('#1a0f0e', '#36191a', '#52282a', '#6b3a34', '#82503f')]

def lum(p): return (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]) / 255.0

def gmap(l, pal):
    l = max(0.0, min(1.0, l)) * (len(pal) - 1)
    i = min(int(l), len(pal) - 2); t = l - i
    a, b = pal[i], pal[i + 1]
    return tuple(int(a[k] + (b[k] - a[k]) * t + 0.5) for k in range(3))

def normalize_lums(img):
    """Stretch the texture's own luminance range to 0..1 so every palette step gets used."""
    px = img.load(); ls = [lum(px[x, y]) for y in range(16) for x in range(16) if px[x, y][3] > 0]
    lo, hi = (min(ls), max(ls)) if ls else (0, 1)
    return lambda p: (lum(p) - lo) / (hi - lo if hi > lo else 1)

def remap(img, pal, gamma=1.0, bias=0.0, contrast=0.8):
    """contrast < 1 softens: normalising stretches the range, the reference pack is soft-shaded."""
    im = img.copy(); px = im.load(); n = normalize_lums(img)
    for y in range(16):
        for x in range(16):
            p = px[x, y]
            if p[3] == 0: continue
            l = 0.5 + ((n(p) ** gamma) - 0.5) * contrast + bias
            px[x, y] = gmap(l, pal) + (p[3] if p[3] < 255 else 255,)
    return im

def shade(px, x, y, f):
    r, g, b, a = px[x % 16, y % 16]
    if a: px[x % 16, y % 16] = (int(r * f), int(g * f), int(b * f), a)

def crack_path(rng, n, length, dirs):
    pts = []
    for _ in range(n):
        x, y = rng.randrange(16), rng.randrange(16); dx, dy = rng.choice(dirs)
        for _ in range(length):
            pts.append((x % 16, y % 16))
            if rng.random() < 0.3: dx, dy = rng.choice(dirs)
            x += dx; y += dy
    return pts

def draw_cracks(im, rng, n, length, dark=0.35, rim=1.18, dirs=((1,0),(0,1),(1,1),(1,-1))):
    px = im.load()
    for (x, y) in crack_path(rng, n, length, dirs):
        shade(px, x, y, dark)
        shade(px, x, y - 1, rim)   # lit upper lip sells the depth
    return im

def mud_plates(im, rng, plates=5, gap=0.55, edge=0.82):
    """Dried-mud plates: tileable (toroidal) Voronoi; soft dark gaps, lit plate rims, per-plate tone."""
    seeds = [(rng.uniform(0, 16), rng.uniform(0, 16)) for _ in range(plates)]
    tone = [rng.uniform(0.92, 1.08) for _ in range(plates)]
    px = im.load()
    def d(ax, ay, bx, by):
        dx = min(abs(ax - bx), 16 - abs(ax - bx)); dy = min(abs(ay - by), 16 - abs(ay - by))
        return (dx * dx + dy * dy) ** 0.5
    for y in range(16):
        for x in range(16):
            ds = sorted((d(x + 0.5, y + 0.5, sx, sy), i) for i, (sx, sy) in enumerate(seeds))
            (d1, i1), (d2, _) = ds[0], ds[1]
            f = tone[i1]
            if d2 - d1 < 0.9: f *= gap         # the crack itself
            elif d2 - d1 < 1.9: f *= edge      # shadowed lip
            shade(px, x, y, f)
    return im

# ----------------------------------------------------------------------------------------------
rng = random.Random(1347)

# Dead log: long-dead, weathered deadwood. Near-colourless silver-grey bark, flattened contrast,
# and patches where the bark has sloughed off to bleached, smooth grain (with a shadowed bark lip).
# Uses its own RNG so tweaking logs never changes the other textures.
lr = random.Random(2077)
DEADWOOD   = [hexrgb(c) for c in ('#1c1b1a', '#35322f', '#524d48', '#736d66', '#938c84')]
log = remap(ref('oak_log.png'), DEADWOOD, gamma=1.05, contrast=0.6, bias=-0.02)
px = log.load()
# One or two small, ragged sloughed-bark patches, blended into the bark (not pasted on), so a
# stacked trunk doesn't show a repeating stripe.
def mix(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
for _ in range(2):
    cx, cy, h = lr.randrange(16), lr.randrange(16), lr.randrange(3, 6)
    for k in range(h):
        y = (cy + k) % 16
        half = 1 if k in (0, h - 1) else lr.choice([1, 1, 2])     # tapered, ragged outline
        left = cx - half + lr.choice([0, 0, 1])
        for x in range(left, cx + half):
            r, g, b, a = px[x % 16, y]
            wood = gmap(0.50 + lr.uniform(-0.04, 0.04), BLEACHED)
            px[x % 16, y] = mix((r, g, b), wood, 0.55) + (a,)
        shade(px, left - 1, y, 0.7)                                 # bark lip shadow
for _ in range(2):                           # a couple of deep weather checks in the bark
    x = lr.randrange(16); y0 = lr.randrange(16)
    for k in range(lr.randrange(3, 6)): shade(px, x, y0 + k, 0.55)
log.save(os.path.join(OUT, 'dead_log.png'))

# Dead log top: grey, flattened rings with a dark rotted-out core.
DEAD_HEART_GREY = [hexrgb(c) for c in ('#1e1c1a', '#3a3632', '#58524b', '#78716a', '#968f86')]
top = remap(ref('oak_log_top.png'), DEAD_HEART_GREY, gamma=1.0, contrast=0.7)
px = top.load()
for y in range(6, 10):
    for x in range(6, 10):
        if lr.random() < 0.75: shade(px, x, y, 0.45)
top.save(os.path.join(OUT, 'dead_log_top.png'))

# Advance the shared RNG exactly as the previous log code did, so every texture generated
# after this point stays pixel-identical.
for _ in range(4): rng.randrange(16); rng.randrange(16); rng.randrange(4, 9)
for _ in range(3): rng.randrange(16); rng.randrange(16); rng.randrange(3, 6)
for _ in range(16): rng.random()

# Rotten planks: reference planks, rot palette, dark seam gaps, mould blooms, a broken knot.
pl = remap(ref('oak_planks.png'), ROT_WOOD, gamma=1.0, contrast=0.8, bias=0.06)
px = pl.load()
for _ in range(5):  # mould blooms (cool green-black clusters)
    cx, cy = rng.randrange(16), rng.randrange(16)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            if rng.random() < 0.55:
                r, g, b, a = px[(cx + dx) % 16, (cy + dy) % 16]
                px[(cx + dx) % 16, (cy + dy) % 16] = (int(r * 0.55), int(g * 0.68), int(b * 0.55), a)
for _ in range(6):  # rot pits
    shade(px, rng.randrange(16), rng.randrange(16), 0.35)
pl.save(os.path.join(OUT, 'rotten_planks.png'))

# Dead leaves: reference leaf clumps, brown-grey, thinned in 2x2 blocks so it stays chunky.
lv = remap(ref('oak_leaves.png'), DEAD_LEAF, gamma=0.95, contrast=0.9)
px = lv.load()
for by in range(0, 16, 4):          # knock out whole 4x4 clumps, keeps the reference's clumping
    for bx in range(0, 16, 4):
        if rng.random() < 0.28:
            for dy in range(4):
                for dx in range(4):
                    if rng.random() < 0.8: px[bx + dx, by + dy] = (0, 0, 0, 0)
lv.save(os.path.join(OUT, 'dead_leaves.png'))

# Cracked earth: reference dirt, dry earth palette, deep cracks.
# ONE shared dirt base. Every face where dirt shows (dirt blocks, grass-block sides/bottom,
# salt-crust sides, cracked earth) derives from this, so stacked blocks always match.
DIRT_BASE = remap(ref('dirt.png'), DRY_EARTH, gamma=1.0, bias=0.03, contrast=0.6)
DIRT_BASE.save(os.path.join(OUT, 'dry_dirt.png'))

ce = DIRT_BASE.copy()
mud_plates(ce, random.Random(7), plates=5, gap=0.6, edge=0.86)
ce.save(os.path.join(OUT, 'cracked_earth.png'))

# Dried grass block: reference (greyscale, normally tinted) grass top in straw-brown.
gt = remap(ref('grass_block_top.png'), STRAW, gamma=1.2, bias=-0.15, contrast=0.55)
gt.save(os.path.join(OUT, 'dried_grass_block_top.png'))
side = DIRT_BASE.copy()
ov = ref('grass_block_side_overlay.png'); op = ov.load(); sp = side.load(); n = normalize_lums(ov)
for y in range(16):
    for x in range(16):
        if op[x, y][3] > 0: sp[x, y] = gmap(0.5 + (n(op[x, y]) ** 1.2 - 0.5) * 0.6 - 0.15, STRAW) + (255,)
side.save(os.path.join(OUT, 'dried_grass_block_side.png'))

# Dried salt: reference sand's grain, re-mapped to salt, with crust cracks.
salt = remap(ref('sand.png'), SALT, gamma=0.9)
mud_plates(salt, random.Random(42), plates=4, gap=0.82, edge=0.94)
salt.save(os.path.join(OUT, 'dried_salt.png'))
ss = DIRT_BASE.copy(); sp = ss.load(); sa = salt.load()
for x in range(16):
    for y in range(rng.choice([2, 3, 3, 4])): sp[x, y] = sa[x, (y + 5) % 16]
ss.save(os.path.join(OUT, 'dried_salt_side.png'))

# Plants: reference shapes, re-palette'd.
remap(ref('short_grass.png'), STRAW, gamma=1.1, contrast=1.0).save(os.path.join(OUT, 'dried_grass.png'))
remap(ref('tall_grass_bottom.png'), STRAW, gamma=1.1, contrast=1.0).save(os.path.join(OUT, 'dried_tall_grass_bottom.png'))
remap(ref('tall_grass_top.png'), STRAW, gamma=1.1, contrast=1.0).save(os.path.join(OUT, 'dried_tall_grass_top.png'))
remap(ref('dead_bush.png'), BLEACHED, gamma=0.9, contrast=1.0).save(os.path.join(OUT, 'withered_shrub.png'))

# Withered flower: reference poppy; petals -> dried blood-brown, stem -> straw, some petals fallen.
wf = ref('poppy.png'); px = wf.load(); n = normalize_lums(wf)
for y in range(16):
    for x in range(16):
        r, g, b, a = px[x, y]
        if a == 0: continue
        if r > g + 25:  # petal
            px[x, y] = (0, 0, 0, 0) if rng.random() < 0.25 else gmap(n((r, g, b, a)), WITHER) + (255,)
        else:
            px[x, y] = gmap(n((r, g, b, a)) * 0.9, STRAW) + (255,)
wf.save(os.path.join(OUT, 'withered_flower.png'))
# Dead stripped log / wood: bare, sun-bleached driftwood grain - lighter than the dead bark so the
# two read as different blocks, still near-colourless. Own RNG => other textures unaffected.
sr = random.Random(4242)
DRIFTWOOD = [hexrgb(c) for c in ('#26231f', '#45403a', '#655e56', '#857d73', '#a19a8f')]
sl = remap(ref('stripped_oak_log.png'), DRIFTWOOD, gamma=1.0, contrast=0.6)
px = sl.load()
for _ in range(3):                      # dark rot streaks running with the grain
    x = sr.randrange(16); y0 = sr.randrange(16)
    for k in range(sr.randrange(2, 5)): shade(px, x, y0 + k, 0.62)
for _ in range(4):                      # small borer holes
    shade(px, sr.randrange(16), sr.randrange(16), 0.45)
sl.save(os.path.join(OUT, 'dead_stripped_log.png'))
st = remap(ref('stripped_oak_log_top.png'), DRIFTWOOD, gamma=1.0, contrast=0.65)
px = st.load()
for y in range(6, 10):
    for x in range(6, 10):
        if sr.random() < 0.7: shade(px, x, y, 0.5)
st.save(os.path.join(OUT, 'dead_stripped_log_top.png'))

print('textures written to', OUT)
