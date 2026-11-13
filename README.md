# Wasteland Vision (v0.23.2) - NeoForge 1.21.1

**How to make visions: [docs/Making-Visions.md](docs/Making-Visions.md)**

A data-driven, client-side **hallucination layer**: while a *vision* is on, the player sees different
blocks, plants, item icons/names, sky, fog and a screen colour grade. The real world never changes.
**H** toggles, **J** cycles visions.

Visions live in `config/wastelandvision/visions/<id>/`. The bundled vision is **Soot & Tar**
(`soot_and_tar`, light industrial pollution - the Steamlands vision), installed on first launch from
`src/main/resources/wastelandvision_defaults/`. The older examples (Zone `wasteland`, `dustbowl`,
`irradiated`, `haunted`) moved to `extras/visions/` - not bundled, not installed; copy one into
`config/wastelandvision/visions/` to use it. The pack-dev guide is
`src/main/resources/wastelandvision_defaults/README.md` (copied to `config/wastelandvision/README.md`).

## Architecture
- `vision/VisionManager` (both sides, mod construction): installs defaults, parses every `vision.json`,
  registers each stand-in as a hidden block+item `wastelandvision:<vision>/<name>` (class per `type`).
- `client/VisionPacks` (AddPackFindersEvent): mounts each vision folder as an always-on resource pack and
  generates blockstates/models for stand-ins into `config/wastelandvision/.generated/` from vanilla
  templates (`wastelandvision_templates/`).
- `client/SubstitutionTable`: compiles the active vision's ordered `rules` into flat per-BlockState /
  per-Item arrays (+ writes `mappings-<vision>.txt`). Rebuilt on tag sync, toggle-on and cycle.
- `client/PlantHallucination`: per-position weighted plant stand-ins (`then: plant`).
- `client/Retexturer` (`then: retexture`): keeps the real model, remaps quad UVs onto target sprites (cached
  per sprite and per quad). `client/RecolorSource`: block-atlas sprite source generating gradient-mapped
  twins for `recolor_sets` at stitch time. `TerrainParticleMixin` remaps break/run particles.
- `Atmosphere` + `ColorGrade` + `BiomeMixin`: tints, fog, post shader from the vision's `atmosphere`.
- Render hooks: `BlockModelShaperMixin` -> `WastelandModel` wrapper only for remapped states, while active,
  and only for world meshers (see below); `ItemRendererMixin` icons; `ItemStackMixin` names (render thread only);
  `BlockStateBaseMixin` empty outlines for vanished blocks; Jade + JEI plugins.

## 0.23.3 - ghost broadcasts, auto-versioning

- Occult Foundry: four 'ghost broadcast' clips play in the groves like the waltz (one at a time, 45-150 s
  gaps, cut out by ghosts): FDR's Fireside Chat 1 (1933, US federal work - public domain), loud opening
  removed, chopped into shuffled/reversed syllable-sized grains (no word or place name survives), pitched
  down, through an AM-radio chain with fading, static, a drifting whistle and a tuning sweep.
  tools/gen_occult_radio.py <source.ogg> regenerates them.
- build.gradle: every `build`/`jar`/`assemble` bumps the patch version in gradle.properties and
  neoforge.mods.toml (`-PnoBump` to skip).

## 0.23.2 - waltz phrases with silences

