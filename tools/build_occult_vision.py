#!/usr/bin/env python3
"""Writes visions/occult_foundry/vision.json (shares decal/variation defaults with soot_and_tar).
Tuned against Excalibur (Maffhew, V26.3_01): palette mixes are kept moderate so Excalibur's own
shading and colour depth show through; stand-in textures come from tools/gen_occult.py run on Excalibur."""
import json

B = 'src/main/resources/wastelandvision_defaults/visions/'
s = json.load(open(B + 'soot_and_tar/vision.json'))
EX = ["create", "framedblocks", "copycats", "chipped_framed", "sophisticatedstorage", "sophisticatedbackpacks"]
HAUNT = {"percent": 18, "scale": 18}

FOLIAGE = ["*:block/*leaves*", "*:block/*foliage*", "*:block/*needles*"]
BARK = ["*:block/*_log*", "*:block/*_bark*", "*:block/*_wood*", "*:block/*_branch*", "*:block/*_stem*", "*:block/*/log/*"]
STRIP = ["*:block/stripped*", "*:block/*/stripped*"]

V = {}
V['_readme'] = (
    "Occult Foundry - an occult steampunk/dieselpunk world: lamplit brass and copper, poured concrete, "
    "rust-streaked iron, varnished timber, tar welling up through the mud, and spirits in the air. Like Soot & Tar "
    "it is a recolour, not a replacement: every block keeps its own model and pattern. About 18% of woodland is "
    "haunted - spectral groves of bleached, frost-pale trees with cold-lit canopies and wisp blooms underfoot. "
    "Rust & Rebar (both mod ids), Tarbound and Create are left exactly as they are, since they're already on theme. "
    "Tune: each set's mix/saturation/brightness, the decals' opacity, the haunt percent (blight blocks on the "
    "log/leaf/flower rules - keep them identical), and the grade uniforms in assets/occult/shaders/post/grade.json.")
V['name'] = "Occult Foundry"
V['description'] = ("Brass, copper and concrete under lamplight; rust-streaked iron, tar in the mud, spectral groves, "
                    "and spirits drifting through a cold slate haze.")
V['standins'] = {
    "tar_pool": {"type": "cube_bottom_top", "textures": {"top": "occult:block/tar_pool", "side": "occult:block/tar_soil", "bottom": "occult:block/tar_soil"}},
    "wisp_bloom": {"type": "cross", "textures": {"cross": "occult:block/wisp_bloom"}},
    "brass_thorns": {"type": "cross", "textures": {"cross": "occult:block/brass_thorns"}},
}

sets = {"_comment": "Inside a rule's recolor list the first set whose sprites match a texture wins."}
sets['damp_growth'] = dict(s['recolor_sets']['grimy_growth'], saturation=0.8, brightness=0.9, decal="damp",
                           _comment="Grass, dirt, moss and plants: a little cooler and duller. Biome tint applies (atmosphere.grass).")
sets['nightshade_foliage'] = {"_comment": "Living canopies: deep, cool green.", "sprites": FOLIAGE, "saturation": 0.72, "brightness": 0.84,
                              "stain": {"color": "#141a1c", "coverage": 0.22, "strength": 0.4, "clump": 4}, "variants": 4, "variant_spread": 0.7}
sets['dark_stripped'] = {"sprites": STRIP, "saturation": 0.8, "brightness": 0.85, "variants": 2, "variant_spread": 0.5}
sets['dark_bark'] = {"sprites": BARK, "saturation": 0.78, "brightness": 0.82, "variants": 2, "variant_spread": 0.5, "decal": "soot"}
sets['lacquered_wood'] = {"_comment": "Varnished oxblood-to-walnut timber over each wood's own grain: Victorian workshop panelling.",
                          "sprites": ["*:block/*planks*", "*:block/*_door_top*", "*:block/*_door_bottom*", "*:block/*_trapdoor*", "*:block/barrel*", "*:block/*/planks/*"],
                          "palette": ["#160a07", "#2a130c", "#3f2013", "#55301c", "#6c4227"], "mix": 0.3, "contrast": 0.8,
                          "saturation": 0.9, "brightness": 0.9, "variants": 2, "variant_spread": 0.45, "decal": "soot"}
