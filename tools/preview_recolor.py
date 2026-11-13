#!/usr/bin/env python3
"""
Offline preview of a vision's recolor_sets: a faithful port of RecolorSource.gradientMap (palettes,
mix, saturation/brightness, clumps/knockout, stain blotches and drips), run on a reference pack's
textures. Writes a PNG sheet: one row per set, each sample shown before -> after, plus a 3x3 tiled
"wall" of the last sample so drips/streaks can be judged as they repeat across blocks.

usage: python3 tools/preview_recolor.py <vision folder> <path/to/pack/assets/minecraft> [out.png] [samples per set]
"""
import json, os, re, sys
from PIL import Image, ImageDraw

M64 = (1 << 64) - 1

def s64(v):
    v &= M64
    return v - (1 << 64) if v >> 63 else v

def jhash(s):
    h = 0
    for ch in s: h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >> 31 else h

def mix(seed, x, y):
    h = (seed ^ (x * 0x9E3779B97F4A7C15) ^ (y * 0xC2B2AE3D27D4EB4F)) & M64
    h = ((h ^ (h >> 30)) * 0xBF58476D1CE4E5B9) & M64
    h = ((h ^ (h >> 27)) * 0x94D049BB133111EB) & M64
    return h ^ (h >> 31)

def cell_value(seed, i, j, gw, gh):
    return ((mix(seed, i % gw, j % gh) >> 40) & 0xFFFF) / 65535

def value_noise(seed, x, y, cell, gw, gh):
    import math
    fx, fy = (x + 0.5) / cell - 0.5, (y + 0.5) / cell - 0.5
    x0, y0 = math.floor(fx), math.floor(fy)
    tx, ty = fx - x0, fy - y0
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a, b = cell_value(seed, x0, y0, gw, gh), cell_value(seed, x0 + 1, y0, gw, gh)
    c, d = cell_value(seed, x0, y0 + 1, gw, gh), cell_value(seed, x0 + 1, y0 + 1, gw, gh)
    top = a + (b - a) * tx
    return top + ((c + (d - c) * tx) - top) * ty

def hexrgb(s): s = s.lstrip('#'); return int(s, 16)

def glob(g):
    if ':' not in g: g = '*:' + g
    return re.compile('.*'.join(re.escape(p) for p in g.split('*')) + r'\Z')

def lum(r, g, b): return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

def sample(pal, t):
    f = t * (len(pal) - 1); i = min(int(f), len(pal) - 2); k = f - i
    a, b = pal[i], pal[i + 1]
    return tuple(int(((a >> s) & 0xFF) + (((b >> s) & 0xFF) - ((a >> s) & 0xFF)) * k + 0.5) for s in (16, 8, 0))

