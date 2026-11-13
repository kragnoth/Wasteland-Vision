#!/usr/bin/env python3
"""
Offline sanity check for Wasteland Vision vision folders (no game needed).

usage: python3 tools/validate_visions.py <config/wastelandvision/visions>

Checks: JSON parses, ids are legal, stand-in types/texture keys, referenced textures exist in the
vision's assets/, rules reference real stand-ins, plant/tall rules are sane, post shader files exist.
Block ids/tags from OTHER mods can't be checked offline - see mappings-<vision>.txt in game.
"""
import json, os, re, sys

ID = re.compile(r'^[a-z0-9_.-]+$')
TEX_KEYS = {
    'cube': [['all']], 'cube_bottom_top': [['top', 'all'], ['bottom', 'all'], ['side', 'all']],
    'pillar': [['side', 'all'], ['end', 'all']], 'leaves': [['all']],
    'stairs': [['top', 'all'], ['bottom', 'all'], ['side', 'all']], 'slab': [['top', 'all'], ['bottom', 'all'], ['side', 'all']],
    'fence': [['texture', 'all']], 'carpet': [['wool', 'all']], 'crop': [['crop', 'all']], 'wall': [['wall', 'all']], 'cross': [['cross', 'all']], 'tall_cross': [['bottom'], ['top']],
}
SHAPES = set(TEX_KEYS)
ACTIONS = {'keep', 'vanish', 'swap', 'native', 'plant', 'retexture'}
COND = {'blocks', 'not_blocks', 'full_cube', 'occludes', 'not_namespaces', 'not_id_contains', 'class', 'stripped', 'id_contains', 'id_ends_with', 'properties', 'namespace'}
CLASSES = {'stairs', 'slab', 'fence', 'fence_gate', 'wall', 'door', 'trapdoor', 'pillar', 'carpet'}

def res_path(folder, rl, kind, ext):
    ns, _, path = rl.partition(':')
    if not path: ns, path = 'minecraft', ns
    return os.path.join(folder, 'assets', ns, kind, path + ext), ns

