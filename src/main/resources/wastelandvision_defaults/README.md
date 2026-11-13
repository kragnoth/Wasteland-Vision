# Wasteland Vision - making your own visions

A **vision** is a client-side hallucination layer: while it's on, the player *sees* different blocks,
plants, item icons/names, sky, fog and a screen-wide colour grade. The real world never changes
(hitboxes, drops, light, the server all see the real blocks).

Every vision is a folder in `config/wastelandvision/visions/`. One vision ships with the mod:

| Folder | Shows off |
|---|---|
| `soot_and_tar/` | **Soot & Tar** (light industrial pollution): every block keeps its own colour and model, slightly muted, with soft soot blotches and drips via the `stain` recolour option; a creosote wash on planks, tar in the mud, off-white snow, a brown haze, a little drifting ash (`ambient_particles`), a gentle grade shader. Its unused stand-ins (soot turf, plants, tar thorns) are there to swap back in for a heavier look |

| `thermal_print/` | **Thermal Print**: a post-shader-only vision - the frame becomes a two-colour thermal/badge print (paper white, red ink, black ink, ordered dither). No block changes. Tunables in `assets/thermal/shaders/post/thermal.json` |

The older examples (Zone `wasteland`, `dustbowl`, `irradiated`, `haunted`) are no longer installed - they
live in the mod's source under `extras/visions/` and still work: copy one into `visions/` to use it.
Every installed vision's `recolor_sets` are stitched into the block atlas whether or not it is active,
so only install the visions your pack actually uses.

New examples that ship with mod updates are installed once (tracked in `.installed_examples`).
Delete one you don't want and it stays deleted; edited visions are never overwritten.

Copy one, rename the folder (lowercase `a-z 0-9 _ . -`), edit, restart.

> **Ship the whole `config/wastelandvision/` folder in BOTH the client and the server pack.**
> Stand-ins are registered blocks, so client and server must load the same visions or players
> get a registry mismatch on join.

Keys (rebindable): **H** toggles the active vision, **J** cycles to the next vision.
`config/wastelandvision-client.toml`: `activeVision` (folder name), `startActive`.

### Locking a vision on (server/world setting)

`<world>/serverconfig/wastelandvision-server.toml` - ship it as `defaultconfigs/wastelandvision-server.toml`
in your pack so every new world gets it. NeoForge syncs it to clients, so players can't override it locally.

```toml
forceVision = true            # everyone sees forcedVision from the moment they join
forcedVision = "soot_and_tar"
creativeBypass = true         # creative/spectator players may toggle and cycle
bypassPermissionLevel = 2     # ops (or cheats-on singleplayer) may too; 5 = nobody
```

Locked players get "The haze won't lift." on the toggle/cycle keys; if a bypassing player turns it off
and then drops back to survival or loses op, it switches back on by itself.

---

## Folder layout

```
visions/myvision/
  vision.json                 <- stand-ins, rules, atmosphere (below)
  pack.mcmeta                 <- auto-created if missing; the folder is mounted as a resource pack
  assets/<any_namespace>/     <- normal resource-pack tree
     textures/block/*.png     <- stand-in textures (must live under textures/block/ to be stitched)
     shaders/post/*.json      <- optional post shader chain
     shaders/program/*        <- its programs (.json/.fsh)
     lang/en_us.json          <- optional: lang keys usable as rule "name"s
```