def recolor(img, st, sprite_id, set_name):
    img = img.convert('RGBA').copy(); px = img.load(); w, h = img.size
    frame_h = w if (h > w and h % w == 0) else h
    scale = max(1, w // 16)
    seed = s64(jhash(sprite_id) * 31 + jhash(set_name)) & M64
    pals = st.get('palettes') or ([st['palette']] if 'palette' in st else [])
    pals = [[hexrgb(c) for c in p] for p in pals]
    weights = (st.get('weights') or [1] * len(pals))[:len(pals)]
    weights += [1] * (len(pals) - len(weights))
    total = sum(weights)
    mixk = 0.0 if not pals else max(0.0, min(1.0, st.get('mix', 1.0)))
    sat, bri = st.get('saturation', 1.0), st.get('brightness', 1.0)
    gamma, contrast, bias = st.get('gamma', 1.0), st.get('contrast', 0.8), st.get('bias', 0.0)
    clump = st.get('clump', 0) * scale; knock = st.get('knockout', 0.0)
    mapped = bool(pals) and mixk > 0
    lo, hi = 1.0, 0.0
    if mapped:
        for y in range(h):
            for x in range(w):
                r, g, b, a = px[x, y]
                if a: l = lum(r, g, b); lo = min(lo, l); hi = max(hi, l)
    rng = hi - lo if hi > lo else 1.0
    sn = st.get('stain')
    drip = [0] * w
    if sn and sn.get('drips', 0) > 0:
        s2 = seed ^ 0x2545F4914F6CDD1D
        for col in range(w // scale):
            hs = mix(s2, col, 7)
            if ((hs >> 40) & 0xFFFF) / 65536 >= sn['drips']: continue
            ln = (0.35 + 0.65 * (((hs >> 8) & 0xFF) / 255)) * sn.get('drip_length', 0.6) * frame_h
            if col > 0 and drip[(col - 1) * scale] > 0: continue
            for k in range(scale): drip[col * scale + k] = max(1, round(ln))
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0: continue
            fy = y % frame_h
            cell = mix(seed, x // clump, fy // clump) if clump > 0 else mix(seed, 0, 0)
            if clump > 0 and knock > 0 and ((cell >> 40) & 0xFFFF) / 65536 < knock \
                    and ((mix(seed ^ 0x5DEECE66D, x, fy) >> 40) & 0xFF) < 218:
                px[x, y] = (0, 0, 0, 0); continue
            fr, fg, fb = r / 255, g / 255, b / 255
            if sat != 1 or bri != 1:
                l = 0.299 * fr + 0.587 * fg + 0.114 * fb
                fr, fg, fb = [(l + (c - l) * sat) * bri for c in (fr, fg, fb)]
            if mapped:
                if len(pals) == 1 or total <= 0: pal = pals[0]
                else:
                    roll = cell % total; pal = pals[-1]
                    for i, wt in enumerate(weights):
                        roll -= wt
                        if roll < 0: pal = pals[i]; break
                n = (lum(r, g, b) - lo) / rng
                t = max(0.0, min(1.0, 0.5 + ((n ** gamma) - 0.5) * contrast + bias))
                pr, pg, pb = sample(pal, t)
                fr += (pr / 255 - fr) * mixk; fg += (pg / 255 - fg) * mixk; fb += (pb / 255 - fb) * mixk
            if sn:
                amt = 0.0; strength = sn.get('strength', 0.5)
                if sn.get('coverage', 0.3) > 0:
                    sc = max(1, sn.get('clump', 2)) * scale
                    nval = value_noise(seed ^ 0x632BE59BD9B4E019, x, fy, sc, max(1, w // sc), max(1, frame_h // sc))
                    t = (nval - (1 - sn.get('coverage', 0.3))) / 0.38
                    if t > 0:
                        t = min(1.0, t); t = t * t * (3 - 2 * t)
                        grain = ((mix(seed ^ 0x1B873593, x, fy) >> 40) & 0xFF) / 255
                        amt = strength * t * (0.8 + 0.2 * grain)
                if drip[x] > 0 and fy < drip[x]:
                    fade = 1 - fy / drip[x]
                    amt = max(amt, strength * 0.7 * fade * fade)
                amt = min(1.0, amt)
                if amt > 0:
                    sc_ = hexrgb(sn.get('color', '#14110e'))
                    fr += (((sc_ >> 16) & 0xFF) / 255 - fr) * amt
                    fg += (((sc_ >> 8) & 0xFF) / 255 - fg) * amt
                    fb += ((sc_ & 0xFF) / 255 - fb) * amt
            px[x, y] = tuple(max(0, min(255, int(c * 255 + 0.5))) for c in (fr, fg, fb)) + (a,)
    return img

def main():
    vision, ref = sys.argv[1], sys.argv[2]
    out = sys.argv[3] if len(sys.argv) > 3 else 'recolor_preview.png'
    per = int(sys.argv[4]) if len(sys.argv) > 4 else 6
    v = json.load(open(os.path.join(vision, 'vision.json')))
    sets = {k: s for k, s in v.get('recolor_sets', {}).items() if not k.startswith('_')}
    tex_dir = os.path.join(ref, 'textures', 'block')
    names = sorted(f[:-4] for f in os.listdir(tex_dir) if f.endswith('.png'))
    S, G = 48, 6
    rows = []
    for name, st in sets.items():
        pats = [glob(g) for g in st['sprites']]
        hits = [n for n in names if any(p.match('minecraft:block/' + n) for p in pats)]
        rows.append((name, hits[:per], len(hits)))
    width = 220 + per * (2 * S + 3 * G) + 3 * S + G
    sheet = Image.new('RGBA', (width, len(rows) * (S + 2 * G) + G), (34, 31, 28, 255))
    d = ImageDraw.Draw(sheet)
    for i, (name, hits, count) in enumerate(rows):
        y0 = G + i * (S + 2 * G)
        d.text((G, y0 + 4), name, fill=(230, 220, 200, 255))
        d.text((G, y0 + 20), f'{count} vanilla sprites', fill=(150, 140, 125, 255))
        x0, last = 220, None
        for n in hits:
            src = Image.open(os.path.join(tex_dir, n + '.png')).convert('RGBA')
            src16 = src.crop((0, 0, src.width, src.width)) if src.height > src.width else src
            dst = recolor(src, sets[name], 'minecraft:block/' + n, name)
            dst16 = dst.crop((0, 0, dst.width, dst.width)) if dst.height > dst.width else dst
            sheet.alpha_composite(src16.resize((S, S), Image.NEAREST), (x0, y0))
            sheet.alpha_composite(dst16.resize((S, S), Image.NEAREST), (x0 + S + G // 2, y0))
            x0 += 2 * S + 3 * G; last = dst16
        if last is not None:
            wall = Image.new('RGBA', (last.width * 3, last.height * 3))
            for ty in range(3):
                for tx in range(3): wall.alpha_composite(last, (tx * last.width, ty * last.height))
            sheet.alpha_composite(wall.resize((3 * S, 3 * S), Image.NEAREST).crop((0, 0, 3 * S, S)), (width - 3 * S - G, y0))
    sheet.save(out)
    print('wrote', out)

if __name__ == '__main__':
    main()
