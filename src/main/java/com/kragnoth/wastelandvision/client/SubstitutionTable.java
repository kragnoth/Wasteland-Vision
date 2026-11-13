package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.WastelandVision;
import com.kragnoth.wastelandvision.vision.StandIn;
import com.kragnoth.wastelandvision.vision.Vision;
import com.kragnoth.wastelandvision.vision.VisionManager;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.common.Tags;

/**
 * Compiles the active vision's ordered "rules" into flat per-BlockState / per-Item lookup tables.
 * First matching rule wins. Hot-path lookups are a single array read.
 *
 * Rebuilt when tags sync (they're unbound on the title screen), when the vision is turned on, and
 * when the active vision changes. Every rebuild writes config/wastelandvision/mappings-<vision>.txt.
 */
public final class SubstitutionTable {
    private static volatile BlockState[] table = new BlockState[0];
    private static volatile byte[] plantKinds = new byte[0];
    private static volatile short[] plantRuleIdx = new short[0];
    private static volatile PlantHallucination.PlantRule[] plantRules = new PlantHallucination.PlantRule[0];
    private static volatile short[] retexIdx = new short[0];
    private static volatile Retexturer[] retexturers = new Retexturer[0];

    /**
     * Item-level hallucination (inventories, JEI, Jade icons, held items).
     * @param alt        item whose model is drawn instead (null = keep the real model)
     * @param nameFormat format wrapping the real name, e.g. "Dead %s" (or a lang key); null = keep name
     */
    public record ItemRule(@Nullable Item alt, @Nullable String nameFormat) {}
    private static volatile ItemRule[] items = new ItemRule[0];

    /** True while rebuilding: BlockStateBaseMixin must report REAL shapes to the classifier. */
    public static volatile boolean rebuilding = false;

    private SubstitutionTable() {}

    // ------------------------------------------------------------------------------------ lookups

    @Nullable
    public static BlockState get(BlockState state) {
        BlockState[] t = table;
        int id = Block.getId(state);
        return id >= 0 && id < t.length ? t[id] : null;
    }

    public static byte plantKind(BlockState state) {
        byte[] p = plantKinds;
        int id = Block.getId(state);
        return id >= 0 && id < p.length ? p[id] : PlantHallucination.NONE;
    }

    @Nullable
    public static PlantHallucination.PlantRule plantRule(BlockState state) {
        short[] idx = plantRuleIdx;
        PlantHallucination.PlantRule[] rules = plantRules;
        int id = Block.getId(state);
        if (id < 0 || id >= idx.length) return null;
        int r = idx[id] - 1;
        return r >= 0 && r < rules.length ? rules[r] : null;
    }

    /** Retexture rule for this state (keep the real model, swap sprites), or null. */
    @Nullable
    public static Retexturer retexturer(BlockState state) {
        short[] idx = retexIdx;
        Retexturer[] rx = retexturers;
        int id = Block.getId(state);
        if (id < 0 || id >= idx.length) return null;
        int r = idx[id] - 1;
        return r >= 0 && r < rx.length ? rx[r] : null;
    }

    @Nullable
    public static ItemRule itemRule(Item item) {
        ItemRule[] t = items;
        int id = Item.getId(item);
        return id >= 0 && id < t.length ? t[id] : null;
    }

    public static boolean isEmpty() {
        return table.length == 0;
    }

    // ------------------------------------------------------------------------------------ rules

    private enum Action { KEEP, VANISH, SWAP, NATIVE, PLANT, RETEXTURE }

    /** Lazily computed per-state facts shared across all rule tests for that state. */
    private static final class Facts {
        Boolean full;
        boolean full(BlockState s) {
            if (full == null) full = isFullCube(s);
            return full;
        }
    }