sets['old_brick'] = {"_comment": "Brick, tile and terracotta keep their colour, soot-darkened.",
                     "sprites": ["*:block/*bricks*", "*:block/*brick", "*:block/*_tiles*", "*:block/*terracotta*", "*:block/packed_mud", "*:block/*chiseled_stone*"],
                     "saturation": 0.85, "brightness": 0.88, "variants": 2, "variant_spread": 0.5, "decal": "soot"}
sets['cast_concrete'] = {"_comment": "Stone reads as poured concrete: cool grey, its grain kept, damp streaks from the decal.",
                         "sprites": ["*:block/stone", "*:block/smooth_stone*", "*:block/*cobblestone*", "*:block/*andesite*", "*:block/*diorite*",
                                     "*:block/*granite*", "*:block/*tuff*", "*:block/calcite", "*:block/*stone_decoration/*"],
                         "palette": ["#2e3132", "#454a4b", "#5d6263", "#777b7b", "#8f9291"], "mix": 0.5, "contrast": 0.75, "saturation": 0.65,
                         "variants": 3, "variant_spread": 0.6, "decal": "damp"}
sets['verdigris'] = {"_comment": "Weathered copper keeps (and slightly deepens) its patina - the spirits' colour.",
                     "sprites": ["*:block/*exposed*", "*:block/*weathered*", "*:block/*oxidized*"], "saturation": 1.05, "brightness": 0.92,
                     "variants": 2, "variant_spread": 0.5}
sets['lamp_brass'] = {"_comment": "Fresh copper and gold read as lamplit brass.",
                      "sprites": ["*:block/*copper*", "*:block/gold_block", "*:block/*brass*"],
                      "palette": ["#3a240e", "#634116", "#8f6423", "#b88b39", "#d9b462"], "mix": 0.42, "contrast": 0.8, "variants": 2, "variant_spread": 0.4}
sets['rusted_iron'] = {"_comment": "Iron and steel go to rust, with orange run-off streaks.",
                       "sprites": ["*:block/iron_block", "*:block/iron_bars", "*:block/chain", "*:block/iron_door*", "*:block/iron_trapdoor", "*:block/*steel*",
                                   "*:block/*sheetmetal*", "*:block/metal/*", "*:block/metal_decoration/*"],
                       "palette": ["#24110a", "#43200f", "#663215", "#87481f", "#a3622f"], "mix": 0.5, "contrast": 0.85,
                       "stain": {"color": "#3b1a0b", "coverage": 0.28, "strength": 0.45, "clump": 3, "drips": 0.35, "drip_length": 0.6},
                       "variants": 3, "variant_spread": 0.6, "decal": "rust"}
sets['sooty_sand'] = dict(s['recolor_sets']['sooty_sand'], saturation=0.75, brightness=0.86)
sets['grimy_glass'] = dict(s['recolor_sets']['grimy_glass'],
                           stain={"color": "#1a1c1e", "coverage": 0.28, "strength": 0.45, "clump": 4, "drips": 0.1, "drip_length": 0.4})
sets['grimy_cloth'] = s['recolor_sets']['grimy_cloth']
sets['soot_crops'] = s['recolor_sets']['soot_crops']
sets['cold_snow'] = dict(s['recolor_sets']['grimy_snow'], _comment="Slightly grey-blue, lightly flecked snow.",
                         palette=["#8a8e92", "#a1a5a9", "#b6babd", "#c9ccce", "#d8dadc"],
                         stain={"color": "#3a3c40", "coverage": 0.2, "strength": 0.3, "clump": 4})
sets['spectral_bark'] = {"_comment": "Haunted groves: bleached, frost-pale bark (haunt areas only).", "sprites": BARK,
                         "palette": ["#2b2f36", "#4a505a", "#6d7480", "#959ca6", "#bcc2c8"], "contrast": 0.85}