The grove waltz is no longer one endless loop: five phrases (Strauss 'The Blue Danube', Rosas 'Over the
Waves', Brahms Waltz Op.39 No.15 - all public domain), each with its own tempo, detune and wow, two
winding down like a spent gramophone and one stuck in a skipping groove. The game plays one at a time,
never the same twice running, with 30-120 s of silence between; a ghost phasing in stops the phrase and
restarts the silence. New ambience loop options: `phrases` (list of sound events) and `gap_seconds`.

## 0.23.1 - headphone-safe audio

Occult Foundry's sounds are now loudness-matched (EBU R128, measured on the encoded OGGs) with true peaks
soft-limited well below full scale (loops -31/-28 LUFS, whispers -29, bell -25; peaks <= -9 dBTP), so the
crackle pops, whisper hiss and bell strike can't spike in headphones. In-game volumes rebalanced to match;
overall a few dB lighter than 0.22/0.23.

## 0.23.0 - Sky omens

New optional vision.json block `"sky_omens"` (client side; SkyOmens.java on the AFTER_SKY and AFTER_WEATHER
render stages, drawn in the vanilla sky frame so any resource pack's sun and moon line up). Each in-game
day rolls at most one omen from a hash of the day number, so every player with the vision sees the same
omen on the same day with no syncing:
- `smog_sun` (common) - the sun becomes a dull orange disc you could stare at, and the day goes sepia.
- `twin_suns` (rare) - from The King in Yellow (Chambers, 1895): a second, paler sun sets beside the real
  one; that night black stars with cold rims rise and a small violet moon circles the moon.
- `eclipse` (rarest) - over `duration_seconds` a black disc slides across the sun, a corona flares at
  totality and the world goes dim and cold; while total, ghosts walk everywhere and the grove ambience
  (waltz and all) plays wherever you stand.
`"force": "eclipse" | "twin_suns" | "smog_sun" | "none"` pins an omen for testing. Omens hide behind rain.

## 0.22.0 - Grove ambience, midnight bell, foundry smoke

Three new optional vision.json blocks, all client side (no mixins; GroveAmbience.java and SmokeColumns.java
on NeoForge's client tick / AFTER_PARTICLES render events):

- `"ambience"` - looping sounds whose volume follows how deep the player stands in the haunt zone (the
  ghosts' zone; everywhere if the vision has no ghosts), `duck_on_ghost` loops that cut out the moment a
  ghost phases in and creep back, positional one-shots a few blocks around the player, and `midnight`:
  when day time ticks past 18000 a distant bell tolls N times, then ghosts may walk anywhere (twice as
  many, spawning faster) for `ghost_surge_seconds`. Everything plays in the AMBIENT sound category.
- `"smoke_columns"` - world-fixed plumes of foundry smoke on the horizon (one chance per `cell`), drawn as
  camera-facing puffs with the vanilla campfire smoke sprites (resource packs restyle them), rising,
  bending downwind and spreading; they live in a band just inside the fog wall (whatever the render
  distance) and dissolve as you approach. At night the base of each plume glows like a furnace mouth.
- Occult Foundry uses both. Its sounds are synthesised from scratch by tools/gen_occult_audio.py: worn
  78rpm crackle, a low machine hum, 'The Blue Danube' (Strauss, 1866, public domain) on a detuned music box
  through a phonograph (the loop that ducks for ghosts), four whispers, and a distant muffled bell.

## 0.21.1 - undead ghosts

`ghosts.mobs` now accepts entity type tags (`#minecraft:undead`) alongside ids/globs, and `land_only`
drops flyers and swimmers at spawn. Occult Foundry now haunts with land undead only: every mob in
#minecraft:undead (vanilla: zombie/husk/drowned/zombie villager/zombified piglin/skeleton/stray/bogged/
wither skeleton/zoglin/skeleton & zombie horse; modded undead join if their mod tags them).

## 0.21.0 - Ghosts

New optional vision.json block `"ghosts"` (client side only, no mixins - one new class, Ghosts.java, on
NeoForge's client tick and AFTER_ENTITIES render events). Random mob types from the pack (monster/creature/
ambient, bosses and huge mobs excluded, overridable with `mobs`/`exclude`/`categories`) are built with
EntityType.create() but never added to the level, so the server, other players, Jade, targeting and
collision never know about them. They wander inside the vision's blight zone (same percent/scale as the
haunted-grove tree rules) and only phase in while the player looks toward them - within `radius`, inside
`view_angle` degrees of the crosshair, with line of sight - fading out when you look away or they drift out
of the zone. Every textured render layer is redrawn as translucent, tinted (`tint`), faded (`alpha`) and
shimmering (`flicker`); a mob type that fails client-side is dropped silently. Ground height is found by
walking down from the synced MOTION_BLOCKING heightmap (MOTION_BLOCKING_NO_LEAVES isn't sent to clients and
reads as the world floor). Occult Foundry ships with ghosts on in its haunted groves.

## 0.20.0 - Occult Foundry vision

New bundled vision `occult_foundry`: an occult steampunk/dieselpunk world - lamplit brass (fresh copper
and gold), copper keeping its verdigris, rust-streaked iron and steel, stone read as poured concrete with
damp streaks, brick soot-darkened, varnished oxblood/walnut timber, mud gone to tar, cool slate haze with
drifting ash and the odd rising soul. About 18% of woodland is haunted: spectral groves of bleached,
frost-pale trees with cold teal/violet/bone canopies and pale wisp blooms. Rust & Rebar (both ids),
Tarbound and Create are left untouched. Optional: vanilla glass/panes show as Create framed glass.
Post shader `occult:grade` (split tone teal shadows / brass highlights, cool-hue mute, violet vignette).
Tuned against Excalibur (Maffhew, V26.3_01); the four stand-in textures (tar_pool, tar_soil, wisp_bloom,
brass_thorns) are derived from Excalibur's sprites with the author's permission - tools/gen_occult.py,
tools/build_occult_vision.py regenerate them.

## 0.19.2 - actual trigger of the 0.19.0 crash

The loop was the hazard marker: wither roses are small flowers (hidden/replaced by flower plant
rules) AND damaging, so the hazard pass swapped them for the marker - minecraft:wither_rose, i.e.
themselves. Any vision using wither_rose as its marker (wasteland, dustbowl, haunted, irradiated)
crashed as soon as a wither rose was meshed. The marker block is now left as the real block.

## 0.19.1 - crash fix

Fixed a StackOverflowError during chunk meshing (Sodium or vanilla) when a swap's stand-in was itself
remapped by another rule (A -> B with B -> A, or a glob that also matches its own stand-in): the
wrapper re-entered itself forever in getRenderTypes. Stand-in models are now always unwrapped, so a
substitution is exactly one level deep. Load now logs a warning listing any such chained swaps so the
rules can be tightened. The mod's reported version (mods.toml) was stuck at 0.18.0; now correct.

## 0.19.0 - Thermal Print: Land two-record colour

New default `Mode` 1 for `thermal_print`, based on Edwin Land's two-colour experiment (a red-filter
record shown in red light plus a green-filter record shown in white light reads as near full colour,
because the brain judges colour from long/short-wave ratios across the scene). Paper = white light,
red ink = red light, black = none. Per pixel the shader solves for the paper/red/black shares that
reproduce the long-wave (R) and short-wave (0.7G + 0.3B) brightness using the actual ink colours
(least-squares onto the nearest edge when out of gamut), then dithers all three with one Bayer
threshold. So every hue gets a continuous ink mix: oranges/pinks/yellows/skin/dirt get red+paper,
deep reds red+black, greens/blues paper+black (read cool next to red). New uniforms: `Mode`
(1 Land, 0 the 0.18 warmth split), `Chroma` (1.6, long/short-wave separation) and `Surround` (0.6,
retinex: each pixel's red/white balance pushed away from its 4-tap neighbourhood, strengthening the
colour edges the eye fills colour in from). Still one pass, 5 samples.

## 0.18.0 - Thermal Print vision

New bundled vision `thermal_print`: post-shader only (no stand-ins, rules, tints or fog). One pass
(`assets/thermal/shaders/program/thermal.fsh`) remaps every pixel to exactly PaperWhite / InkRed /
InkBlack: green/blue-weighted luminance + unsharp mask + contrast/gamma, ordered Bayer dither (4x4 or
8x8 by bit interleaving, screen space, no shimmer) for ink vs paper, then an offset Bayer threshold on a
warmth mask (red dominance + skin/orange, saturation-gated) for red vs black. Tunables are uniforms in
`assets/thermal/shaders/post/thermal.json` (F3+T to reload).

## 0.17.1 - Fix: front faces darker than tops

Vertex grime was weighted by face direction (30% on UP faces), so inside grime patches block sides
darkened ~3x more than their tops. Grime is now uniform per face; soot_and_tar's grime is weaker (0.1)
and its "dust" decal is light ash-grey instead of dark brown.

## 0.17.0 - Mixed plants, plant blight, heavier pollution

- Plant rules: a stand-in id of `"self"` keeps the real plant (also for `"tall"`), so fields can be mostly
  living with dead tufts mixed in. `PlantHallucination.resolve` returns the real state; `WastelandModel`
  renders it with the original model.
- Plant rules accept `"blight"` too (same percent/scale as the tree rules): blighted areas get their own
  plant mix, so dead trees stand in dead undergrowth.
- soot_and_tar: lighter, readable dead-plant textures; dead leaves re-done (4px clumps, two close warm
  palettes, no speckle); more ash fall, browner haze, denser soot sheets.

## 0.16.0 - Blight (pollution die-off)

A retexture rule may carry `"blight": {"percent": 30, "scale": 14, "recolor": [...], "tint": "drop"}`: a
second `Retexturer` used inside blighted areas. `Variation.blight` is 2D value noise over x/z only, so a
tree's trunk and canopy always agree; `Variation.blightThreshold` samples the field so exactly `percent`%
of the area is blighted. `Retexturer.at(pos)` picks base or blight for remapping and decals.

## 0.15.0 - World-space decals

`client/Decals`: a vision's `"decals"` are generated sheets (default 16x16 blocks, 256x256 px; one for
walls with downward drips, one for floors) stitched into the block atlas by `RecolorSource`. A recolour
set with `"decal": "<name>"` makes every axis-aligned face of its blocks get an extra TRANSLUCENT quad
showing that face's slice of the sheet, picked by world position (UVs from vertex world positions, so
slabs/stairs line up; X-facing walls use a half-sheet offset). Soot therefore flows continuously across
block edges and repeats only every N blocks. `WastelandModel` adds the translucent layer to such blocks'
render types (only while the vision is on and only in chunk meshing); faces whose slice is empty get no
quad. Verified on vanilla and Sodium 0.8.13.

