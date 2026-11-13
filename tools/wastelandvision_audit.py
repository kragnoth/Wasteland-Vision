#!/usr/bin/env python3
"""
Wasteland Vision audit - drop this file into your instance's game folder (the one holding mods/ and
config/; in Prism that's instances/<name>/minecraft/) and run:

    python3 wastelandvision_audit.py                 # active vision from wastelandvision-client.toml
    python3 wastelandvision_audit.py soot_and_tar    # a specific vision
    python3 wastelandvision_audit.py --mc-jar /path/to/minecraft-1.21.1-client.jar

No dependencies (standard library only; it has its own PNG reader/writer).

What it does
  1. Indexes every block's assets from vanilla, every mod jar, your enabled resource packs and the
     vision folders (same layering order as the game): blockstates -> models (parents followed) ->
     the actual block textures each block uses.
  2. Reads config/wastelandvision/mappings-<vision>.txt (written by the game whenever the vision is
     built - toggle it once in a world) to know what the game really did to every block.
  3. Runs the vision's recolour sets on those textures (a port of the mod's own Java code), so you
     see each block before -> after.
  4. Finds GAPS (blocks no rule touched that look like stone/brick/wood/metal/... ) and RISKS (Create
     addons, custom model loaders, rule/texture mismatches), and writes ready-to-paste rules.

Output folder: wastelandvision_audit/
  report.html           open in a browser - summary, suggestions, every mod before/after
  suggested_rules.json  rules + recolor sprite additions to paste into vision.json
  blocks.csv            one line per block: status, rule, sets, textures, flags
"""
import argparse, base64, csv, glob, html, io, json, math, os, re, struct, sys, zipfile, zlib
from collections import Counter, OrderedDict, defaultdict

# =============================================================================== tiny PNG codec