sets['spectral_stripped'] = {"sprites": STRIP, "palette": ["#5b616b", "#7b828c", "#9aa1aa", "#b8bec5", "#d3d8dc"], "contrast": 0.8}
sets['spectral_leaves'] = {"_comment": "Haunted canopies: cold spirit-light - mostly pale teal, some violet and bone-white trees.",
                           "sprites": FOLIAGE,
                           "palettes": [["#1c2b2e", "#2f4a4d", "#4a6e6f", "#6c9693", "#97bdb6"],
                                        ["#221d30", "#3a3150", "#584b74", "#7c6d98", "#a698bd"],
                                        ["#2a2b2c", "#47494b", "#6a6c6e", "#909294", "#b9bbbc"]],
                           "weights": [5, 3, 2], "clump": 0, "contrast": 0.85, "gamma": 0.9}
V['recolor_sets'] = sets

V['decals'] = {
    "_comment": "World-space grime sheets spanning 'blocks' x 'blocks' blocks; sets opt in with \"decal\". See soot_and_tar for the full notes.",
    "soot": dict(s['decals']['soot'], coverage=0.32, opacity=0.7),
    "dust": dict(s["decals"]["dust"], shades=["#5f6062", "#74767a", "#8a8c90"], opacity=0.35),
    "damp": {"blocks": 16, "pixels": 16, "shades": ["#1d2422", "#262e2c", "#303836"], "coverage": 0.3, "patch_size": 4, "drips": 0.7,
             "drip_min": 12, "drip_max": 64, "floor_coverage": 0.3, "opacity": 0.55},
    "rust": {"blocks": 16, "pixels": 16, "shades": ["#3a1708", "#55240e", "#733515"], "coverage": 0.22, "patch_size": 3, "drips": 0.9,
             "drip_min": 10, "drip_max": 56, "floor_coverage": 0.25, "opacity": 0.7},
}
V['plants'] = {"visible_percent": 100, "hide_hidden_outlines": True}
V['hazards'] = {"_comment": "Anything a rule would hide but that still hurts shows brass thorns; solid or climbable blocks are never hidden.",
                "marker": "brass_thorns", "blocks": [], "ignore": []}
V['atmosphere'] = {
    "grass": {"color": "#5d6d55", "amount": 0.35, "brightness": 0.95},
    "foliage": {"color": "#4c5e4e", "amount": 0.38, "brightness": 0.92},
    "water": {"color": "#24363a", "amount": 0.55, "brightness": 0.85},
    "sky": {"color": "#666c7c", "amount": 0.5},
    "fog": {"color": "#5c6270", "amount": 0.55},
    "clouds": {"color": "#4f5462", "amount": 0.6},
    "ambient_particles": [
        {"type": "minecraft:ash", "probability": 0.0035, "sky_only": True},
        {"type": "minecraft:white_ash", "probability": 0.0008, "sky_only": True},
        {"type": "minecraft:soul", "probability": 0.00035}],
    "post_shader": "occult:shaders/post/grade.json",
    "fog_distance": 176,
}
V['variation'] = dict(s['variation'], grime=dict(s['variation']['grime'], color="#1f2324", strength=0.1))
V['_haunt'] = ("Haunted groves reuse the 'blight' mechanism: 2D x/z noise so a whole tree agrees; 'percent' of the world's area, "
               "'scale' = grove size in blocks. Keep percent/scale identical on the log, leaf and flower rules.")

def ex(cond):
    return dict(cond, not_namespaces=EX)