## 0.14.0 - Per-block variation + the vanilla mesher fix

- **FIX (important):** `SectionCompilerMixin` used a name-only selector (`"compile"`), which Mixin binds to
  the FIRST overload only (4-arg). NeoForge's RebuildTask calls the 5-arg overload directly, so the
  vanilla chunk mesher was never hooked: swaps, retextures and per-position plants were invisible on
  the vanilla renderer (only biome tints/fog/shader/items worked). Both overloads are now targeted by
  full descriptor (`require = 1`), with a per-thread depth count for the nesting. Verified in game on
  vanilla and on Sodium 0.8.13.
- **Texture variants:** a recolour set's `"variants": N` (1-8) builds N twins per texture, from clean to
  filthy (`"variant_spread"` scales the stain and grade per variant), each with its own stain pattern.
  `Retexturer` picks one per block position (`client/Variation.pick`: smooth world noise + per-block
  jitter) via the block position stored in ModelData (`WastelandModel.RETEX_POS`, set in getModelData).
- **World-space vertex grime:** vision `"variation": {"grime": {...}}` darkens quad corners by 3D noise of
  their world position (shared corners match, so it flows across block edges); faint on up/down faces.
- Stains: wider smoothstep blotch edges, fainter drips, no adjacent drip columns.

## 0.13.0 - Server-locked vision

