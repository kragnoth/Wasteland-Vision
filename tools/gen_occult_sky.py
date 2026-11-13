#!/usr/bin/env python3
"""Sky omen textures for occult_foundry: soft_disc, hard_disc, star_ring, corona (white + alpha; tinted in code)."""
import math, os, random, sys
from PIL import Image
OUT = sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/wastelandvision_defaults/visions/occult_foundry/assets/occult/textures/environment'
os.makedirs(OUT, exist_ok=True)
def img(n, f):
    im = Image.new('RGBA', (n, n)); px = im.load()
    for y in range(n):
        for x in range(n):
            dx, dy = (x + 0.5) / n * 2 - 1, (y + 0.5) / n * 2 - 1
            a = max(0.0, min(1.0, f(math.hypot(dx, dy), math.atan2(dy, dx))))
            px[x, y] = (255, 255, 255, int(a * 255 + 0.5))
    return im
img(64, lambda r, a: (1 - r) ** 1.6 if r < 1 else 0).save(os.path.join(OUT, 'soft_disc.png'))
img(64, lambda r, a: min(1, max(0, (0.97 - r) * 24))).save(os.path.join(OUT, 'hard_disc.png'))
img(32, lambda r, a: math.exp(-((r - 0.6) / 0.09) ** 2)).save(os.path.join(OUT, 'star_ring.png'))
rnd = random.Random(1878)
rays = [(rnd.uniform(0, 2 * math.pi), rnd.uniform(0.05, 0.16), rnd.uniform(0.5, 1.0)) for _ in range(26)]
def corona(r, a):
    if r < 0.30: return 0.0
    base = math.exp(-(r - 0.30) / 0.13) * 0.85
    streak = 0
    for ang, wid, strength in rays:
        d = abs((a - ang + math.pi) % (2 * math.pi) - math.pi)
        streak += strength * math.exp(-(d / wid) ** 2) * math.exp(-(r - 0.30) / 0.32)
    return min(1.0, (base + 0.55 * streak) * min(1, (r - 0.30) * 40)) * (1 if r < 1 else 0)
img(128, corona).save(os.path.join(OUT, 'corona.png'))
print('sky textures written to', OUT)