rules = [
    {"_comment": "The Nether keeps its fungus wood.", "if": {"id_contains": ["crimson", "warped"]}, "then": "keep"},
    {"_comment": "Already on theme - left exactly as made.", "if": {"namespace": "rustandrebar"}, "then": "keep"},
    {"if": {"namespace": "rustandrebar_blocks"}, "then": "keep"},
    {"if": {"namespace": "tarbound"}, "then": "keep"},
    {"_comment": "Create's brass, copper and andesite machinery stays as made.", "if": {"namespace": "create"}, "then": "keep"},
    {"_comment": "Tar wells up through the mud.", "if": {"blocks": ["minecraft:mud", "minecraft:muddy_mangrove_roots"], "full_cube": True},
     "then": "swap", "standin": "tar_pool", "name": "Tar-soaked %s"},
    {"_comment": "Built-in dead forms (dead coral...).", "then": "native", "template": "{namespace}:dead_{path}", "name": "Dead %s"},
    {"_comment": "Flowers: the odd wisp bloom in the open, mostly wisp blooms in haunted groves. 'self' keeps the real flower.",
     "if": {"blocks": ["#minecraft:small_flowers", "#minecraft:tall_flowers"], "occludes": False, "not_blocks": ["minecraft:wither_rose"]},
     "then": "plant", "visible_percent": 100,
     "standins": [{"id": "self", "weight": 88}, {"id": "wisp_bloom", "weight": 12}], "tall": {"id": "self", "chance": 90},
     "blight": dict(HAUNT, visible_percent=100, standins=[{"id": "wisp_bloom", "weight": 65}, {"id": "self", "weight": 35}],
                    tall={"id": "self", "chance": 60})},
    {"_comment": "Crops: every growth stage stays readable.", "if": ex({"blocks": ["#minecraft:crops"], "occludes": False}),
     "then": "retexture", "recolor": "soot_crops"},
    {"if": {"blocks": ["#minecraft:leaves"]}, "then": "retexture", "recolor": "nightshade_foliage", "tint": "keep",
     "blight": dict(HAUNT, recolor=["spectral_leaves"], tint="drop")},
    {"if": {"blocks": ["#minecraft:logs"]}, "then": "retexture", "recolor": ["dark_stripped", "dark_bark"],
     "blight": dict(HAUNT, recolor=["spectral_stripped", "spectral_bark"])},
    {"if": ex({"blocks": ["#minecraft:planks", "#minecraft:wooden_stairs", "#minecraft:wooden_slabs", "#minecraft:wooden_fences",
                          "#minecraft:fence_gates", "#minecraft:wooden_doors", "#minecraft:wooden_trapdoors", "#minecraft:wooden_buttons",
                          "#minecraft:wooden_pressure_plates", "#c:barrels/wooden"], "not_id_contains": ["copycat"]}),
     "then": "retexture", "recolor": "lacquered_wood"},
    {"_comment": "Ground and field plants: cooler, a little dulled. Nothing is hidden.",
     "if": ex({"blocks": ["minecraft:grass_block", "#minecraft:dirt", "minecraft:moss_block", "minecraft:moss_carpet", "#minecraft:small_flowers",
                          "#minecraft:tall_flowers", "#minecraft:replaceable_by_trees", "#minecraft:saplings"],
               "not_blocks": ["#minecraft:leaves", "minecraft:water", "minecraft:seagrass", "minecraft:tall_seagrass"]}),
     "then": "retexture", "recolor": "damp_growth", "tint": "keep"},
    {"if": {"blocks": ["minecraft:snow", "minecraft:snow_block", "minecraft:powder_snow"]}, "then": "retexture", "recolor": "cold_snow"},
    {"if": ex({"blocks": ["#c:sands", "#c:sandstone/blocks", "#c:sandstone/slabs", "#c:sandstone/stairs", "#c:gravels",
                          "minecraft:suspicious_sand", "minecraft:suspicious_gravel"], "not_blocks": ["minecraft:soul_sand"]}),
     "then": "retexture", "recolor": "sooty_sand"},
    {"if": ex({"id_contains": ["sandstone"]}), "then": "retexture", "recolor": "sooty_sand"},
    {"_comment": "Masonry by id so stairs/slabs/walls/modded variants follow: brick/tile/terracotta keep their colour, "
                 "plain stone reads as concrete. Blocks with no matching sprite are pruned.",
     "if": ex({"id_contains": ["stone", "brick", "cobble", "andesite", "diorite", "granite", "tuff", "calcite", "terracotta", "concrete", "tile", "packed_mud"],
               "not_id_contains": ["ore", "redstone", "glowstone", "lodestone", "end_stone", "blackstone", "nether", "copycat", "stonecutter", "grindstone", "lamp"]}),
     "then": "retexture", "recolor": ["old_brick", "cast_concrete"]},
    {"if": ex({"blocks": ["#c:stones", "#c:cobblestones", "#minecraft:stone_bricks", "#c:bricks/normal", "#minecraft:terracotta",
                          "#c:glazed_terracottas", "#c:concretes"]}),
     "then": "retexture", "recolor": ["old_brick", "cast_concrete"]},
    {"_comment": "Metals: weathered copper keeps its patina, fresh copper/gold -> brass, iron/steel -> rust.",
     "if": ex({"id_contains": ["iron_block", "iron_bars", "iron_door", "iron_trapdoor", "chain", "copper", "gold_block", "brass", "steel", "sheetmetal", "metal"],
               "not_id_contains": ["ore", "raw_", "copycat"]}),
     "then": "retexture", "recolor": ["verdigris", "lamp_brass", "rusted_iron"]},
    {"_comment": "Plain glass shows as Create's framed glass - riveted industrial windows. Skipped if Create isn't installed.",
     "if": {"blocks": ["minecraft:glass"]}, "then": "swap", "standin": "create:framed_glass", "optional": True},
    {"if": {"blocks": ["minecraft:glass_pane"]}, "then": "swap", "standin": "create:framed_glass_pane", "optional": True},
    {"if": ex({"blocks": ["#c:glass_blocks", "#c:glass_panes"]}), "then": "retexture", "recolor": "grimy_glass"},
    {"if": ex({"blocks": ["#minecraft:wool", "#minecraft:wool_carpets"]}), "then": "retexture", "recolor": "grimy_cloth"},
    {"_comment": "FALLBACK: untagged trunks.",
     "if": ex({"id_ends_with": ["_log", "_wood", "_bark", "_branch", "_branches", "_stem"],
               "not_id_contains": ["coral", "copycat", "mushroom", "melon", "pumpkin"]}),
     "then": "retexture", "recolor": ["dark_stripped", "dark_bark"], "blight": dict(HAUNT, recolor=["spectral_stripped", "spectral_bark"])},
    {"_comment": "FALLBACK: foliage outside #leaves.",
     "if": ex({"id_contains": ["leaves", "foliage", "needles"], "not_id_contains": ["coral", "copycat"]}),
     "then": "retexture", "recolor": "nightshade_foliage", "tint": "keep", "blight": dict(HAUNT, recolor=["spectral_leaves"], tint="drop")},
    {"_comment": "FALLBACK: untagged woodwork; blocks without plank textures are pruned.",
     "if": ex({"id_contains": ["planks", "stairs", "slab", "fence", "door", "trapdoor", "pressure_plate", "button", "crate", "shelf"],
               "not_id_contains": ["copycat"]}),
     "then": "retexture", "recolor": "lacquered_wood"},
]
V['ghosts'] = {
    "_comment": ("Client-only spectres in the haunted groves: random mobs from the pack, never added to the world (nothing "
                 "can see, target or hit them). They only phase in while you look toward them (view_angle degrees either side "
                 "of the crosshair, within radius, with line of sight) and phase out when you look away or they drift out of "
                 "a grove. percent/scale must match the haunt values on the tree rules so they live in the same groves. "
                 "mobs: ids, globs or #entity_type_tags to allow (default all; here every undead mob in the pack - mods that tag "
                 "their undead #minecraft:undead join automatically); land_only drops flyers and swimmers; exclude: ids; "
                 "categories: monster/creature/ambient."),
    "percent": HAUNT["percent"], "scale": HAUNT["scale"],
    "max": 6, "spawn_every": 30, "radius": 48, "min_distance": 10, "view_angle": 40, "line_of_sight": True,
    "alpha": 0.38, "flicker": 0.25, "fade_ticks": 25, "tint": "#b3ecff",
    "mobs": ["#minecraft:undead"], "land_only": True,
    "categories": ["monster", "creature", "ambient"],
    "exclude": ["minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:giant", "minecraft:elder_guardian"]}