`WVServerConfig` (SERVER config: `<world>/serverconfig/wastelandvision-server.toml`, synced to clients by
NeoForge; ship it in `defaultconfigs/` for new worlds): `forceVision`, `forcedVision`, `creativeBypass`,
`bypassPermissionLevel`. `WastelandClient.enforceLock` runs every client tick: a locked player is kept
in the forced vision (turned on at join, re-forced after losing creative/op), and the toggle/cycle keys
show "The haze won't lift." instead. Client-side by nature: it stops normal players, not a modified client.

## 0.12.1 - Soot & Tar retuned to light pollution (no wholesale recolours or plant hiding; softer shader)

## 0.12.0 - Soot & Tar

- **Grime recolours** (`RecolorSource`): `palette` is optional; `saturation`/`brightness` grade the
  original colour, `mix` blends a palette over it, and `stain` paints soft wrap-around soot blotches
  plus top-edge drips. Noise is per animation frame row (fixes clump/knockout flicker on animated
  textures) and scales with texture resolution.
- **Atmosphere**: `clouds` tint and `ambient_particles` (`mixin/ClientLevelMixin`: `getCloudColor`,
  and `doAnimateTick` TAIL - piggybacks vanilla's ambient sampling, one volatile read when off).
- **Optional-mod friendly rules** (`SubstitutionTable`): `standin`/`item`/`hazards.marker` accept a
  fallback list; plant lists drop other mods' blocks that aren't installed; `"optional": true` turns a
  rule's compile failure into an info line.
- `activeVision` now defaults to `soot_and_tar`.
- Tools: `gen_soot.py` (stand-in textures), `preview_recolor.py` (offline before/after sheet of a
  vision's recolour sets - a port of the Java code); `validate_visions.py` knows the new keys.

## Live vision.json reload (0.11.1)

`VisionManager.reload(vision)` re-reads a vision's `vision.json` and swaps the cached `Vision` record
(stand-ins kept - they're registry entries; a changed `standins` block logs a restart warning; a parse
error keeps the last good version and posts a chat message). Called from `WastelandClient.compileSelected()`
(toggle-on, cycle, tag sync, after F3+T) and `RecolorSource.run()` via `reloadAll()` before the atlas stitch.

## Opt-in hallucination (0.9)
Block models are only swapped for callers inside a world mesher; everyone else (map mods, LOD mods,
Create's cached wheels/contraptions/schematics, unknown mods) always gets the real model.
- `WastelandState.meshing()` per-thread flag, set for the duration of:
  - vanilla `SectionCompiler#compile` (`SectionCompilerMixin`, main config): no-Sodium terrain, Sable's vanilla renderer
  - Sodium `ChunkBuilderMeshingTask#execute` (`compat/SodiumMeshingMixin`, verified 0.8.13)
  - Sable `SubLevelMeshBuilder#compile` (`compat/SableMeshingMixin`, verified 2.0.6)
- `wastelandvision.compat.mixins.json` is optional and `@Pseudo`: packs without Sodium/Sable skip it.
- Terrain particles remap via `TerrainParticleMixin` (only their own sprite lookups).
- If Sodium is loaded but its hook never fires while active, a one-time warning is logged + shown.
- Not hallucinated (real by design): falling/held blocks, block entities, Create contraptions/Flywheel,
  Embeddium terrain (would need its own hook).

## Tools
- `python3 tools/validate_visions.py config/wastelandvision/visions` - offline check of vision folders.
- `python3 tools/gen_soot.py <pack>/assets/minecraft [out]` - regenerates the soot_and_tar stand-in textures.
- `tools/wastelandvision_audit.py` - drop into an instance's game folder (Prism: `instances/<name>/minecraft/`) and run
  `python3 wastelandvision_audit.py`. No dependencies. Reads vanilla (auto-found in Prism/vanilla launcher
  libraries), NeoForge's c: tags, every mod jar, enabled resource packs, KubeJS data and the game-written
  `mappings-<vision>.txt`; writes `wastelandvision_audit/report.html` (before/after of everything the vision
  changes, gaps, risks), `suggested_rules.json` and `blocks.csv`.
- `python3 tools/preview_recolor.py <vision folder> <pack>/assets/minecraft [out.png] [n]` - before/after sheet of every recolour set.
- `python3 tools/gen_zone.py <pack>/assets/minecraft [out]` - regenerates the wasteland (Zone) example textures (extras/).
- `python3 tools/gen_haunted.py <pack>/assets/minecraft [out]` - regenerates the haunted example textures.
- `python3 tools/gen_textures.py <pack>/assets/minecraft [out]` - regenerates the dustbowl example
  textures from a reference resource pack.

## Build
    ./gradlew build
    ./gradlew runClient

## Known limits
- Client and server must have identical vision folders (stand-ins are registry entries).
- Block entities (BER), fluids and entities aren't swapped. Break particles use the real block.