def png_read(data):
    """-> (w, h, [r,g,b,a,...] flat list) or None. 8-bit and palette/low-bit, non-interlaced."""
    if data[:8] != b'\x89PNG\r\n\x1a\n': return None
    pos, idat, plte, trns = 8, [], None, None
    w = h = depth = ctype = interlace = 0
    while pos < len(data):
        ln, = struct.unpack('>I', data[pos:pos + 4]); typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]; pos += 12 + ln
        if typ == b'IHDR': w, h, depth, ctype, _, _, interlace = struct.unpack('>IIBBBBB', body)
        elif typ == b'PLTE': plte = body
        elif typ == b'tRNS': trns = body
        elif typ == b'IDAT': idat.append(body)
        elif typ == b'IEND': break
    if interlace or w == 0 or w * h > 4096 * 4096: return None
    chans = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(ctype)
    if chans is None or (depth != 8 and not (ctype in (0, 3) and depth in (1, 2, 4))) : return None
    raw = zlib.decompress(b''.join(idat))
    bpp = max(1, chans * depth // 8)
    stride = (w * chans * depth + 7) // 8
    out = bytearray(); prev = bytearray(stride); i = 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0; b = prev[x]; c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        out += line; prev = line
    px = []
    for y in range(h):
        row = out[y * stride:(y + 1) * stride]
        for x in range(w):
            if depth < 8:
                per = 8 // depth; byte = row[x // per]; sh = 8 - depth * (x % per + 1)
                v = (byte >> sh) & ((1 << depth) - 1)
            else:
                v = None
            if ctype == 3:
                idx = v if v is not None else row[x]
                r, g, b = plte[idx * 3:idx * 3 + 3] if plte and idx * 3 + 2 < len(plte) else (0, 0, 0)
                a = trns[idx] if trns and idx < len(trns) else 255
            elif ctype == 0:
                g0 = v * 255 // ((1 << depth) - 1) if v is not None else row[x]; r = g = b = g0; a = 255
            elif ctype == 4: r = g = b = row[x * 2]; a = row[x * 2 + 1]
            elif ctype == 2: r, g, b = row[x * 3:x * 3 + 3]; a = 255
            else: r, g, b, a = row[x * 4:x * 4 + 4]
            px.extend((r, g, b, a))
    return w, h, px

def png_write(w, h, px):
    raw = bytearray()
    for y in range(h):
        raw.append(0); raw += bytes(px[y * w * 4:(y + 1) * w * 4])
    def chunk(t, b): return struct.pack('>I', len(b)) + t + b + struct.pack('>I', zlib.crc32(t + b) & 0xFFFFFFFF)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')

def first_frame(img):
    w, h, px = img
    if h > w and h % w == 0: return w, w, px[:w * w * 4]
    return img

def data_uri(img):
    return 'data:image/png;base64,' + base64.b64encode(png_write(*img)).decode()

# =============================================================================== recolour port
# Mirrors client/RecolorSource.java (gradientMap, dripLengths, stainAmount, valueNoise).

M64 = (1 << 64) - 1
def s64(v): v &= M64; return v - (1 << 64) if v >> 63 else v
def jhash(s):
    h = 0
    for ch in s: h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >> 31 else h
def mix(seed, x, y):
    h = (seed ^ (x * 0x9E3779B97F4A7C15) ^ (y * 0xC2B2AE3D27D4EB4F)) & M64
    h = ((h ^ (h >> 30)) * 0xBF58476D1CE4E5B9) & M64
    h = ((h ^ (h >> 27)) * 0x94D049BB133111EB) & M64
    return h ^ (h >> 31)
def cell_value(seed, i, j, gw, gh): return ((mix(seed, i % gw, j % gh) >> 40) & 0xFFFF) / 65535
def value_noise(seed, x, y, cell, gw, gh):
    fx, fy = (x + 0.5) / cell - 0.5, (y + 0.5) / cell - 0.5
    x0, y0 = math.floor(fx), math.floor(fy); tx, ty = fx - x0, fy - y0
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a, b = cell_value(seed, x0, y0, gw, gh), cell_value(seed, x0 + 1, y0, gw, gh)
    c, d = cell_value(seed, x0, y0 + 1, gw, gh), cell_value(seed, x0 + 1, y0 + 1, gw, gh)
    top = a + (b - a) * tx
    return top + ((c + (d - c) * tx) - top) * ty
def hexrgb(s): return int(s.lstrip('#'), 16)
def lum(r, g, b): return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
def pal_sample(pal, t):
    f = t * (len(pal) - 1); i = min(int(f), len(pal) - 2); k = f - i; a, b = pal[i], pal[i + 1]
    return tuple(int(((a >> s) & 0xFF) + (((b >> s) & 0xFF) - ((a >> s) & 0xFF)) * k + 0.5) for s in (16, 8, 0))

def recolor(img, st, sprite_id, set_name):
    w, h, src = img; px = list(src)
    frame_h = w if (h > w and h % w == 0) else h
    scale = max(1, w // 16)
    seed = s64(jhash(sprite_id) * 31 + jhash(set_name)) & M64
    pals = st.get('palettes') or ([st['palette']] if 'palette' in st else [])
    pals = [[hexrgb(c) for c in p] for p in pals]
    weights = (st.get('weights') or [1] * len(pals))[:len(pals)]; weights += [1] * (len(pals) - len(weights))
    total = sum(weights)
    mixk = 0.0 if not pals else max(0.0, min(1.0, st.get('mix', 1.0)))
    sat, bri = st.get('saturation', 1.0), st.get('brightness', 1.0)
    gamma, contrast, bias = st.get('gamma', 1.0), st.get('contrast', 0.8), st.get('bias', 0.0)
    clump = st.get('clump', 0) * scale; knock = st.get('knockout', 0.0)
    mapped = bool(pals) and mixk > 0
    lo, hi = 1.0, 0.0
    if mapped:
        for i in range(0, len(px), 4):
            if px[i + 3]: l = lum(px[i], px[i + 1], px[i + 2]); lo = min(lo, l); hi = max(hi, l)
    rng = hi - lo if hi > lo else 1.0
    sn = st.get('stain'); drip = [0] * w
    if sn and sn.get('drips', 0) > 0:
        s2 = seed ^ 0x2545F4914F6CDD1D
        for col in range(w // scale):
            hs = mix(s2, col, 7)
            if ((hs >> 40) & 0xFFFF) / 65536 >= sn['drips']: continue
            ln = (0.35 + 0.65 * (((hs >> 8) & 0xFF) / 255)) * sn.get('drip_length', 0.6) * frame_h
            if col > 0 and drip[(col - 1) * scale] > 0: continue
            for k in range(scale): drip[col * scale + k] = max(1, round(ln))
    scol = hexrgb(sn.get('color', '#14110e')) if sn else 0
    for y in range(h):
        fy = y % frame_h
        for x in range(w):
            i = (y * w + x) * 4; r, g, b, a = px[i:i + 4]
            if a == 0: continue
            cell = mix(seed, x // clump, fy // clump) if clump > 0 else mix(seed, 0, 0)
            if clump > 0 and knock > 0 and ((cell >> 40) & 0xFFFF) / 65536 < knock \
                    and ((mix(seed ^ 0x5DEECE66D, x, fy) >> 40) & 0xFF) < 218:
                px[i:i + 4] = [0, 0, 0, 0]; continue
            fr, fg, fb = r / 255, g / 255, b / 255
            if sat != 1 or bri != 1:
                l = 0.299 * fr + 0.587 * fg + 0.114 * fb
                fr, fg, fb = [(l + (c - l) * sat) * bri for c in (fr, fg, fb)]
            if mapped:
                pal = pals[0]
                if len(pals) > 1 and total > 0:
                    roll = cell % total; pal = pals[-1]
                    for j, wt in enumerate(weights):
                        roll -= wt
                        if roll < 0: pal = pals[j]; break
                n = (lum(r, g, b) - lo) / rng
                t = max(0.0, min(1.0, 0.5 + ((n ** gamma) - 0.5) * contrast + bias))
                pr, pg, pb = pal_sample(pal, t)
                fr += (pr / 255 - fr) * mixk; fg += (pg / 255 - fg) * mixk; fb += (pb / 255 - fb) * mixk
            if sn:
                amt = 0.0; strength = sn.get('strength', 0.5)
                if sn.get('coverage', 0.3) > 0:
                    sc = max(1, sn.get('clump', 2)) * scale
                    nv = value_noise(seed ^ 0x632BE59BD9B4E019, x, fy, sc, max(1, w // sc), max(1, frame_h // sc))
                    t = (nv - (1 - sn.get('coverage', 0.3))) / 0.38
                    if t > 0:
                        t = min(1.0, t); t = t * t * (3 - 2 * t)
                        grain = ((mix(seed ^ 0x1B873593, x, fy) >> 40) & 0xFF) / 255
                        amt = strength * t * (0.8 + 0.2 * grain)
                if drip[x] > 0 and fy < drip[x]:
                    fade = 1 - fy / drip[x]; amt = max(amt, strength * 0.7 * fade * fade)
                amt = min(1.0, amt)
                if amt > 0:
                    fr += (((scol >> 16) & 0xFF) / 255 - fr) * amt
                    fg += (((scol >> 8) & 0xFF) / 255 - fg) * amt
                    fb += ((scol & 0xFF) / 255 - fb) * amt
            px[i:i + 3] = [max(0, min(255, int(c * 255 + 0.5))) for c in (fr, fg, fb)]
    return w, h, px

def tile3(img):
    w, h, px = img; out = []
    for y in range(h * 2):
        for x in range(w * 3):
            i = ((y % h) * w + (x % w)) * 4; out.extend(px[i:i + 4])
    return w * 3, h * 2, out

# =============================================================================== globs / rules

def glob_re(g):
    g = g.strip()
    if ':' not in g: g = '*:' + g
    return re.compile('.*'.join(re.escape(p) for p in g.split('*')) + r'\Z')

class RecolorSet:
    def __init__(self, name, st):
        self.name, self.st = name, st
        self.globs = [glob_re(g) for g in st.get('sprites', [])]
    def matches(self, sprite): return any(p.match(sprite) for p in self.globs)
    def category(self):
        """Material this set is for: from its name, else the commonest category among its sprite globs."""
        c = category(self.name.replace('soot_', '').replace('grimy_', '').replace('sooty_', '').replace('creosote_', 'plank_'))
        if c: return c
        votes = Counter(category(g.replace('*', '')) for g in self.st.get('sprites', []))
        votes.pop(None, None)
        return votes.most_common(1)[0][0] if votes else None

# =============================================================================== asset index

class Sources:
    """Layered asset lookup: later sources override earlier ones (vanilla < mods < packs < visions)."""
    def __init__(self):
        self.layers = []          # (label, kind, handle)
        self.index = {}           # path -> layer idx (topmost)
        self.ns_owner = {}        # namespace -> label of the jar that first provided it
        self.tag_files = []       # (layer idx, tag id, path) - tags MERGE across layers

    def add_zip(self, label, path):
        try: z = zipfile.ZipFile(path)
        except Exception as e: print(f'  ! skipping {path}: {e}'); return
        li = len(self.layers); self.layers.append((label, 'zip', z))
        for n in z.namelist():
            self._maybe_tag(li, n)
            if n.startswith('assets/') and not n.endswith('/'):
                self.index[n] = li
                ns = n.split('/')[1]
                if label.startswith('mod:'): self.ns_owner.setdefault(ns, label[4:])

    TAG_RE = re.compile(r'^data/([^/]+)/tags/blocks?/(.+)\.json$')

    def _maybe_tag(self, li, path):
        m = self.TAG_RE.match(path)
        if m: self.tag_files.append((li, f'{m.group(1)}:{m.group(2)}', path))

    def add_dir(self, label, root):
        li = len(self.layers); self.layers.append((label, 'dir', root))
        for d, _, files in os.walk(os.path.join(root, 'data')):
            for f in files:
                self._maybe_tag(li, os.path.relpath(os.path.join(d, f), root).replace(os.sep, '/'))
        base = os.path.join(root, 'assets')
        for d, _, files in os.walk(base):
            for f in files:
                p = os.path.relpath(os.path.join(d, f), root).replace(os.sep, '/')
                self.index[p] = li

    def read(self, path, layer=None):
        li = self.index.get(path) if layer is None else layer
        if li is None: return None
        _, kind, h = self.layers[li]
        try:
            if kind == 'zip': return h.read(path)
            with open(os.path.join(h, path), 'rb') as f: return f.read()
        except Exception: return None

    def tags(self):
        """-> {tag id: set of entries ('#tag' or 'ns:id')}, merged in load order ('replace' honoured)."""
        out = defaultdict(set)
        for li, tid, path in self.tag_files:
            b = self.read(path, li)
            try: j = json.loads(b.decode('utf-8-sig')) if b else {}
            except Exception: continue
            if j.get('replace'): out[tid] = set()
            for v in j.get('values', []):
                if isinstance(v, dict): v = v.get('id', '')
                if v: out[tid].add(v if v.startswith('#') else rl(v))
        return out

    def source_of(self, path):
        li = self.index.get(path); return self.layers[li][0] if li is not None else None

    def json(self, path):
        b = self.read(path)
        if b is None: return None
        try: return json.loads(b.decode('utf-8-sig'))
        except Exception:
            try: return json.loads(re.sub(r'//.*', '', b.decode('utf-8-sig', 'replace')))
            except Exception: return None

def rl(s, default_ns='minecraft'):
    s = s.strip()
    return s if ':' in s else default_ns + ':' + s

def model_path(mid):
    ns, p = rl(mid).split(':', 1); return f'assets/{ns}/models/{p}.json'

def tex_path(sid):
    ns, p = rl(sid).split(':', 1); return f'assets/{ns}/textures/{p}.png'

class ModelResolver:
    def __init__(self, src):
        self.src, self.cache = src, {}

    def textures(self, mid, depth=0):
        """-> (dict of resolved texture vars, set of flags)."""
        mid = rl(mid)
        if mid in self.cache: return self.cache[mid]
        flags = set(); tex = {}
        if depth > 24: return tex, {'parent-loop'}
        m = self.src.json(model_path(mid))
        if m is None:
            if not mid.startswith('minecraft:builtin/'): flags.add('missing-model')
            self.cache[mid] = (tex, flags); return tex, flags
        if 'parent' in m:
            ptex, pflags = self.textures(m['parent'], depth + 1); tex.update(ptex); flags |= (pflags - {'missing-model'})
            if 'missing-model' in pflags and not rl(m['parent']).startswith('minecraft:'): flags.add('missing-parent')
        if 'loader' in m: flags.add('loader:' + str(m['loader']))
        for k, v in (m.get('textures') or {}).items():
            if isinstance(v, str): tex[k] = v
        for child in self._children(m):           # composite / multi-layer loaders
            for k, v in (child.get('textures') or {}).items():
                if isinstance(v, str): tex.setdefault('child_' + k, v)
            if 'parent' in child:
                ctex, cflags = self.textures(child['parent'], depth + 1)
                for k, v in ctex.items(): tex.setdefault('child_' + k, v)
                flags |= cflags
        self.cache[mid] = (tex, flags)
        return tex, flags

    @staticmethod
    def _children(m):
        ch = m.get('children')
        if isinstance(ch, dict): return [c for c in ch.values() if isinstance(c, dict)]
        if isinstance(ch, list): return [c for c in ch if isinstance(c, dict)]
        return []

    def sprites(self, mid):
        tex, flags = self.textures(mid)
        out = set()
        for k, v in tex.items():
            seen = 0
            while v.startswith('#') and seen < 16: v = tex.get(v[1:], ''); seen += 1
            if v and not v.startswith('#'): out.add(rl(v))
        return out, flags

def blockstate_models(bs):
    out = []
    def take(a):
        if isinstance(a, dict) and 'model' in a: out.append(a['model'])
        elif isinstance(a, list):
            for x in a: take(x)
    for v in (bs.get('variants') or {}).values(): take(v)
    for part in bs.get('multipart') or []: take(part.get('apply'))
    return out

# =============================================================================== categories

# Material words, matched against WHOLE tokens of an id/texture name (split on _ / : . - and digits):
# "rice" no longer reads as ice, "spinel" as pine, "sands_of_vitae" as sand.
# First category with a matching token wins; plant/leaves words come first so "silver_birch_sapling"
# is a plant, not silver metal.
CATS = OrderedDict([
    ('leaves',  {'leaves', 'leaf', 'foliage', 'needles', 'hedge'}),
    ('plant',   {'sapling', 'shrub', 'sprouts', 'sprout', 'flower', 'flowers', 'bush', 'fern', 'weed', 'reed', 'reeds',
                 'vine', 'vines', 'petal', 'petals', 'tulip', 'daisy', 'orchid', 'poppy', 'clover', 'roots', 'grass',
                 'dandelion', 'allium', 'bluet', 'cornflower', 'lilac', 'peony', 'coneflower', 'dropleaf', 'flowerbed',
                 'seedling', 'cattail', 'lavender', 'fireweed', 'aster', 'bloom', 'cactus', 'dripleaf', 'litter'}),
    ('ground',  {'dirt', 'soil', 'mud', 'podzol', 'moss', 'peat', 'loam', 'turf', 'mycelium', 'farmland', 'path', 'silt'}),
    ('metal',   {'iron', 'steel', 'copper', 'brass', 'bronze', 'zinc', 'tin', 'lead', 'aluminum', 'aluminium', 'alu',
                 'nickel', 'metal', 'sheetmetal', 'girder', 'rebar', 'pipe', 'catwalk', 'grate', 'chain', 'bars',
                 'dwarven', 'tarnished', 'plating', 'rivet', 'riveted', 'industrial', 'silver', 'gold', 'dawnstone', 'netherite'}),
    ('log',     {'log', 'logs', 'wood', 'bark', 'stem', 'stripped', 'trunk', 'branch', 'branches', 'treated'}),
    ('glass',   {'glass', 'window', 'pane'}),
    ('cloth',   {'wool', 'carpet', 'cloth', 'fabric', 'curtain', 'awning', 'hammock', 'canvas', 'rug'}),
    ('sand',    {'sand', 'gravel', 'clay', 'sandstone', 'cinder'}),
    ('masonry', {'stone', 'brick', 'bricks', 'cobble', 'cobblestone', 'tile', 'tiles', 'concrete', 'terracotta',
                 'andesite', 'diorite', 'granite', 'tuff', 'calcite', 'slate', 'deepslate', 'basalt', 'marble',
                 'limestone', 'shale', 'schist', 'rhyolite', 'dolerite', 'arkose', 'chalk', 'asphalt', 'pavement', 'dripstone',
                 'cassiterite', 'cylindrite', 'sugilite', 'lunalite', 'travertine', 'prismarine', 'purpur', 'quartz',
                 'shingle', 'shingles', 'caminite', 'masonry', 'pillar', 'ashlar', 'cobbled'}),
    ('wood',    {'plank', 'planks', 'board', 'boards', 'timber', 'lumber', 'crate', 'barrel', 'shelf', 'bookshelf',
                 'beam', 'wooden', 'cabinet', 'mosaic', 'fence', 'gate', 'plate', 'door', 'trapdoor',
                 'oak', 'spruce', 'birch', 'jungle', 'acacia', 'mangrove', 'cherry', 'bamboo', 'pine', 'maple',
                 'willow', 'aspen', 'redwood', 'cypress', 'larch', 'baobab', 'eucalyptus', 'palm', 'holly', 'chestnut',
                 'rosewood', 'laurel', 'kousa', 'morado', 'yucca', 'grimwood', 'plum', 'wisteria', 'azalea'}),
    ('snow',    {'snow', 'ice', 'icicle', 'frost', 'frosted'}),
])
# pairs that legitimately share a set (no mismatch warning)
COMPATIBLE = [{'sand', 'masonry'}, {'log', 'wood'}, {'ground', 'plant'}, {'ground', 'masonry'}, {'glass', 'metal'}]
# Tokens that mark machines, props and mob-shaped blocks: never suggested for a material set.
NOT_MATERIAL_TOKENS = {
    'bell', 'anvil', 'bed', 'pot', 'potted', 'skull', 'head', 'banner', 'rail', 'hopper', 'dispenser', 'dropper',
    'observer', 'piston', 'beacon', 'conduit', 'cake', 'campfire', 'composter', 'lectern', 'loom', 'smoker',
    'cauldron', 'jukebox', 'note', 'enchanting', 'brewing', 'crafting', 'ore', 'redstone', 'glowstone', 'lamp',
    'torch', 'lantern', 'candle', 'button', 'lever', 'sign', 'machine', 'furnace', 'chest', 'spawner', 'portal',
    'fluid', 'bucket', 'crystal', 'gem', 'cable', 'wire', 'gui', 'overlay', 'particle', 'destroy',
    'nylium', 'netherrack', 'crimson', 'warped', 'soul', 'nether', 'coral', 'ingot', 'raw',
    'cogwheel', 'cog', 'shaft', 'gear', 'gearbox', 'crank', 'charge', 'thruster', 'extruder', 'slicer', 'catalyst',
    'cannon', 'mortirer', 'forge', 'printer', 'infuser', 'cooler', 'server', 'trader', 'breeder', 'incubator',
    'converter', 'barterer', 'viewer', 'planter', 'farmer', 'farm', 'market', 'nest', 'trough', 'rune',
    'stand', 'station', 'keg', 'instiller', 'schematicannon', 'pump', 'vent', 'reactor', 'hourglass',
    'skeleton', 'scarecrow', 'statue', 'engine', 'motor', 'generator', 'turbine', 'drill', 'saw', 'press',
    'mixer', 'basin', 'depot', 'funnel', 'tunnel', 'chute', 'valve', 'tank', 'casing', 'encased', 'controller',
    'terminal', 'interface', 'connector', 'link', 'radiance', 'heat', 'test', 'creative', 'decal', 'wreath',
    'pudding', 'jam', 'pumpkin', 'melon', 'bale', 'bag', 'block_of', 'rice', 'gunpowder', 'vitae',
    'flesh', 'gums', 'stitched', 'carrion', 'veinmine', 'sacrifice', 'arcane',
    'clutch', 'deployer', 'gearshift', 'pulley', 'hose', 'sequenced', 'belt', 'millstone', 'crushing', 'wheel',
    'fan', 'bearing', 'gantry', 'sticker', 'harvester', 'plough', 'contraption', 'gauge', 'turngear', 'ring',
    'gas', 'oil', 'placement', 'emitter', 'receiver', 'extractor', 'inscriber', 'refinery', 'actuator', 'pedestal'}

def tokens(text):
    return set(t for t in re.split(r'[^a-z]+', text.lower()) if t)

def not_material(text):
    return bool(tokens(text) & NOT_MATERIAL_TOKENS)

def category(text):
    tk = tokens(text)
    if 'grass' in tk and 'block' in tk: return 'ground'
    for cat, keys in CATS.items():
        if tk & keys: return cat
    # compound words ("stonebrick", "cobblestonewall"): long keywords inside a token
    for cat, keys in CATS.items():
        if any(len(k) >= 5 and k in t for t in tk for k in keys): return cat
    return None


# =============================================================================== offline rule matcher

class TagResolver:
    def __init__(self, raw): self.raw, self.cache = raw, {}
    def members(self, tid, depth=0):
        tid = rl(tid)
        if tid in self.cache: return self.cache[tid]
        out = set()
        if depth < 32:
            for e in self.raw.get(tid, ()):
                out |= self.members(e[1:], depth + 1) if e.startswith('#') else {e}
        self.cache[tid] = out
        return out

CLASS_SUFFIX = {'stairs': ('_stairs',), 'slab': ('_slab',), 'fence': ('_fence',), 'fence_gate': ('_fence_gate',),
                'wall': ('_wall',), 'door': ('_door',), 'trapdoor': ('_trapdoor',), 'carpet': ('_carpet', 'moss_carpet')}

def rule_matches(rule, bid, tags, axis):
    """True / False / None (can't tell offline: shape/property checks) / 'excluded' (its positive
    conditions select the block, but a not_* condition deliberately leaves it out)."""
    c = rule.get('if', {}) or {}
    ns, path = bid.split(':', 1)
    def lst(k):
        v = c.get(k, []); return [v] if isinstance(v, str) else v
    def in_list(entries):
        for e in entries:
            if e.startswith('#'):
                if bid in tags.members(e[1:]): return True
            elif rl(e) == bid: return True
        return False
    if 'namespace' in c and c['namespace'] != ns: return False
    if lst('blocks') and not in_list(lst('blocks')): return False
    if lst('id_contains') and not any(x in path for x in lst('id_contains')): return False
    if lst('id_ends_with') and not any(path.endswith(x) for x in lst('id_ends_with')): return False
    if 'stripped' in c:
        st = 'stripped' in path or bid in tags.members('c:stripped_logs') or bid in tags.members('c:stripped_woods')
        if st != c['stripped']: return False
    if not c: return True
    if ns in lst('not_namespaces') or in_list(lst('not_blocks')) or any(x in path for x in lst('not_id_contains')):
        return 'excluded'
    unsure = False
    if 'class' in c:
        if c['class'] == 'pillar': ok = axis
        else: ok = path.endswith(CLASS_SUFFIX.get(c['class'], ('\0',)))
        if not ok: unsure = True
    if any(k in c for k in ('full_cube', 'occludes', 'properties')): unsure = True
    return None if unsure else True

def is_loose(rule):
    """A rule that selects by id text only (no block/tag list, no namespace): relies on texture pruning."""
    c = rule.get('if', {}) or {}
    return not c.get('blocks') and 'namespace' not in c

def first_rule(rules, bid, tags, axis):
    """-> (rule number, certain?, rule number that deliberately excluded it or None)."""
    maybe = excl = None
    for i, r in enumerate(rules, 1):
        if r.get('then') == 'native': continue        # 'native' only fires where a variant block exists
        m = rule_matches(r, bid, tags, axis)
        if m is True: return (i, maybe is None, excl)
        if m is None and maybe is None: maybe = i
        if m == 'excluded' and excl is None: excl = i
    return (maybe, False, excl) if maybe else (None, True, excl)

# =============================================================================== main

def find_mc_jar(game_dir, explicit):
    if explicit: return explicit if os.path.isfile(explicit) else None
    version = '1.21.1'
    for meta in ('../mmc-pack.json', 'mmc-pack.json'):
        p = os.path.join(game_dir, meta)
        if os.path.isfile(p):
            try:
                for c in json.load(open(p)).get('components', []):
                    if c.get('uid') == 'net.minecraft': version = c.get('version', version)
            except Exception: pass
    cands = []
    d = os.path.abspath(game_dir)
    for _ in range(6):                                            # Prism: <root>/libraries/...
        cands += glob.glob(os.path.join(d, 'libraries', 'com', 'mojang', 'minecraft', version, f'minecraft-{version}-client.jar'))
        cands += glob.glob(os.path.join(d, 'libraries', 'net', 'minecraft', 'client', f'{version}*', f'client-{version}*.jar'))
        cands += glob.glob(os.path.join(d, 'versions', version, f'{version}.jar'))
        d = os.path.dirname(d)
    home = os.path.expanduser('~')
    for root in (f'{home}/.local/share/PrismLauncher', f'{home}/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher',
                 f'{home}/.local/share/multimc', f'{home}/.minecraft'):
        cands += glob.glob(os.path.join(root, 'libraries', 'com', 'mojang', 'minecraft', version, f'minecraft-{version}-client.jar'))
        cands += glob.glob(os.path.join(root, 'versions', version, f'{version}.jar'))
    for c in cands:
        try:
            with zipfile.ZipFile(c) as z:
                if 'assets/minecraft/textures/block/stone.png' in z.namelist(): return c
        except Exception: pass
    return None

def find_neoforge_jar(game_dir):
    """NeoForge's own jar carries the c: convention tags (c:sands, c:glass_blocks...)."""
    d = os.path.abspath(game_dir); home = os.path.expanduser('~')
    roots = []
    for _ in range(6): roots.append(d); d = os.path.dirname(d)
    roots += [f'{home}/.local/share/PrismLauncher', f'{home}/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher']
    for r in roots:
        c = sorted(glob.glob(os.path.join(r, 'libraries', 'net', 'neoforged', 'neoforge', '*', 'neoforge-*-universal.jar')))
        if c: return c[-1]
    return None

def enabled_packs(game_dir):
    p = os.path.join(game_dir, 'options.txt'); out = []
    if not os.path.isfile(p): return out
    for line in open(p, encoding='utf-8', errors='replace'):
        if line.startswith('resourcePacks:'):
            try: names = json.loads(line.split(':', 1)[1])
            except Exception: names = []
            for n in names:
                if n.startswith('file/'):
                    f = os.path.join(game_dir, 'resourcepacks', n[5:])
                    if os.path.exists(f): out.append(f)
    return out

def mod_names(jar):
    """-> (mod ids declared in [[mods]], display name, ids this jar depends on)."""
    try:
        with zipfile.ZipFile(jar) as z:
            for t in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml'):
                if t in z.namelist():
                    s = z.read(t).decode('utf-8', 'replace')
                    ids, names, deps = [], [], set()
                    for sec in re.split(r'^\s*\[\[', s, flags=re.M)[1:]:
                        head = sec.split(']]', 1)[0].strip()
                        mid = re.search(r'^\s*modId\s*=\s*"([^"]+)"', sec, re.M)
                        if head == 'mods' and mid:
                            ids.append(mid.group(1))
                            dn = re.search(r'^\s*displayName\s*=\s*"([^"]+)"', sec, re.M)
                            if dn: names.append(dn.group(1))
                        elif head.startswith('dependencies') and mid:
                            deps.add(mid.group(1))
                    return ids, (names[0] if names else None), deps
    except Exception: pass
    return [], None, set()

def main():
    ap = argparse.ArgumentParser(description='Audit a Wasteland Vision vision against this instance.')
    ap.add_argument('vision', nargs='?', help='vision folder name (default: activeVision from the client config)')
    ap.add_argument('--game-dir', default=os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument('--mc-jar', help='vanilla client jar (auto-detected for Prism/vanilla launchers)')
    ap.add_argument('--out', default='wastelandvision_audit')
    ap.add_argument('--max-per-mod', type=int, default=400, help='before/after tiles per mod in the report')
    a = ap.parse_args()

    game = a.game_dir
    if not os.path.isdir(os.path.join(game, 'mods')) and os.path.isdir(os.path.join(os.getcwd(), 'mods')): game = os.getcwd()
    if not os.path.isdir(os.path.join(game, 'mods')): sys.exit(f'No mods/ folder in {game} - put this script in the instance game folder.')
    wv = os.path.join(game, 'config', 'wastelandvision')
    vision_id = a.vision
    installed = sorted(os.path.basename(d) for d in glob.glob(os.path.join(wv, 'visions', '*')) if os.path.isfile(os.path.join(d, 'vision.json')))
    if not vision_id:
        # the vision the game compiled most recently = the newest mappings file
        maps = sorted(((os.path.getmtime(f), f) for f in glob.glob(os.path.join(wv, 'mappings-*.txt'))
                       if os.path.basename(f)[9:-4] in installed), reverse=True)
        if maps:
            vision_id = os.path.basename(maps[0][1])[9:-4]
        else:
            t = os.path.join(game, 'config', 'wastelandvision-client.toml')
            m = re.search(r'activeVision\s*=\s*"([^"]+)"', open(t).read()) if os.path.isfile(t) else None
            vision_id = m.group(1) if m else 'soot_and_tar'
        if len(installed) > 1:
            print(f'Installed visions: {", ".join(installed)} - auditing "{vision_id}" (newest mappings file). '
                  f'Pass a name to pick another, e.g. python3 {os.path.basename(__file__)} soot_and_tar')
    vdir = os.path.join(wv, 'visions', vision_id)
    if not os.path.isfile(os.path.join(vdir, 'vision.json')): sys.exit(f'No vision at {vdir}')
    vision = json.load(open(os.path.join(vdir, 'vision.json'), encoding='utf-8'))
    sets = OrderedDict((k, RecolorSet(k, v)) for k, v in vision.get('recolor_sets', {}).items() if not k.startswith('_'))
    rules = vision.get('rules', [])
    print(f'Vision: {vision_id} ({len(rules)} rules, {len(sets)} recolour sets)')

    # ---------------------------------------------------------------- assets
    src = Sources()
    mcjar = find_mc_jar(game, a.mc_jar)
    if mcjar: print(f'Vanilla assets: {mcjar}'); src.add_zip('vanilla', mcjar)
    else: print('  ! vanilla client jar not found (use --mc-jar): vanilla blocks will show without textures')
    nf = find_neoforge_jar(game)
    if nf: src.add_zip('neoforge', nf)
    jars = sorted(glob.glob(os.path.join(game, 'mods', '*.jar')))
    ns_name, ns_deps = {}, {}
    for j in jars:
        ids, name, deps = mod_names(j)
        for i in ids: ns_name.setdefault(i, name or i); ns_deps[i] = deps
        src.add_zip('mod:' + os.path.basename(j), j)
    for p in enabled_packs(game):
        (src.add_zip if os.path.isfile(p) else src.add_dir)('pack:' + os.path.basename(p), p)
    for extra in [os.path.join(game, 'kubejs')] + sorted(glob.glob(os.path.join(game, 'datapacks', '*'))):
        if os.path.isdir(os.path.join(extra, 'data')): src.add_dir('data:' + os.path.basename(extra), extra)
        elif extra.endswith('.zip') and os.path.isfile(extra): src.add_zip('data:' + os.path.basename(extra), extra)
    for vd in sorted(glob.glob(os.path.join(wv, 'visions', '*'))):
        if os.path.isdir(os.path.join(vd, 'assets')): src.add_dir('vision:' + os.path.basename(vd), vd)
    print(f'Indexed {len(jars)} mod jars, {len(src.index)} asset files')

    # ---------------------------------------------------------------- mappings
    mp = os.path.join(wv, f'mappings-{vision_id}.txt')
    mapping = {}
    if os.path.isfile(mp):
        for line in open(mp, encoding='utf-8'):
            m = re.match(r'(\S+) -> (.+?)\s+\[rule #(\d+)(?:: (.*))?\]\s*$', line.strip())
            if m: mapping[m.group(1)] = (m.group(2), int(m.group(3)), m.group(4) or '')
        print(f'Mappings: {len(mapping)} blocks changed by the game')
    else:
        print(f'  ! {mp} not found - turn the vision on in a world once so the game writes it. Gaps will be estimated only.')

    # ---------------------------------------------------------------- exclusions declared by the vision
    keep_ns, skip_ns = set(), set()
    for r in rules:
        c = r.get('if', {})
        if r.get('then') == 'keep' and 'namespace' in c: keep_ns.add(c['namespace'])
        skip_ns |= set(c.get('not_namespaces', []))
    skip_ns.discard('minecraft')
    create_ns = {ns for ns, deps in ns_deps.items() if 'create' in deps and ns != 'create'}

    # ---------------------------------------------------------------- blocks
    resolver = ModelResolver(src)
    blocks = []
    for path in sorted(p for p in src.index if '/blockstates/' in p and p.endswith('.json')):
        parts = path.split('/')
        ns, name = parts[1], '/'.join(parts[3:])[:-5]
        if ns == 'wastelandvision': continue
        bid = f'{ns}:{name}'
        bs = src.json(path) or {}
        axis = any('axis=' in k for k in (bs.get('variants') or {}))
        sprites, flags = set(), set()
        for mid in set(blockstate_models(bs)):
            s, f = resolver.sprites(mid); sprites |= s; flags |= f
        sprites = {s for s in sprites if '/block/' in s or s.split(':', 1)[1].startswith('block')}
        models = set(blockstate_models(bs))
        msrc = sorted({src.source_of(model_path(m)) or '?' for m in models} | {src.source_of(path)})
        blocks.append({'id': bid, 'ns': ns, 'sprites': sorted(sprites), 'flags': flags, 'axis': axis,
                       'src': [x for x in msrc if x and x.startswith(('pack:', 'vision:'))]})
    known = {b['id'] for b in blocks}
    for bid in mapping:                                       # mapped but no blockstate file (code-generated)
        if bid not in known:
            blocks.append({'id': bid, 'ns': bid.split(':')[0], 'sprites': [], 'flags': {'no-blockstate-file'}, 'axis': False, 'src': []})
    print(f'Resolved {len(blocks)} blocks')
    tags = TagResolver(src.tags())
    print(f'Block tags: {len(tags.raw)}')

    texcache = {}
    def tex(sid):
        if sid not in texcache:
            b = src.read(tex_path(sid)); texcache[sid] = png_read(b) if b else None
        return texcache[sid]

    recache = {}
    def after(sid, setname):
        k = (sid, setname)
        if k not in recache:
            img = tex(sid)
            recache[k] = recolor(first_frame(img), sets[setname].st, sid, setname) if img else None
        return recache[k]

    CAT_SET = {}
    for sn in sets:
        c = sets[sn].category()
        if c and c not in CAT_SET: CAT_SET[c] = sn
    CAT_SET.setdefault('log', next((s for s in sets if 'bark' in s), None))
    CAT_SET.setdefault('ground', next((s for s in sets if 'growth' in s), None))
    CAT_SET.setdefault('plant', CAT_SET.get('ground'))

    # ---------------------------------------------------------------- classify
    rows, gaps, risks = [], [], []
    for b in blocks:
        bid, ns = b['id'], b['ns']
        m = mapping.get(bid)
        status, rule_no, rule_txt, used = 'untouched', None, '', []
        if m:
            target, rule_no, rule_txt = m
            rule = rules[rule_no - 1] if 0 < rule_no <= len(rules) else {}
            if target == '(retextured)':
                status = 'retextured'
                rec = rule.get('recolor', [])
                rec = [rec] if isinstance(rec, str) else rec
                for s in b['sprites']:
                    hit = next((n for n in rec if n in sets and sets[n].matches(s)), None)
                    if hit: used.append((s, hit))
            elif target == '(vanishes)': status = 'vanishes'
            elif target == 'per-position plant': status = 'plant'
            else: status = 'swapped'; b['target'] = target
        b.update(status=status, rule=rule_no, rule_txt=rule_txt, used=used)

        # risk flags
        notes = []
        if status == 'retextured':
            if ns in create_ns: notes.append('Create addon (connected/custom models - check in game)')
            if any(f.startswith('loader:') for f in b['flags']): notes.append('custom model loader: ' + ', '.join(sorted(f[7:] for f in b['flags'] if f.startswith('loader:'))))
            bc = category(bid.split(':')[1])
            for s, sn in used:
                sc = category(s.split(':')[1].split('/')[-1]) or category(s)
                setcat = sets[sn].category()
                if sc and setcat and sc != setcat and {sc, setcat} not in COMPATIBLE:
                    notes.append(f'texture {s.split(":")[1]} looks like {sc} but got {sn}'); break
            if not used and b['sprites']: notes.append('rule matched but no texture in the model matches its sets (custom model?)')
            if notes: risks.append(b)
        b['notes'] = notes

        # gap detection
        if status == 'untouched' and b['sprites']:
            text = bid.split(':')[1]
            would, certain, excl = first_rule(rules, bid, tags, b['axis'])
            wrule = rules[would - 1] if would else None
            if wrule and wrule.get('then') == 'keep':
                pass                                                   # deliberately kept
            elif excl and would and excl < would and not not_material(text):
                # an earlier, specific rule deliberately left it out; a later loose rule only matched by id
                b['gap'] = {'sets': [], 'cat': category(text), 'kind': 'excluded',
                            'why': f'deliberately excluded by rule #{excl} (not_blocks / not_id_contains / not_namespaces)'}
                gaps.append(b)
            elif wrule and wrule.get('then') == 'retexture':
                rec = wrule.get('recolor', []); rec = [rec] if isinstance(rec, str) else rec
                hits = [s for s in b['sprites'] if any(n in sets and sets[n].matches(s) for n in rec)]
                cat = category(text) or category(b['sprites'][0].split('/')[-1])
                setcat = sets[rec[0]].category() if rec and rec[0] in sets else None
                tcat = category(b['sprites'][0].split('/')[-1]) or cat
                if hits and len(hits) == len(b['sprites']):
                    why = (f'rule #{would} selects it and its textures match, but the game left it alone - its in-game model '
                           'differs: a resource pack this tool didn\'t see (server pack, or one not enabled in options.txt), '
                           'a mod that replaces models at runtime (connected/random textures), or a code-built model')
                    if not certain: why = f'rule #{would} probably selects it (shape check unknown offline); textures match, yet the game left it alone'
                    b['gap'] = {'sets': [(rec[0], len(hits))], 'cat': cat, 'why': why, 'kind': 'runtime'}
                elif not_material(text):
                    pass
                elif setcat and tcat and tcat != setcat and {tcat, setcat} not in COMPATIBLE and CAT_SET.get(tcat):
                    # a loose rule (fallback) picked it, but it's really another material: give it its own rule
                    b['gap'] = {'sets': [], 'cat': tcat, 'kind': 'norule',
                                'why': f'only loose rule #{would} ({rec[0]}) picks it up, but it looks like {tcat} - needs its own rule'}
                else:
                    via = f' (its model comes from {", ".join(x.split(":", 1)[1] for x in b["src"])})' if b['src'] else ''
                    if is_loose(wrule) and not (tcat and setcat and (tcat == setcat or {tcat, setcat} in COMPATIBLE)):
                        continue_gap = False                      # loose fallback rule + unrelated material: not a gap
                    else:
                        continue_gap = True
                    if continue_gap: b['gap'] = {'sets': [], 'cat': cat or setcat, 'kind': 'sprites', 'set': rec[0], 'rule': would,
                                'why': f'rule #{would} selects it, but {"some" if hits else "none"} of its textures match {", ".join(rec)}{via} - add the rest to the set'}
                if 'gap' in b: gaps.append(b)
            elif not not_material(text):
                cat = category(text) or category(b['sprites'][0].split('/')[-1])
                cand = Counter()
                for s in b['sprites']:
                    tc = category(s.split('/')[-1]) or cat
                    for sn, rs in sets.items():
                        sc = rs.category()
                        # a glob hit only counts if the material agrees ('*pine*' also matches 'spinel')
                        if rs.matches(s) and tc and sc and (tc == sc or {tc, sc} in COMPATIBLE):
                            cand[sn] += 1; break
                if cand or cat:
                    excluded = ns in keep_ns or ns in skip_ns or excl
                    b['gap'] = {'sets': cand.most_common(), 'cat': cat, 'kind': 'excluded' if excluded else 'norule',
                                'why': (f'deliberately excluded by rule #{excl} (not_blocks / not_id_contains / not_namespaces)' if excl
                                        else 'excluded by the vision (keep / not_namespaces)') if excluded else
                                       'no rule selects it; its textures already match a recolour set' if cand else
                                       'no rule selects it, and it looks like ' + cat + ' but its textures match no recolour set'}
                    gaps.append(b)
        rows.append(b)

    # ---------------------------------------------------------------- suggestions
    suggestions, sprite_adds = [], defaultdict(set)
    by = defaultdict(list)
    for b in gaps:
        g = b['gap']
        if g['kind'] in ('excluded', 'runtime'): continue
        if g['kind'] == 'sprites':                       # the rule already picks it: only textures are missing
            scat = sets[g['set']].category()
            loose = is_loose(rules[g['rule'] - 1])
            for s in b['sprites']:
                if not_material(s) or sets[g['set']].matches(s): continue
                tc = category(s.split('/')[-1]) or category(s)
                compatible = tc and (tc == scat or {tc, scat} in COMPATIBLE)
                # a tag/block-list rule names the block on purpose: trust it with every texture of the block
                # (resource packs rename them: fence_oak, stone1, plate_oak...). Loose id rules must match material.
                if compatible or (not loose and (tc is None or tc == category(b['id'].split(':')[1]))):
                    sprite_adds[g['set']].add(s)
            continue
        idcat = category(b['id'].split(':', 1)[1])
        if idcat is None: continue                       # only suggest blocks whose own name reads as a material
        sn = g['sets'][0][0] if g['sets'] else CAT_SET.get(g['cat'])
        if not sn or not (idcat == sets[sn].category() or {idcat, sets[sn].category()} in COMPATIBLE):
            sn = CAT_SET.get(idcat)                      # the block's own name decides the material
            if not sn: continue
        by[(b['ns'], sn)].append(b)
        if not g['sets']:
            scat = sets[sn].category()
            for s in b['sprites']:
                if not_material(s): continue
                tc = category(s.split('/')[-1]) or category(s)
                if (tc and (tc == scat or {tc, scat} in COMPATIBLE)) or len(b['sprites']) == 1: sprite_adds[sn].add(s)
    for (ns, sn), bl in sorted(by.items(), key=lambda kv: -len(kv[1])):
        rule = {'_comment': f'audit: {len(bl)} {ns_name.get(ns, ns)} blocks for {sn}', 'if': {'blocks': sorted(x['id'] for x in bl)},
                'then': 'retexture', 'recolor': sn}
        if sn.endswith('foliage') or 'growth' in sn: rule['tint'] = 'keep'
        suggestions.append(rule)
    sprite_globs = {}
    for sn, ss in sprite_adds.items():                    # collapse to folder globs where it helps
        byfolder = defaultdict(list)
        for s in ss: byfolder[s.rsplit('/', 1)[0]].append(s)
        out = []
        for folder, lst in byfolder.items():
            root = folder.endswith(':block')                 # never 'ns:block/*' - that's every texture of a mod
            out += [folder + '/*'] if (len(lst) >= 4 and not root) else sorted(lst)
        sprite_globs[sn] = sorted(out)

    os.makedirs(os.path.join(game, a.out), exist_ok=True)
    out = os.path.join(game, a.out)
    json.dump({'_readme': 'Paste "rules" entries into vision.json BEFORE the FALLBACK rules; add "add_sprites" globs to each recolor set\'s "sprites" list. Review the report first - every block here is listed with a before/after preview.',
               'add_sprites': sprite_globs, 'rules': suggestions},
              open(os.path.join(out, 'suggested_rules.json'), 'w'), indent=2)

    with open(os.path.join(out, 'blocks.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['block', 'mod', 'status', 'rule', 'sets_used', 'textures', 'model_from_pack', 'gap', 'notes'])
        for b in sorted(rows, key=lambda x: x['id']):
            w.writerow([b['id'], ns_name.get(b['ns'], b['ns']), b['status'], b['rule'] or '',
                        ' '.join(sorted({s for _, s in b['used']})), ' '.join(b['sprites']),
                        ' '.join(b['src']), b.get('gap', {}).get('why', ''), ' | '.join(b['notes'])])

    # ---------------------------------------------------------------- report
    print('Rendering report (recolouring textures)...')
    def thumb(sid, setname=None):
        img = after(sid, setname) if setname else (first_frame(tex(sid)) if tex(sid) else None)
        return f'<img src="{data_uri(img)}" title="{html.escape(sid)}">' if img else '<span class="nt">?</span>'
    def block_tile(b, mode):
        sid, sn = (b['used'][0] if b['used'] else (b['sprites'][0] if b['sprites'] else None, None))
        if mode == 'gap':
            sn = b['gap'].get('set') or (b['gap']['sets'][0][0] if b['gap']['sets'] else CAT_SET.get(b['gap']['cat']))
            sid = next((s for s in b['sprites'] if sn and sets[sn].matches(s)), b['sprites'][0] if b['sprites'] else None)
        if mode == 'swapped':
            tb = src.json(f'assets/{b["target"].split(":")[0]}/blockstates/{b["target"].split(":",1)[1]}.json') or {}
            ts = set()
            for mid in blockstate_models(tb): ts |= resolver.sprites(mid)[0]
            right = thumb(sorted(ts)[0]) if ts else '<span class="nt">?</span>'
            left = thumb(sid) if sid else '<span class="nt">?</span>'
        else:
            left = thumb(sid) if sid else '<span class="nt">?</span>'
            right = thumb(sid, sn) if (sid and sn) else '<span class="nt">-</span>'
        label = html.escape(b['id'].split(':', 1)[1])
        extra = ''
        if mode == 'gap': extra = f'<div class="why">{html.escape(b["gap"]["why"])}{(" → " + html.escape(sn)) if sn else ""}</div>'
        elif b.get('notes'): extra = '<div class="why warn">' + '<br>'.join(html.escape(n) for n in b['notes']) + '</div>'
        elif mode == 'retextured': extra = f'<div class="why">#{b["rule"]} · {html.escape(" ".join(sorted({s for _, s in b["used"]})))}</div>'
        elif mode == 'swapped': extra = f'<div class="why">→ {html.escape(b["target"])}</div>'
        return f'<div class="tile" data-q="{html.escape(b["id"])}">{left}{right}<div class="lbl">{label}</div>{extra}</div>'

    per_mod = defaultdict(lambda: Counter())
    for b in rows: per_mod[b['ns']][b['status']] += 1; per_mod[b['ns']]['total'] += 1
    for b in gaps: per_mod[b['ns']]['gap'] += 1
    rule_hits = Counter(b['rule'] for b in rows if b['rule'])
    set_hits = Counter(s for b in rows for _, s in b['used'])

    H = []
    H.append(f'''<!doctype html><html><head><meta charset="utf-8"><title>Wasteland Vision audit: {html.escape(vision_id)}</title><style>
:root{{--bg:#1c1a17;--card:#26231f;--fg:#e8e2d6;--dim:#a39b8c;--acc:#d39b4a;--warn:#e07a5f;--ok:#8fb36b}}
body{{background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif;margin:0;padding:24px;max-width:1500px}}
h1{{margin:0 0 4px}} h2{{margin-top:36px;border-bottom:1px solid #3a352e;padding-bottom:6px}} .dim{{color:var(--dim)}}
table{{border-collapse:collapse;width:100%}} td,th{{padding:4px 8px;border-bottom:1px solid #332f29;text-align:left}} th{{color:var(--dim);font-weight:600}}
td.n{{text-align:right;font-variant-numeric:tabular-nums}} .bar{{height:8px;background:var(--ok);border-radius:4px}}
.grid{{display:flex;flex-wrap:wrap;gap:6px}} .tile{{background:var(--card);border-radius:6px;padding:6px;width:132px}}
.tile img,.nt{{width:48px;height:48px;image-rendering:pixelated;display:inline-block;vertical-align:top;margin-right:4px;background:#111}}
.nt{{text-align:center;line-height:48px;color:var(--dim)}} .lbl{{font-size:11px;word-break:break-all;margin-top:3px}}
.why{{font-size:10.5px;color:var(--dim);margin-top:2px}} .warn{{color:var(--warn)}} details{{background:#211f1b;border-radius:8px;margin:8px 0;padding:6px 12px}}
summary{{cursor:pointer;font-weight:600}} code,pre{{background:#111;border-radius:4px;padding:2px 5px}} pre{{padding:10px;overflow:auto;max-height:420px}}
input{{background:#111;color:var(--fg);border:1px solid #3a352e;border-radius:6px;padding:6px 10px;width:320px}}
.walls{{display:flex;flex-wrap:wrap;gap:14px}} .walls figure{{margin:0}} .walls img{{width:144px;height:96px;image-rendering:pixelated}} figcaption{{font-size:12px;color:var(--dim)}}
</style></head><body>
<h1>Wasteland Vision audit: {html.escape(vision.get("name", vision_id))}</h1>
<div class="dim">{len(rows)} blocks with models · {sum(1 for b in rows if b["status"]=="retextured")} retextured · {sum(1 for b in rows if b["status"]=="swapped")} swapped ·
{sum(1 for g in gaps if g["gap"]["kind"] in ("norule", "sprites"))} fixable gaps · {sum(1 for g in gaps if g["gap"]["kind"] == "runtime")} changed at runtime by your pack · {len(risks)} to check · {len(jars)} mods{" · <b class=warn>no mappings file: run the vision in game once</b>" if not mapping else ""}</div>
<p class="dim">Resource packs read (from options.txt): {html.escape(", ".join(l[5:] for l, _, _ in src.layers if l.startswith("pack:")) or "none")}</p>
<p class="dim">Left = real texture, right = what the vision shows (computed with the mod's own recolour code). Filter: <input id="q" placeholder="block id contains..." oninput="f()"></p>''')

    # recolour set walls
    H.append('<h2>Recolour sets</h2><p class="dim">Each set on a typical texture, tiled 3×2 so soot patches and drips show as they repeat. Texture count = textures in this instance the set\'s globs catch.</p><div class="walls">')
    all_sprites = sorted({p[7:].replace('/textures/', ':', 1)[:-4] for p in src.index if '/textures/block/' in p and p.endswith('.png')})
    for sn, rs in sets.items():
        caught = [s for s in all_sprites if rs.matches(s)]
        used_n = set_hits.get(sn, 0)
        sample = next((s for s, x in ((s, x) for b in rows for s, x in b['used']) if x == sn), caught[0] if caught else None)
        img = after(sample, sn) if sample else None
        H.append(f'<figure>{"<img src=%s>" % data_uri(tile3(img)) if img else "<span class=nt>?</span>"}<figcaption><b>{html.escape(sn)}</b><br>{len(caught)} textures · used by {used_n} blocks</figcaption></figure>')
    H.append('</div>')

    # suggestions
    H.append('<h2>Suggested additions</h2><p class="dim">Blocks no rule touched that look like a material the vision covers. Saved to <code>suggested_rules.json</code>: paste the rules before the FALLBACK rules and add the <code>add_sprites</code> globs to the named sets.</p>')
    if suggestions:
        H.append('<pre>' + html.escape(json.dumps({'add_sprites': sprite_globs, 'rules': suggestions[:12]}, indent=2)) + ('\n... (full list in suggested_rules.json)' if len(suggestions) > 12 else '') + '</pre>')
    else: H.append('<p>Nothing obvious - the vision covers every block that looks like a material.</p>')
    sections = (
        ('No rule selects these', 'norule', 'They look like a material the vision covers. The suggested rules above pick them up.'),
        ('A rule selects these, but their textures aren\'t in its recolour set', 'sprites', 'Fixed by the add_sprites globs above.'),
        ('A rule selects these and the textures match, but the game left them alone', 'runtime',
         'Something the tool couldn\'t see changes these blocks in game. Most often: a resource pack that isn\'t enabled in options.txt '
         '(e.g. a server resource pack) giving them variant textures with other names (stone_1, dirt/variant2...). Enable it and re-run, and '
         'the tool will suggest the texture names. Otherwise a mod that replaces models at runtime, or a code-built model.'),
        ('Deliberately excluded by the vision, for reference', 'excluded', 'Kept by a keep rule, or left out by a rule\'s not_namespaces / not_blocks / not_id_contains. Listed so you can see what they look like untouched.'))
    for title, kind, blurb in sections:
        lst = [g for g in gaps if g['gap']['kind'] == kind]
        bym = defaultdict(list)
        for g in lst: bym[g['ns']].append(g)
        H.append(f'<h2>{title} <span class="dim">({len(lst)})</span></h2><p class="dim">{blurb}</p>')
        for ns, bl in sorted(bym.items(), key=lambda kv: -len(kv[1])):
            H.append(f'<details><summary>{html.escape(ns_name.get(ns, ns))} <span class="dim">({ns}) · {len(bl)}</span></summary><div class="grid">')
            H += [block_tile(b, 'gap') for b in bl[:a.max_per_mod]]
            H.append('</div></details>')

    # risks
    H.append(f'<h2>Check these in game <span class="dim">({len(risks)})</span></h2><p class="dim">Retextured blocks with a reason to look twice: Create addons (connected textures / custom baked models), custom model loaders, or a texture that looks like a different material than the set it got. If one looks wrong, add its namespace to <code>not_namespaces</code> or its id to the rule\'s <code>not_blocks</code>.</p>')
    bym = defaultdict(list)
    for r in risks: bym[r['ns']].append(r)
    for ns, bl in sorted(bym.items(), key=lambda kv: -len(kv[1])):
        H.append(f'<details><summary>{html.escape(ns_name.get(ns, ns))} <span class="dim">({ns}) · {len(bl)}</span></summary><div class="grid">')
        H += [block_tile(b, 'retextured') for b in bl[:a.max_per_mod]]
        H.append('</div></details>')

    # per mod coverage
    H.append('<h2>Coverage by mod</h2><table><tr><th>Mod</th><th>Namespace</th><th class=n>Blocks</th><th class=n>Retextured</th><th class=n>Swapped</th><th class=n>Gaps</th><th style="width:28%">Coverage</th></tr>')
    for ns, c in sorted(per_mod.items(), key=lambda kv: -kv[1]['total']):
        cov = (c['retextured'] + c['swapped'] + c['plant'] + c['vanishes']) / c['total'] if c['total'] else 0
        H.append(f'<tr><td>{html.escape(ns_name.get(ns, ns))}</td><td class=dim>{ns}{" <b class=dim>(kept)</b>" if ns in keep_ns else " <b class=dim>(skipped)</b>" if ns in skip_ns else ""}</td><td class=n>{c["total"]}</td><td class=n>{c["retextured"]}</td><td class=n>{c["swapped"]}</td><td class=n>{c["gap"]}</td><td><div class="bar" style="width:{cov*100:.0f}%"></div></td></tr>')
    H.append('</table>')

    # rules
    H.append('<h2>Rules</h2><table><tr><th>#</th><th>Then</th><th>Comment</th><th class=n>Blocks</th></tr>')
    for i, r in enumerate(rules, 1):
        H.append(f'<tr><td>{i}</td><td>{html.escape(str(r.get("then")))} {html.escape(str(r.get("recolor", r.get("standin", ""))))}</td><td class=dim>{html.escape(r.get("_comment", ""))}</td><td class=n>{rule_hits.get(i, 0)}{"  <span class=warn>never fires</span>" if not rule_hits.get(i) and mapping and r.get("then") != "keep" else ""}</td></tr>')
    H.append('</table>')

    # everything changed, by mod
    H.append('<h2>Everything the vision changes, by mod</h2>')
    bym = defaultdict(list)
    for b in rows:
        if b['status'] in ('retextured', 'swapped'): bym[b['ns']].append(b)
    for ns, bl in sorted(bym.items(), key=lambda kv: -len(kv[1])):
        H.append(f'<details><summary>{html.escape(ns_name.get(ns, ns))} <span class="dim">({ns}) · {len(bl)}</span></summary><div class="grid">')
        H += [block_tile(b, b['status']) for b in bl[:a.max_per_mod]]
        if len(bl) > a.max_per_mod: H.append(f'<div class="dim">... {len(bl) - a.max_per_mod} more (raise --max-per-mod)</div>')
        H.append('</div></details>')

    H.append('''<script>function f(){var q=document.getElementById('q').value.toLowerCase();
document.querySelectorAll('.tile').forEach(function(t){t.style.display=!q||t.dataset.q.indexOf(q)>=0?'':'none'});
if(q)document.querySelectorAll('details').forEach(function(d){d.open=!!d.querySelector('.tile:not([style*="none"])')})}</script></body></html>''')
    with open(os.path.join(out, 'report.html'), 'w', encoding='utf-8') as fh: fh.write('\n'.join(H))
    print(f'\nDone -> {out}/report.html  (+ suggested_rules.json, blocks.csv)')
    print(f'  retextured {sum(1 for b in rows if b["status"]=="retextured")}, swapped {sum(1 for b in rows if b["status"]=="swapped")}, '
          f'fixable gaps {sum(1 for g in gaps if g["gap"]["kind"] in ("norule", "sprites"))}, runtime-changed {sum(1 for g in gaps if g["gap"]["kind"] == "runtime")}, '
          f'to check {len(risks)}, suggested rules {len(suggestions)}, sprite globs {sum(len(v) for v in sprite_globs.values())}')

if __name__ == '__main__':
    main()