V['ambience'] = {
    "_comment": ("Client-only sound tied to the haunt zone (the ghosts' percent/scale). loops follow how deep you stand in a grove; "
                 "duck_on_ghost cuts that loop the moment a ghost phases in. The ghost broadcasts (tools/gen_occult_radio.py, from FDR's public-domain 1933 Fireside Chat, scrambled past recognition) work the same way. phrases + gap_seconds: play one phrase at a time (never the same twice running) with a random silence between. oneshots play a few blocks around you. midnight: at "
                 "18000 day time the bell tolls, then ghosts may walk anywhere (and twice as many) for ghost_surge_seconds. "
                 "All sounds are synthesised (tools/gen_occult_audio.py), loudness-matched with peaks limited so nothing "
                 "spikes in headphones, and play under the Ambient/Environment volume slider."),
    "depth_radius": 10, "fade_seconds": 3,
    "loops": [
        {"sound": "occult:grove.crackle", "volume": 0.7},
        {"sound": "occult:grove.hum", "volume": 0.7},
        {"phrases": ["occult:grove.waltz1", "occult:grove.waltz2", "occult:grove.waltz3", "occult:grove.waltz4", "occult:grove.waltz5"], "gap_seconds": [30, 120], "volume": 0.7, "duck_on_ghost": True},
        {"phrases": ["occult:grove.radio1", "occult:grove.radio2", "occult:grove.radio3", "occult:grove.radio4"],
         "gap_seconds": [45, 150], "volume": 0.6, "duck_on_ghost": True}],
    "oneshots": [{"sound": "occult:grove.whisper", "volume": 0.7, "per_minute": 2.5, "min_depth": 0.5}],
    "midnight": {"sound": "occult:bell", "volume": 0.8, "tolls": 12, "interval_seconds": 3.2, "ghost_surge_seconds": 60}}