def check(folder):
    errs, warns = [], []
    vid = os.path.basename(folder.rstrip('/'))
    if not ID.match(vid): errs.append(f'folder name "{vid}" must be lowercase [a-z0-9_.-]')
    try:
        v = json.load(open(os.path.join(folder, 'vision.json')))
    except Exception as e:
        return [f'vision.json: {e}'], []
    st = v.get('standins', {})
    types = {}
    for name, d in st.items():
        if not ID.match(name): errs.append(f'stand-in "{name}": bad name')
        t = d.get('type', 'cube')
        if t == 'custom':
            t = d.get('shape', 'cube')
            types[name] = t
            if t not in SHAPES: errs.append(f'stand-in "{name}": unknown shape "{t}"')
            bs = os.path.join(folder, 'assets', 'wastelandvision', 'blockstates', vid, name + '.json')
            if not os.path.exists(bs): errs.append(f'stand-in "{name}": custom type needs {bs}')
            continue
        types[name] = t
        if t not in SHAPES: errs.append(f'stand-in "{name}": unknown type "{t}"'); continue
        tex = d.get('textures', {})
        for alts in TEX_KEYS[t]:
            k = next((a for a in alts if a in tex), None)
            if k is None: errs.append(f'stand-in "{name}" ({t}): missing texture "{alts[0]}"'); continue
            p, ns = res_path(folder, tex[k], 'textures', '.png')
            if not tex[k].split(':')[-1].startswith('block/'):
                warns.append(f'stand-in "{name}": texture {tex[k]} is not under textures/block/ (won\'t be in the block atlas)')
            if ns != 'minecraft' and not os.path.exists(p): errs.append(f'stand-in "{name}": texture file missing: {p}')

    def ref(r, where):
        if ':' in r or r == 'self': return None   # other mod/vanilla block, or 'self' (keep the real plant)  # other mod/vanilla block: can't verify offline
        if r not in st: errs.append(f'{where}: no stand-in named "{r}"')
        return types.get(r)

    for i, rule in enumerate(v.get('rules', []), 1):
        w = f'rule #{i}'
        a = rule.get('then')
        if a not in ACTIONS: errs.append(f'{w}: "then" must be one of {sorted(ACTIONS)}'); continue
        c = rule.get('if', {})
        for k in c:
            if k not in COND: errs.append(f'{w}: unknown condition "{k}"')
        if 'class' in c and c['class'] not in CLASSES: errs.append(f'{w}: unknown class "{c["class"]}"')
        for k in ('blocks', 'not_blocks'):
            for b in ([c[k]] if isinstance(c.get(k), str) else c.get(k, [])):
                if ':' not in b.lstrip('#'): warns.append(f'{w}: "{b}" has no namespace (assumed minecraft:)')
        if a == 'retexture':
            rec = rule.get('recolor', [])
            rec = [rec] if isinstance(rec, str) else rec
            if not rec and not rule.get('textures'): errs.append(f'{w}: retexture needs "textures" and/or "recolor"')
            for n in rec:
                if n not in v.get('recolor_sets', {}): errs.append(f'{w}: no recolor set named "{n}"')
            for src, dst in rule.get('textures', {}).items():
                p_, ns = res_path(folder, dst, 'textures', '.png')
                if not dst.split(':')[-1].startswith('block/'): warns.append(f'{w}: target {dst} not under textures/block/ (not in the block atlas)')
                elif os.path.isdir(os.path.join(folder, 'assets', ns)) and not os.path.exists(p_): errs.append(f'{w}: target texture missing: {p_}')
            if not c: errs.append(f'{w}: retexture with no "if" would wrap EVERY block model - scope it')
            if 'blight' in rule:
                b = rule['blight']
                for n in ([b['recolor']] if isinstance(b.get('recolor'), str) else b.get('recolor', [])):
                    if n not in v.get('recolor_sets', {}): errs.append(f'{w}: blight names unknown recolor set "{n}"')
                if not (0 <= b.get('percent', 30) <= 100): errs.append(f'{w}: blight.percent must be 0..100')
        if a in ('swap',) and 'standin' not in rule: errs.append(f'{w}: swap needs "standin"')
        if a == 'swap':
            sref = rule.get('standin', '')
            for x in (sref if isinstance(sref, list) else [sref]): ref(x, w)
        if a == 'plant':
            sl = rule.get('standins', [])
            if not sl: errs.append(f'{w}: plant needs a non-empty "standins" list')
            for e in sl:
                t = ref(e.get('id', ''), w)
                if t and t not in ('cross', 'crop'): warns.append(f'{w}: plant stand-in "{e["id"]}" is type {t}, usually "cross"')
            for part, pr in (('', rule), (' blight', rule.get('blight') or {})):
                for e in pr.get('standins', []) if part else []:
                    ref(e.get('id', ''), w + part)
                if 'tall' in pr:
                    t = ref(pr['tall'].get('id', ''), w + part + ' tall')
                    if t and t != 'tall_cross': errs.append(f'{w}{part}: tall stand-in must be type tall_cross (is {t})')
            if 'item' in rule:
                for x in (rule['item'] if isinstance(rule['item'], list) else [rule['item']]): ref(x, w + ' item')
        if 'if' not in rule and a not in ('native',):
            warns.append(f'{w}: no "if" - matches EVERY block')
        if a == 'plant' and c.get('full_cube') is False:
            warns.append(f'{w}: full_cube:false on a plant rule excludes two-high plants (their outline is a full cube) - use "occludes": false')
        has_tag = any(b.startswith('#') for b in ([c['blocks']] if isinstance(c.get('blocks'), str) else c.get('blocks', [])))
        if a in ('swap', 'native') and has_tag and not any(k in c for k in ('full_cube', 'class', 'occludes')):
            warns.append(f'{w}: tag rule without full_cube/class guard - odd tagging can make bad swaps')

    for name, rs in v.get('recolor_sets', {}).items():
        if name.startswith('_'): continue
        if not ID.match(name): errs.append(f'recolor set "{name}": bad name (lowercase [a-z0-9_.-])')
        if not rs.get('sprites'): errs.append(f'recolor set "{name}": needs "sprites" globs')
        pals = rs.get('palettes') or ([rs['palette']] if 'palette' in rs else [])
        grades = 'stain' in rs or 'decal' in rs or rs.get('saturation', 1) != 1 or rs.get('brightness', 1) != 1
        if not pals and not grades: errs.append(f'recolor set "{name}": needs a palette/palettes, a "stain", or saturation/brightness')
        if 'mix' in rs and not pals: warns.append(f'recolor set "{name}": "mix" does nothing without a palette')
        if not (0 <= rs.get('mix', 1) <= 1): errs.append(f'recolor set "{name}": mix must be 0..1')
        if not (1 <= rs.get('variants', 1) <= 8): errs.append(f'recolor set "{name}": variants must be 1..8')
        if 'decal' in rs and rs['decal'] not in v.get('decals', {}): errs.append(f'recolor set "{name}": no decal named "{rs["decal"]}" in "decals"')
        sn = rs.get('stain')
        if sn is not None:
            for k in sn:
                if k not in ('color', 'coverage', 'strength', 'clump', 'drips', 'drip_length', '_comment'): errs.append(f'recolor set "{name}": unknown stain key "{k}"')
            for k in ('coverage', 'strength', 'drips', 'drip_length'):
                if k in sn and not (0 <= sn[k] <= 1): errs.append(f'recolor set "{name}": stain.{k} must be 0..1')
            if 'color' in sn and not re.match(r'^#?[0-9a-fA-F]{6}$', sn['color']): errs.append(f'recolor set "{name}": bad stain colour')
        for pal in pals:
            if len(pal) < 2: errs.append(f'recolor set "{name}": each palette needs >= 2 colours')
            for col in pal:
                if not re.match(r'^#?[0-9a-fA-F]{6}$', col): errs.append(f'recolor set "{name}": bad colour "{col}"')
        if 'weights' in rs and len(rs['weights']) != len(pals): warns.append(f'recolor set "{name}": weights count != palettes count')
        if not (0 <= rs.get('knockout', 0) <= 1): errs.append(f'recolor set "{name}": knockout must be 0..1')
        if rs.get('knockout', 0) > 0 and not rs.get('clump'): warns.append(f'recolor set "{name}": knockout only works with clump > 0')
        for g in rs.get('sprites', []):
            if 'block/' not in g: warns.append(f'recolor set "{name}": glob "{g}" isn\'t under block/ - only block-atlas textures can be recoloured')
    for dn, dd in v.get('decals', {}).items():
        if dn.startswith('_'): continue
        b, px = dd.get('blocks', 8), dd.get('pixels', 16)
        if (b * px) & (b * px - 1): errs.append(f'decal "{dn}": blocks x pixels must be a power of two (is {b * px})')
        if b * px > 512: warns.append(f'decal "{dn}": {b * px}px sheets are large - 128 or 256 is plenty')
    hz = v.get('hazards', {})
    if 'marker' in hz and ':' not in hz['marker'] and hz['marker'] not in st:
        errs.append(f'hazards.marker: no stand-in named "{hz["marker"]}"')
    for i, ap in enumerate(v.get('atmosphere', {}).get('ambient_particles', []), 1):
        if 'type' not in ap: errs.append(f'ambient_particles #{i}: needs "type" (a simple particle id, e.g. minecraft:ash)')
        if not (0 < ap.get('probability', 0.005) <= 1): errs.append(f'ambient_particles #{i}: probability must be in (0, 1]')
        if ap.get('probability', 0.005) > 0.05: warns.append(f'ambient_particles #{i}: probability {ap["probability"]} is heavy (~1300 samples/tick; basalt deltas use 0.118)')
    sh = v.get('atmosphere', {}).get('post_shader')
    if sh:
        p, ns = res_path(folder, sh, '', '')
        p = os.path.join(folder, 'assets', ns, sh.split(':', 1)[1])
        if not os.path.exists(p): errs.append(f'post_shader file missing: {p}')
        else:
            post = json.load(open(p))
            for ps in post.get('passes', []):
                n = ps['name']
                if ':' in n:
                    pns, pp = n.split(':', 1)
                    pj = os.path.join(folder, 'assets', pns, 'shaders', 'program', pp + '.json')
                    if not os.path.exists(pj): errs.append(f'shader pass {n}: missing {pj}')
                    else:
                        prog = json.load(open(pj))
                        fr = prog.get('fragment', '')
                        if ':' in fr:
                            fns, fp = fr.split(':', 1)
                            ff = os.path.join(folder, 'assets', fns, 'shaders', 'program', fp + '.fsh')
                            if not os.path.exists(ff): errs.append(f'shader {pj}: missing fragment {ff}')
    return errs, warns

root = sys.argv[1] if len(sys.argv) > 1 else 'config/wastelandvision/visions'
bad = 0
for d in sorted(os.listdir(root)):
    f = os.path.join(root, d)
    if not os.path.isfile(os.path.join(f, 'vision.json')): continue
    e, w = check(f)
    print(f'== {d}: {len(e)} error(s), {len(w)} warning(s)')
    for x in e: print('  ERROR', x)
    for x in w: print('  warn ', x)
    bad += len(e)
sys.exit(1 if bad else 0)