`vision.json` is re-read live, no restart needed:
- **rules, plants, hazards, atmosphere** - toggle the vision off and on (**H H**), or cycle to it.
- **recolor_sets** and texture/shader files - **F3+T** (they're baked into the block atlas).
- **stand-ins** still need a restart (they're registered blocks); a warning is logged if they changed.

If the file has a JSON error, a chat message says so and the last good version keeps running.

After every rebuild the mod writes **`config/wastelandvision/mappings-<vision>.txt`**: every remapped
block and which rule caught it. Check it whenever a modpack's tags surprise you.

---

## `standins` - the blocks you can show instead

```json
"standins": {
  "dead_log": { "type": "pillar", "textures": { "side": "wasteland:block/dead_log", "end": "wasteland:block/dead_log_top" } }
}
```

Registered as `wastelandvision:<vision>/<name>` (hidden: no creative tab, hidden from JEI).
Blockstates and models are generated for you from `type` + `textures`:

| type | textures | notes |
|---|---|---|
| `cube` | `all` | |
| `cube_bottom_top` | `top`, `bottom`, `side` (or `all`) | |
| `pillar` | `side`, `end` (or `all`) | copies `axis` from the real log |
| `leaves` | `all` | cutout, see-through |
| `stairs` | `top`, `bottom`, `side` (or `all`) | copies facing/half/shape |
| `slab` | `top`, `bottom`, `side` (or `all`) | copies type (double = full block) |
| `fence` | `texture` (or `all`) | copies connections |
| `wall` | `wall` (or `all`) | copies connections |
| `carpet` | `wool` (or `all`) | 1/16-high carpet layer |
| `cross` | `cross` | plants; icon uses `item/generated` |
| `crop` | `crop` (or `all`) | 4-plane crop shape (wheat-style); icon uses `item/generated` |
| `tall_cross` | `bottom`, `top` | two-high plants |
| `custom` | - | add `"shape": "<one of the above>"` for the block class, and ship your own `assets/wastelandvision/blockstates/<vision>/<name>.json` + models in the vision folder |

Anywhere a stand-in is referenced you can also use **any registered block id**, e.g. `"minecraft:dead_bush"`.

---

## `rules` - ordered, first match wins

```json
{ "if": { ...conditions... }, "then": "swap", "standin": "dead_log", "name": "Dead %s" }
```

Rule-level extras:
- `"optional": true` - if the rule can't compile (usually: it names a block from a mod that isn't
  installed), skip it quietly (info log) instead of logging an error. Use it for pack-specific rules.

### `then`
| value | effect | extra fields |
|---|---|---|
| `keep` | leave matching blocks alone (stop here) | |
| `vanish` | don't render; crosshair/Jade pass through | `name` |
| `swap` | render the stand-in, copying every property it shares by name | `standin` (a name, a block id, or a fallback list - first that exists wins), `name` |
| `native` | render another existing block built from a template; **skipped** (falls through) if that block doesn't exist | `template` (default `{namespace}:dead_{path}`), optional `path_replace` (edits `{path}` first, e.g. `{"mossy_": ""}`), `name` |
| `plant` | per-position: `plants.visible_percent` show a weighted stand-in, the rest vanish | `standins: [{id, weight}]` (other mods' block ids that aren't installed are left out of the mix), optional `tall: {id, chance}`, `item`, `visible_percent`, `name` |
| `retexture` | **keep the real model** (any shape: Dynamic Trees branches, Chipped variants, modded leaves) and swap the textures on it | `textures` and/or `recolor`, optional `tint`, `name` - see below |

`name` wraps the real item/Jade name: a literal format (`"Dead %s"`) or a lang key from your
vision's `assets/*/lang`. Omit it to keep the real name.

### `if` (all present fields must match; lists are "any of")
| field | example | meaning |
|---|---|---|
| `blocks` | `["#minecraft:logs", "minecraft:podzol"]` | ids or `#tags` |
| `not_blocks` | `["minecraft:mycelium"]` | exclusions |
| `full_cube` | `true` | outline is a full 1x1x1 block. **Use this on tag rules**: packs put odd things in tags (e.g. coral fans in `#leaves`) |
| `occludes` | `false` | the block hides what's behind it like a solid block. **Use `"occludes": false` on plant rules**, not `full_cube: false`: two-high plants (rose bush, lilac, tall grass...) have a full-cube outline |
| `class` | `"stairs"` | `stairs` `slab` `fence` `fence_gate` `wall` `door` `trapdoor` `pillar` `carpet` |
| `stripped` | `true` | `c:stripped_logs`/`c:stripped_woods` or "stripped" in the id |
| `id_contains` / `id_ends_with` | `["_wood","_hyphae"]` | id path text. Prefer `id_ends_with` for short words: `id_contains: "log"` also matches ana**log** and **log**ic |
| `not_id_contains` | `["coral"]` | exclude ids containing any of these |
| `properties` | `{"hanging": "true"}` | exact blockstate property values |
| `namespace` | `"biomesoplenty"` | only blocks from this mod |
| `not_namespaces` | `["framedblocks", "create"]` | skip blocks from these mods (use on loose `retexture` rules: some mods cast their own model classes) |

An empty/missing `if` matches everything (useful with `native`).

---

## Retexturing (`"then": "retexture"`)

Swapping needs a stand-in with the right shape. Retexturing doesn't: the block keeps its own model and
only the sprites on it change (UVs are remapped, so partial faces, other resolutions and animated
textures all work). Break particles follow too.

```json
{ "if": { "namespace": "dynamictrees" }, "then": "retexture",
  "textures": { "*:block/*_log": "haunted:block/gravewood_log", "minecraft:block/oak_planks": "haunted:block/grave_planks" },
  "recolor": ["gravewood_bark", "ghost_foliage"],
  "tint": "keep" }
```

- `textures`: source sprite glob -> target sprite. `*` matches anything; no namespace = any namespace.
  Targets must be under `textures/block/` (block atlas). First match wins.
- `recolor`: one or more **recolor sets** (below). Sprites not caught by `textures` are checked
  against each set in order.
- `tint`: `keep` (default; biome tint still applies - good for leaves) or `drop` (pre-coloured art).
- Always give retexture rules an `if`. Matched blocks whose model uses none of the mapped textures are
  pruned automatically at build time (logged as "matched but had no mapped textures"), so a loose
  id match only costs anything where it actually changes something.
- Sprite globs: same advice - `*_log*` rather than `*log*`.

### `recolor_sets` - retexture with zero art

```json
"recolor_sets": {
  "gravewood_bark": { "sprites": ["*:block/*log*", "*:block/*bark*"],
                      "palette": ["#0b0d11", "#181c23", "#272d36", "#3b434f", "#535c69"],
                      "contrast": 0.8, "gamma": 1.0, "bias": 0.0 }
}
```

Optional extras:
- `"palettes": [[...], [...]]` (+ optional `"weights"`) instead of one `palette`: with `"clump": 0` each
  texture picks one palette (seeded by its id - per-species variety); with `"clump": 4` every 4x4 pixel
  clump picks its own (mixed autumn canopies, mottled rot, patchy rust).
- `"knockout": 0.3` (needs `clump`): that share of clumps becomes transparent - sparse, dying leaves.
  Only for cutout blocks (leaves); on solid blocks it would leave holes.

### Grime: keep the block's own colour (0.12)

A palette replaces colour. To *dirty* something instead - red bricks that are still red, copper that
still glints - grade the original and/or paint a stain over it. `palette` becomes optional:

```json
"soot_masonry": { "sprites": ["*:block/*bricks*", "*:block/*concrete*"],
                  "saturation": 0.55, "brightness": 0.76,
                  "stain": { "color": "#0e0c0a", "coverage": 0.3, "strength": 0.55, "clump": 4,
                             "drips": 0.22, "drip_length": 0.55 } }
```

| key | default | meaning |
|---|---|---|
| `saturation` | 1 | saturation of the original colour (0 = grey) |
| `brightness` | 1 | brightness multiplier of the original colour |
| `mix` | 1 | with a palette: how much the gradient map replaces the graded original (0.5 = half-tinted) |
| `stain.color` | `#14110e` | the grime colour (soot, tar, rust, moss...) |
| `stain.coverage` | 0.3 | share of the texture under soft blotches |
| `stain.strength` | 0.5 | how far blotches/drips pull toward `color` |
| `stain.clump` | 2 | blotch scale in pixels (per 16px, scaled for HD packs) |
| `stain.drips` | 0 | share of pixel columns with a streak running down from the top edge |
| `stain.drip_length` | 0.6 | longest streak, as a share of the texture height |

### Plants: "self" and blight (0.17)

In a plant rule, a stand-in `"id": "self"` keeps the real plant, so a field can stay mostly alive:

```json
{ "if": { "blocks": ["minecraft:short_grass"], "occludes": false }, "then": "plant", "visible_percent": 100,
  "standins": [ { "id": "self", "weight": 72 }, { "id": "soot_grass", "weight": 18 }, { "id": "minecraft:dead_bush", "weight": 4 } ],
  "blight": { "percent": 30, "scale": 14, "visible_percent": 85,
              "standins": [ { "id": "soot_grass", "weight": 55 }, { "id": "self", "weight": 10 } ] } }
```

`"tall": {"id": "self", "chance": 75}` keeps real two-high plants. A plant rule's `blight` uses the same
pattern as tree blight (use the same percent/scale), so dead trees stand in dead undergrowth.

### Blight - a different look in part of the world (0.16)

Any retexture rule can switch to other recolour sets inside "blighted" areas:

```json
{ "if": { "blocks": ["#minecraft:logs"] }, "then": "retexture", "recolor": ["grimy_stripped", "grimy_bark"],
  "blight": { "percent": 30, "scale": 14, "recolor": ["dead_stripped", "dead_bark"] } }
```

- `percent`: share of the world's area that is blighted (calibrated, so 30 means 30%).
- `scale`: patch size in blocks. The pattern ignores height, so a whole tree - trunk and canopy - is
  either dead or alive. Use the SAME percent/scale on the log and leaf rules so they agree.
- `recolor` / `textures` / `tint`: the blighted look (same keys as the rule itself). `soot_and_tar` uses
  ashen dead bark and `dead_leaves` with `knockout` 0.62, so blighted canopies are mostly bare.

### Decals - grime that spans many blocks (0.15)

Per-texture stains repeat on every block. A decal is one large generated sheet that covers N x N blocks
(the CTM "repeat" idea): each block face shows its own slice, chosen by world position, so soot patches
and drips run across block edges and only repeat every N blocks.

```json
"decals": {
  "soot": { "blocks": 16, "pixels": 16, "shades": ["#0c0a08", "#16120e", "#211a14"],
            "coverage": 0.32, "patch_size": 3, "opacity": 0.78,
            "drips": 0.8, "drip_min": 10, "drip_max": 64, "floor_coverage": 0.45 }
},
"recolor_sets": { "soot_masonry": { "sprites": [...], "saturation": 0.85, "brightness": 0.9, "decal": "soot" } }
```

| key | default | meaning |
|---|---|---|
| `blocks` x `pixels` | 16 x 16 | sheet size; must multiply to a power of two (256 = 256x256 px). `pixels` = your texture resolution |
| `shades` | soot browns | colours, dark (dense soot) -> light (thin edges) |
| `coverage` | 0.2 | share of the wall under soot |
| `patch_size` | 2.5 | patch size in blocks |
| `opacity` | 0.8 | darkest soot's opacity (it's translucent - texture shows through) |
| `drips` / `drip_min` / `drip_max` | 0.35 / 6 / 40 | streak amount and length in pixels; they run DOWN walls across blocks |
| `floor_coverage` | 0.6 | floors/ceilings use coverage x this, and no drips |