    /** "if" block. All present fields must match (AND); lists inside a field are OR. */
    private record Cond(Matcher blocks, Matcher notBlocks, @Nullable Boolean fullCube, @Nullable Boolean occludes, @Nullable String clazz,
                        @Nullable Boolean stripped, List<String> idContains, List<String> notIdContains, List<String> idEndsWith,
                        Map<String, String> properties, @Nullable String namespace, List<String> notNamespaces) {
        boolean test(BlockState state, ResourceLocation key, Facts facts) {
            if (namespace != null && !namespace.equals(key.getNamespace())) return false;
            if (notNamespaces.contains(key.getNamespace())) return false;
            if (!blocks.isEmpty() && !blocks.matches(state, key)) return false;
            if (notBlocks.matches(state, key)) return false;
            String path = key.getPath();
            if (!idContains.isEmpty() && idContains.stream().noneMatch(path::contains)) return false;
            if (notIdContains.stream().anyMatch(path::contains)) return false;
            if (!idEndsWith.isEmpty() && idEndsWith.stream().noneMatch(path::endsWith)) return false;
            if (stripped != null && stripped != isStripped(state, path)) return false;
            if (clazz != null && !classMatches(state.getBlock(), clazz)) return false;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                Property<?> p = state.getBlock().getStateDefinition().getProperty(e.getKey());
                if (p == null || !valueName(state, p).equals(e.getValue())) return false;
            }
            if (occludes != null && state.canOcclude() != occludes) return false;
            return fullCube == null || facts.full(state) == fullCube;
        }
    }

    private record Rule(String label, Cond cond, Action action, @Nullable BlockState target,
                        @Nullable String template, Map<String, String> pathReplace, int plantRule, int retex,
                        @Nullable Item itemAlt, @Nullable String name) {}

    private static volatile List<Rule> rules = List.of();

    // ------------------------------------------------------------------------------------ rebuild

    /** Compiles {@code vision} and rebuilds every table. Call on the client thread. */
    public static void rebuild(Vision vision) {
        rebuilding = true;
        try {
            List<PlantHallucination.PlantRule> plantList = new ArrayList<>();
            List<Retexturer> retexList = new ArrayList<>();
            loadHazards(vision);
            Variation.load(vision.json().has("variation") ? vision.json().getAsJsonObject("variation") : null);
            rules = compile(vision, plantList, retexList);
            plantRules = plantList.toArray(new PlantHallucination.PlantRule[0]);
            retexturers = retexList.toArray(new Retexturer[0]);
            JsonObject plants = vision.json().has("plants") ? vision.json().getAsJsonObject("plants") : new JsonObject();
            WastelandState.visiblePlantPercent = plants.has("visible_percent") ? plants.get("visible_percent").getAsInt() : 20;
            WastelandState.hidePlantOutlines = !plants.has("hide_hidden_outlines") || plants.get("hide_hidden_outlines").getAsBoolean();
            WastelandState.visionId = vision.id();
            fill();
            writeAudit(vision);
        } finally {
            rebuilding = false;
        }
    }

    private static void fill() {
        int size = Block.BLOCK_STATE_REGISTRY.size();
        BlockState[] next = new BlockState[size];
        byte[] kinds = new byte[size];
        short[] idx = new short[size];
        short[] rIdx = new short[size];
        int mapped = 0, plantCount = 0, retexCount = 0, retexPruned = 0;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.getId(state);
            if (id < 0 || id >= size) continue;
            Hit hit = classify(state);
            if (hit == null) continue;
            if (hit.rule.action == Action.PLANT) {
                kinds[id] = PlantHallucination.kindOf(state);
                idx[id] = (short) (hit.rule.plantRule + 1);
                plantCount++;
            } else if (hit.rule.action == Action.RETEXTURE) {
                if (!retexturers[hit.rule.retex].affects(state)) { retexPruned++; continue; }
                rIdx[id] = (short) (hit.rule.retex + 1);
                retexCount++;
            } else {
                next[id] = hit.sub;
                mapped++;
            }
        }
        plantKinds = kinds;
        plantRuleIdx = idx;
        retexIdx = rIdx;
        table = next;

        ItemRule[] nextItems = new ItemRule[BuiltInRegistries.ITEM.size()];
        int itemCount = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem blockItem)) continue;
            int id = Item.getId(item);
            if (id < 0 || id >= nextItems.length) continue;
            Hit hit = classify(blockItem.getBlock().defaultBlockState());
            if (hit == null) continue;
            Item alt = switch (hit.rule.action) {
                case PLANT -> hit.rule.itemAlt;
                case SWAP, NATIVE -> asItem(hit.sub);
                case RETEXTURE -> null;
                default -> null;
            };
            nextItems[id] = new ItemRule(alt, hit.rule.name);
            itemCount++;
        }
        items = nextItems;
        // Chained swaps (stand-in that is itself remapped/retextured/a plant): rendering only follows one
        // level, so the stand-in shows as-is - log them so rule globs can be tightened.
        BlockState[] tbl = table;
        java.util.Set<String> chained = new java.util.TreeSet<>();
        for (int i = 0; i < tbl.length; i++) {
            BlockState sub = tbl[i];
            if (sub == null || sub.isAir()) continue;
            int sid = Block.getId(sub);
            boolean mappedAgain = sid >= 0 && ((sid < tbl.length && tbl[sid] != null)
                    || (sid < plantKinds.length && plantKinds[sid] != PlantHallucination.NONE));
            if (mappedAgain) chained.add(BuiltInRegistries.BLOCK.getKey(Block.stateById(i).getBlock())
                    + " -> " + BuiltInRegistries.BLOCK.getKey(sub.getBlock()));
        }
        if (!chained.isEmpty()) {
            WastelandClient.LOGGER.warn("Wasteland Vision: [{}] {} swaps point at a stand-in that another rule also remaps (shown un-remapped): {}",
                    WastelandState.visionId, chained.size(), chained.size() > 20 ? List.copyOf(chained).subList(0, 20) + " ..." : chained);
        }
        WastelandClient.LOGGER.info("Wasteland Vision: [{}] {} rules -> {} states remapped, {} plant states, {} retextured ({} matched but had no mapped textures), {} items",
                WastelandState.visionId, rules.size(), mapped, plantCount, retexCount, retexPruned, itemCount);
    }

    private record Hit(Rule rule, @Nullable BlockState sub) {}

    @Nullable
    private static Hit classify(BlockState state) {
        Hit hit = classify0(state);
        if (hit == null) return null;
        boolean hides = hit.rule.action == Action.PLANT || (hit.sub != null && hit.sub.isAir());
        if (!hides) return hit;
        // Never make something invisible that can still hurt, slow or block the player.
        switch (hazardOf(state)) {
            case DAMAGING: {
                // Two-high hazards: mark the bottom, hide the top (the marker is one block tall).
                if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                    return new Hit(hazardRule(hit.rule), Blocks.AIR.defaultBlockState());
                }
                // The marker itself (wither rose: a small flower that also withers you) would be swapped
                // for... itself - the 0.19.0 StackOverflowError. It already IS the marker: leave it real.
                if (hazardMarker != null && hazardMarker.getBlock() == state.getBlock()) return null;
                return new Hit(hazardRule(hit.rule), hazardMarker);
            }
            case SOLID:
                return null; // keep it real: hiding it would make an invisible platform / lip
            default:
                return hit;
        }
    }

    // ------------------------------------------------------------------------------------ hazards

    private enum Hazard { NONE, DAMAGING, SOLID }

    private static volatile BlockState hazardMarker = Blocks.WITHER_ROSE.defaultBlockState();
    private static Matcher hazardExtra = Matcher.parse(List.of());
    private static Matcher hazardIgnore = Matcher.parse(List.of());
    private static final Map<Class<?>, Boolean> CONTACT_EFFECT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * DAMAGING: the block does something on contact (overrides entityInside: berry thorns, wither,
     * cobweb slowing, fire, modded thorny plants...) or is listed in hazards.blocks.
     * SOLID: it has collision (lily pads, moss carpet...) or is climbable (vines, ladders) - never hidden.
     */
    private static Hazard hazardOf(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (hazardIgnore.matches(state, key)) return Hazard.NONE;
        // Solid/climbable first: lily pads (break boats) and cactus also have contact effects, but the
        // real block is clearer than a marker floating on water.
        if (state.is(net.minecraft.tags.BlockTags.CLIMBABLE)) return Hazard.SOLID; // no invisible ladders/vines
        try {
            if (!state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return Hazard.SOLID;
        } catch (Throwable ignored) {
            return Hazard.SOLID; // can't tell without a world: assume it's solid, i.e. don't hide it
        }
        if (hazardExtra.matches(state, key) || hasContactEffect(state.getBlock())) return Hazard.DAMAGING;
        return Hazard.NONE;
    }

    /** Does this block class (or a superclass below Block) override BlockBehaviour#entityInside? */
    private static boolean hasContactEffect(Block block) {
        return CONTACT_EFFECT.computeIfAbsent(block.getClass(), cls -> {
            for (Class<?> c = cls; c != null && c != Block.class && c != Object.class; c = c.getSuperclass()) {
                try {
                    c.getDeclaredMethod("entityInside", BlockState.class, net.minecraft.world.level.Level.class,
                            BlockPos.class, net.minecraft.world.entity.Entity.class);
                    return true;
                } catch (NoSuchMethodException ignored) {
                    // keep walking up
                }
            }
            return false;
        });
    }

    private static Rule hazardRule(Rule r) {
        return new Rule(r.label + " -> hazard marker", r.cond, Action.SWAP, hazardMarker, null, Map.of(), -1, -1,
                asItem(hazardMarker), r.name);
    }

    private static void loadHazards(Vision v) {
        JsonObject h = v.json().has("hazards") ? v.json().getAsJsonObject("hazards") : new JsonObject();
        BlockState marker = Blocks.WITHER_ROSE.defaultBlockState();
        if (h.has("marker")) {
            try {
                marker = firstStandIn(v, h.get("marker"));
            } catch (Exception e) {
                WastelandClient.LOGGER.error("Wasteland Vision: [{}] hazards.marker: {}", v.id(), e.toString());
            }
        }
        hazardMarker = marker;
        hazardExtra = Matcher.parse(strings(h, "blocks"));
        hazardIgnore = Matcher.parse(strings(h, "ignore"));
    }

    @Nullable
    private static Hit classify0(BlockState state) {
        if (state.isAir()) return null;
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key.getNamespace().equals(WastelandVision.MODID)) return null; // never remap stand-ins
        Facts facts = new Facts();
        for (Rule rule : rules) {
            if (!rule.cond.test(state, key, facts)) continue;
            switch (rule.action) {
                case KEEP:
                    return null;
                case VANISH:
                    return new Hit(rule, Blocks.AIR.defaultBlockState());
                case SWAP:
                    return new Hit(rule, copyProperties(state, rule.target));
                case NATIVE: {
                    BlockState t = nativeVariant(state, key, rule.template, rule.pathReplace);
                    if (t == null) continue; // no such block: fall through to later rules
                    return new Hit(rule, t);
                }
                case PLANT:
                case RETEXTURE:
                    return new Hit(rule, null);
                default:
                    break;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------ compile

    private static List<Rule> compile(Vision v, List<PlantHallucination.PlantRule> plantOut, List<Retexturer> retexOut) {
        List<Rule> out = new ArrayList<>();
        JsonArray arr = v.json().has("rules") ? v.json().getAsJsonArray("rules") : new JsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject r = arr.get(i).getAsJsonObject();
            String label = "rule #" + (i + 1) + (r.has("_comment") ? ": " + r.get("_comment").getAsString() : "");
            try {
                Cond cond = compileCond(r.has("if") ? r.getAsJsonObject("if") : new JsonObject());
                Action action = Action.valueOf(r.get("then").getAsString().trim().toUpperCase(Locale.ROOT));
                String name = r.has("name") ? r.get("name").getAsString() : null;
                BlockState target = null;
                String template = null;
                int plantIdx = -1;
                int retexI = -1;
                Item itemAlt = null;
                Map<String, String> pathReplace = new java.util.LinkedHashMap<>();
                if (r.has("path_replace")) {
                    for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("path_replace").entrySet()) {
                        pathReplace.put(e.getKey(), e.getValue().getAsString());
                    }
                }
                switch (action) {
                    case SWAP -> target = firstStandIn(v, r.get("standin"));
                    case NATIVE -> template = r.has("template") ? r.get("template").getAsString() : "{namespace}:dead_{path}";
                    case PLANT -> {
                        plantIdx = plantOut.size();
                        plantOut.add(compilePlant(v, r));
                        PlantHallucination.PlantRule pr = plantOut.get(plantIdx);
                        if (r.has("item")) {
                            try {
                                itemAlt = asItem(firstStandIn(v, r.get("item")));
                            } catch (IllegalArgumentException ex) {
                                itemAlt = null;
                            }
                        } else {
                            itemAlt = null;                    // plants keep their own item icon unless "item" says otherwise
                        }
                    }
                    case RETEXTURE -> {
                        retexI = retexOut.size();
                        Retexturer rx = compileRetexture(v, r);
                        if (r.has("blight")) {
                            // {"percent": 30, "scale": 14, "recolor": [...], "tint": "drop"}: a different look in
                            // blighted areas (2D world noise, so trunk and canopy of a tree agree)
                            JsonObject b = r.getAsJsonObject("blight");
                            float pct = b.has("percent") ? b.get("percent").getAsFloat() : 30F;
                            float scale = b.has("scale") ? Math.max(2F, b.get("scale").getAsFloat()) : 14F;
                            rx.setBlight(compileRetexture(v, b), Variation.blightThreshold(pct, scale), scale);
                        }
                        retexOut.add(rx);
                    }
                    default -> {}
                }
                out.add(new Rule(label, cond, action, target, template, java.util.Collections.unmodifiableMap(pathReplace), plantIdx, retexI, itemAlt, name));
            } catch (Exception e) {
                if (r.has("optional") && r.get("optional").getAsBoolean()) {
                    // e.g. a rule aimed at a mod that isn't in this pack
                    WastelandClient.LOGGER.info("Wasteland Vision: [{}] optional {} skipped: {}", v.id(), label, e.getMessage());
                } else {
                    WastelandClient.LOGGER.error("Wasteland Vision: [{}] {} is invalid and was skipped: {}", v.id(), label, e.toString());
                }
            }
        }
        return List.copyOf(out);
    }

    private static Cond compileCond(JsonObject c) {
        Map<String, String> props = new HashMap<>();
        if (c.has("properties")) {
            for (Map.Entry<String, JsonElement> e : c.getAsJsonObject("properties").entrySet()) {
                props.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return new Cond(
                Matcher.parse(strings(c, "blocks")),
                Matcher.parse(strings(c, "not_blocks")),
                c.has("full_cube") ? c.get("full_cube").getAsBoolean() : null,
                c.has("occludes") ? c.get("occludes").getAsBoolean() : null,
                c.has("class") ? c.get("class").getAsString().toLowerCase(Locale.ROOT) : null,
                c.has("stripped") ? c.get("stripped").getAsBoolean() : null,
                strings(c, "id_contains"),
                strings(c, "not_id_contains"),
                strings(c, "id_ends_with"),
                props,
                c.has("namespace") ? c.get("namespace").getAsString() : null,
                strings(c, "not_namespaces"));
    }

    private static PlantHallucination.PlantRule compilePlant(Vision v, JsonObject r) {
        PlantHallucination.PlantRule base = compilePlant0(v, r);
        if (!r.has("blight")) return base;
        // {"percent", "scale", "standins", "tall", "visible_percent"}: the look inside blighted areas
        JsonObject b = r.getAsJsonObject("blight");
        float pct = b.has("percent") ? b.get("percent").getAsFloat() : 30F;
        float scale = b.has("scale") ? Math.max(2F, b.get("scale").getAsFloat()) : 14F;
        PlantHallucination.PlantRule alt = compilePlant0(v, b);
        return new PlantHallucination.PlantRule(base.shorts(), base.cumulative(), base.tallLower(), base.tallUpper(),
                base.tallChance(), base.visible(), base.tallSelf(), alt, Variation.blightThreshold(pct, scale), scale);
    }

    private static PlantHallucination.PlantRule compilePlant0(Vision v, JsonObject r) {
        JsonArray list = r.getAsJsonArray("standins");
        List<BlockState> shortList = new ArrayList<>();
        List<Integer> cumList = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < list.size(); i++) {
            JsonObject e = list.get(i).getAsJsonObject();
            String id = e.get("id").getAsString();
            BlockState s;
            if (id.equals("self")) {
                s = null;                                  // keep the real plant
            } else {
                try {
                    s = standIn(v, id);
                } catch (IllegalArgumentException ex) {
                    if (!id.contains(":")) throw ex;       // a typo'd stand-in name is a real error
                    WastelandClient.LOGGER.info("Wasteland Vision: [{}] plant stand-in {} isn't installed - left out", v.id(), id);
                    continue;
                }
            }
            shortList.add(s);
            total += e.has("weight") ? Math.max(0, e.get("weight").getAsInt()) : 1;
            cumList.add(total);
        }
        if (shortList.isEmpty() && list.size() > 0) throw new IllegalArgumentException("none of the plant stand-ins exist");
        BlockState[] shorts = shortList.toArray(new BlockState[0]);
        int[] cumulative = cumList.stream().mapToInt(Integer::intValue).toArray();
        BlockState lower = null, upper = null;
        int chance = 0;
        boolean tallSelf = false;
        if (r.has("tall")) {
            JsonObject t = r.getAsJsonObject("tall");
            chance = t.has("chance") ? t.get("chance").getAsInt() : 100;
            if (t.get("id").getAsString().equals("self")) {
                tallSelf = true;
            } else {
                BlockState tall = standIn(v, t.get("id").getAsString());
                if (tall.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                    lower = tall.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                    upper = tall.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                } else {
                    WastelandClient.LOGGER.warn("Wasteland Vision: [{}] tall stand-in {} isn't two-high (use type tall_cross)", v.id(), t.get("id"));
                    chance = 0;
                }
            }
        }
        int visible = r.has("visible_percent") ? r.get("visible_percent").getAsInt() : -1;
        return new PlantHallucination.PlantRule(shorts, cumulative, lower, upper, chance, visible, tallSelf, null, 2F, 14F);
    }

    /**
     * "then": "retexture": {"textures": {glob: target}, "recolor": "<set>" or ["<set>", ...], "tint": "keep"|"drop"}.
     * Needs at least one of textures / recolor.
     */
    private static Retexturer compileRetexture(Vision v, JsonObject r) {
        List<Map.Entry<java.util.regex.Pattern, ResourceLocation>> tex = new ArrayList<>();
        if (r.has("textures")) {
            for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("textures").entrySet()) {
                tex.add(Map.entry(Globs.compile(e.getKey()), ResourceLocation.parse(e.getValue().getAsString())));
            }
        }
        List<Retexturer.SetRef> refs = new ArrayList<>();
        List<RecolorSource.RecolorSet> sets = RecolorSource.sets(v);
        Map<String, Decals.Decal> decals = new HashMap<>();
        for (Decals.Decal d : Decals.parse(v)) decals.put(d.name(), d);
        for (String name : strings(r, "recolor")) {          // a set name or a list of them
            RecolorSource.RecolorSet found = sets.stream().filter(s -> s.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no recolor set named '" + name + "' in recolor_sets"));
            Retexturer.DecalRef dref = null;
            if (found.decal() != null) {
                Decals.Decal d = decals.get(found.decal());
                if (d == null) throw new IllegalArgumentException("recolor set '" + name + "' names decal '" + found.decal() + "', which isn't in \"decals\"");
                dref = new Retexturer.DecalRef(Decals.id(v.id(), d.name(), false), Decals.id(v.id(), d.name(), true), d.blocks());
            }
            refs.add(new Retexturer.SetRef(v.id(), name, found.sprites(), found.variants(), dref));
        }
        if (tex.isEmpty() && refs.isEmpty()) throw new IllegalArgumentException("retexture needs \"textures\" and/or \"recolor\"");
        boolean drop = r.has("tint") && r.get("tint").getAsString().equalsIgnoreCase("drop");
        return new Retexturer(List.copyOf(tex), List.copyOf(refs), drop);
    }

    /**
     * A stand-in reference that may be a list of fallbacks: {@code ["supplementaries:ash", "soot_layer"]}
     * uses the first one that exists (handy for blocks from optional mods).
     */
    private static BlockState firstStandIn(Vision v, JsonElement ref) {
        if (!ref.isJsonArray()) return standIn(v, ref.getAsString());
        IllegalArgumentException last = null;
        for (JsonElement e : ref.getAsJsonArray()) {
            try {
                return standIn(v, e.getAsString());
            } catch (IllegalArgumentException ex) {
                last = ex;
            }
        }
        throw last != null ? last : new IllegalArgumentException("empty stand-in list");
    }

    /** "dead_log" = this vision's stand-in; "minecraft:dead_bush" = any registered block. */
    private static BlockState standIn(Vision v, String id) {
        if (id.contains(":")) {
            ResourceLocation rl = ResourceLocation.parse(id);
            Optional<Block> b = BuiltInRegistries.BLOCK.getOptional(rl);
            if (b.isEmpty()) throw new IllegalArgumentException("unknown block " + id);
            return b.get().defaultBlockState();
        }
        StandIn s = v.standins().get(id);
        if (s == null) throw new IllegalArgumentException("no stand-in named '" + id + "' in this vision");
        return s.block().get().defaultBlockState();
    }

    @Nullable
    private static Item asItem(@Nullable BlockState s) {
        if (s == null || s.isAir()) return null;
        Item i = s.getBlock().asItem();
        return i == Items.AIR ? null : i;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key)) return out;
        JsonElement e = o.get(key);
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        } else {
            out.add(e.getAsString());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ predicates

    private static boolean isStripped(BlockState state, String path) {
        return path.contains("stripped") || state.is(Tags.Blocks.STRIPPED_LOGS) || state.is(Tags.Blocks.STRIPPED_WOODS);
    }

    private static boolean classMatches(Block b, String clazz) {
        return switch (clazz) {
            case "stairs" -> b instanceof StairBlock;
            case "slab" -> b instanceof SlabBlock;
            case "fence" -> b instanceof FenceBlock;
            case "fence_gate" -> b instanceof FenceGateBlock;
            case "wall" -> b instanceof WallBlock;
            case "door" -> b instanceof DoorBlock;
            case "trapdoor" -> b instanceof TrapDoorBlock;
            case "pillar" -> b instanceof RotatedPillarBlock;
            case "carpet" -> b instanceof CarpetBlock;
            default -> false;
        };
    }

    /**
     * "{namespace}:dead_{path}" -> that block, with properties copied; null if it doesn't exist.
     * {@code pathReplace} edits {path} first, e.g. {"mossy_": ""} or {"exposed_": "", "weathered_": ""}.
     */
    @Nullable
    private static BlockState nativeVariant(BlockState state, ResourceLocation key, String template, Map<String, String> pathReplace) {
        String path = key.getPath();
        for (Map.Entry<String, String> e : pathReplace.entrySet()) path = path.replace(e.getKey(), e.getValue());
        String id = template.replace("{namespace}", key.getNamespace()).replace("{path}", path);
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || rl.equals(key)) return null;
        Optional<Block> b = BuiltInRegistries.BLOCK.getOptional(rl);
        if (b.isEmpty() || b.get() == Blocks.AIR) return null;
        return copyProperties(state, b.get().defaultBlockState());
    }

    /** True if the block's outline is a full 1x1x1 cube (queried without a world; failures = false). */
    private static boolean isFullCube(BlockState state) {
        try {
            return Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------ audit

    /** config/wastelandvision/mappings-<vision>.txt: one line per block (default state) + the rule that fired. */
    private static void writeAudit(Vision v) {
        List<String> lines = new ArrayList<>();
        lines.add("# Wasteland Vision mappings for vision '" + v.id() + "' (rewritten on every rebuild).");
        lines.add("# real block -> stand-in   [rule that fired]");
        for (Block block : BuiltInRegistries.BLOCK) {
            Hit hit = classify(block.defaultBlockState());
            if (hit == null) continue;
            if (hit.rule.action == Action.RETEXTURE && !retexturers[hit.rule.retex].affects(block.defaultBlockState())) continue;
            String target = switch (hit.rule.action) {
                case PLANT -> "per-position plant";
                case VANISH -> "(vanishes)";
                case RETEXTURE -> "(retextured)";
                default -> BuiltInRegistries.BLOCK.getKey(hit.sub.getBlock()).toString();
            };
            lines.add(BuiltInRegistries.BLOCK.getKey(block) + " -> " + target + "   [" + hit.rule.label + "]");
        }
        try {
            Files.write(VisionManager.root().resolve("mappings-" + v.id() + ".txt"), lines);
        } catch (IOException | RuntimeException e) {
            WastelandClient.LOGGER.warn("Wasteland Vision: couldn't write mappings audit", e);
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    /** Matches block ids ("modid:block") and tags ("#modid:tag"). */
    private record Matcher(Set<ResourceLocation> ids, List<TagKey<Block>> tags) {
        static Matcher parse(List<String> entries) {
            Set<ResourceLocation> ids = new HashSet<>();
            List<TagKey<Block>> tags = new ArrayList<>();
            for (String raw : entries) {
                String s = raw.trim();
                if (s.isEmpty()) continue;
                boolean tag = s.startsWith("#");
                ResourceLocation rl = ResourceLocation.tryParse(tag ? s.substring(1) : s);
                if (rl == null) {
                    WastelandClient.LOGGER.warn("Wasteland Vision: ignoring bad block/tag '{}'", s);
                } else if (tag) {
                    tags.add(TagKey.create(Registries.BLOCK, rl));
                } else {
                    ids.add(rl);
                }
            }
            return new Matcher(ids, tags);
        }

        boolean isEmpty() {
            return ids.isEmpty() && tags.isEmpty();
        }

        boolean matches(BlockState state, ResourceLocation key) {
            if (ids.contains(key)) return true;
            for (TagKey<Block> t : tags) if (state.is(t)) return true;
            return false;
        }
    }

    /** Copies every property the target also has, matched by NAME and value name. */
    private static BlockState copyProperties(BlockState from, BlockState to) {
        for (Property<?> fromProp : from.getProperties()) {
            Property<?> toProp = to.getBlock().getStateDefinition().getProperty(fromProp.getName());
            if (toProp != null) to = set(to, toProp, valueName(from, fromProp));
        }
        return to;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> prop) {
        return prop.getName(state.getValue(prop));
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> prop, String value) {
        return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
    }
}