V['smoke_columns'] = {
    "_comment": ("Distant foundry smoke on the horizon - always just inside the fog, dissolving as you approach. One chance per "
                 "'cell' x 'cell' blocks; puffs use the vanilla campfire smoke sprites, so the resource pack styles them. "
                 "wind = direction in degrees, lean = how far the plume bends with it."),
    "density": 0.45, "cell": 112, "height": 110, "width": 12, "rise": 1.4, "puffs": 64, "wind": 25, "lean": 0.45,
    "max_distance": 180, "color_low": "#24211e", "color_high": "#5c5b5f", "ember": "#7a2e10"}
V['sky_omens'] = {
    "_comment": ("Client-only omens over the vanilla sky; each in-game day rolls at most one from a hash of the day number, "
                 "so every player sees the same one. smog_sun: dull orange sun, sepia day. twin_suns (The King in Yellow, "
                 "1895): a second pale sun sets beside the real one, then black stars rise and a small second moon circles "
                 "the moon. eclipse: the sun goes black for duration_seconds; at totality ghosts walk everywhere and the "
                 "grove ambience plays wherever you are. Set \"force\": \"eclipse\" (or smog_sun / twin_suns / none) to test."),
    "textures_namespace": "occult",
    "smog_sun": {"chance": 0.3, "color": "#b8652e", "cover": 0.92, "day_tint": "#ebd8bd"},
    "twin_suns": {"chance": 0.06, "offset": 11, "lag": 4, "alpha": 0.6, "black_stars": 40, "second_moon": True},
    "eclipse": {"chance": 0.025, "duration_seconds": 150, "darken": 0.72, "ghost_surge": True, "music": True}}
V['rules'] = rules
json.dump(V, open(B + 'occult_foundry/vision.json', 'w'), indent=2)
print('wrote', B + 'occult_foundry/vision.json', len(rules), 'rules', len(sets) - 1, 'sets')