Decals are an extra translucent face on the blocks they cover (only faces whose slice has soot).
Use them instead of a set's `stain` - doing both brings back the per-block repeat.

### Variants and world-space variation (0.14)

One stained texture used everywhere repeats visibly. Give a set variants and every placed block picks
one by its position:

```json
"soot_masonry": { "sprites": [...], "saturation": 0.85, "brightness": 0.9,
                  "stain": { ... }, "variants": 4, "variant_spread": 0.75 }
```

- `variants` (1-8, default 1): twins per texture, ordered clean -> filthy, each with its own stain pattern.
- `variant_spread` (default 0.7): how far apart they are. Variant k scales the stain (coverage, strength,
  drips) and how far saturation/brightness/mix move by `1 - spread ... 1 + spread`.
- Each variant is a full extra copy of the texture in the block atlas: 4 variants x 500 textures = 2000.

The vision-level `variation` block decides which variant a block gets, plus a smooth grime layer:

```json
"variation": { "scale": 8, "jitter": 0.35,
               "grime": { "strength": 0.22, "scale": 9, "coverage": 0.45, "color": "#2a221a" } }
```

- `scale`: size (blocks) of the dirty/clean patches; `jitter`: per-block randomness on top, so neighbours
  are similar but never identical.
- `grime`: darkens block corners toward `color` by 3D noise of their world position. Neighbouring blocks
  share corners, so the shading flows across block edges. Faint on floors/ceilings. `strength: 0` turns
  it off. Both are baked into the chunk mesh - no per-frame cost.

Blotches are wrap-around noise, so stained textures still tile seamlessly; drips start at every
block's top edge, which reads as rain-streaked soot down a wall. Clumps, knockouts and stains are
computed per animation frame row, so animated textures don't flicker.
Preview offline: `python3 tools/preview_recolor.py <vision folder> <pack>/assets/minecraft out.png`.

**Recolour first for wood.** Swapping every log to one stand-in makes every species identical and
needs fallbacks for untagged wood; recolouring keeps each species' own grain/rings/leaf shape and
covers everything whose texture matches. See `soot_and_tar/` - stand-ins are only used there for ground
and plants. Retexture rules don't need `full_cube`/`class` guards: they never change geometry.

When the block atlas is built (game start / F3+T), every texture matching `sprites` gets a
gradient-mapped twin: its own shading mapped onto `palette` (dark -> light). So *every* modded log
becomes gravewood in its own pixel pattern. `sprites` globs pick **textures** (at load); the rule's
`if` picks **blocks** (when the vision compiles). Twins live at
`wastelandvision:recolor/<vision>/<set>/<namespace>/<path>` - you never reference them directly.

`soot_and_tar` ends with three **fallback** retexture rules (trunks, foliage, woodwork) that catch
whatever their swap rules couldn't.

---

## `plants`
```json
"plants": { "visible_percent": 20, "hide_hidden_outlines": true }
```

## `hazards` - nothing dangerous is ever invisible
```json
"hazards": { "marker": "minecraft:wither_rose", "blocks": ["biomesoplenty:bramble"], "ignore": [] }
```
Whenever a `vanish` or `plant` rule would hide a block:
- **damaging** blocks (anything with a contact effect - berry bushes, wither roses, cobwebs, fire,
  modded thorns; detected automatically from the block's code, plus `blocks`) show `marker` instead
  (a stand-in name or any block id). Two-high ones get the marker on the bottom half.
- **solid or climbable** blocks (lily pads, moss carpet, vines, ladders) are never hidden - they stay real.
- `ignore` opts specific blocks out of both checks. The whole section is optional; defaults as above.

## `atmosphere` (every entry optional)
```json
"atmosphere": {
  "grass":   { "color": "#a8946a", "amount": 0.85, "brightness": 1.05 },
  "foliage": { ... }, "water": { ... }, "sky": { ... }, "fog": { ... }, "clouds": { ... },
  "fog_distance": 128,
  "ambient_particles": [ { "type": "minecraft:ash", "probability": 0.004, "sky_only": true } ],
  "post_shader": "wasteland:shaders/post/wasteland.json"
}
```
Colours are pulled toward `color` by `amount` (0-1), keeping relative brightness. `fog_distance`
caps terrain fog (blocks). `clouds` tints the cloud layer (vanilla and Sodium clouds).

`ambient_particles` drift through the air while the vision is on, client-side only. Each entry is
tried at every random position the game already samples for ambient effects (~1300 per tick within
32 blocks - the mechanism behind basalt-delta ash), and only spawns in air. `type` must be a simple
particle (`minecraft:ash`, `white_ash`, `smoke`, `spore_blossom_air`, `mycelium`...); ones that need
extra data (`dust`, `falling_dust`, `block`) are skipped with a log line. `sky_only` (default true) keeps it out of caves and buildings. For scale:
soul sand valley ash is 0.00625, basalt deltas' white ash is 0.118 (a blizzard).

`post_shader` is a vanilla post-chain JSON from your vision's assets (copy `soot_and_tar`'s:
`shaders/post/smog.json` + `shaders/program/smog.{json,fsh}`). Post passes receive `Time` (0..1,
wraps every second - handy for grain) and `InSize`; any extra uniform you declare (like `Strength` or
`Grain`) can be set per pass in the post JSON.

---

## Ideas
- **Magical**: swap leaves to glowing crystal `leaves` stand-ins, plants to `cross` mana-flowers at
  60% visible, sky/fog toward violet, a bloom-ish post shader.
- **Radiation**: see `extras/visions/irradiated/` in the mod source.
- **Haunted**: see `extras/visions/haunted/`. `native` templates worth knowing: `{namespace}:soul_{path}` (torch,
  wall_torch, lantern, campfire, fire, sand), `{namespace}:cracked_{path}` (stone/deepslate/nether/
  blackstone bricks, deepslate tiles), `{namespace}:oxidized_{path}` (copper family),
  `{namespace}:dead_{path}` (corals), `{namespace}:{path}` + `"path_replace": {"mossy_": ""}`.
