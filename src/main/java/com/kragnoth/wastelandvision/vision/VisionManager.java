package com.kragnoth.wastelandvision.vision;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kragnoth.wastelandvision.WastelandVision;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * Discovers vision folders at mod construction and registers their stand-in blocks/items.
 *
 * Runs on BOTH sides: stand-ins are real registry entries, so client and server must load the same
 * vision folders (ship config/wastelandvision/ in both the client and server pack).
 *
 * First launch: if config/wastelandvision/visions/ doesn't exist, the bundled example visions are
 * copied there. README.md is refreshed every launch so the docs match the installed version.
 */
public final class VisionManager {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+");

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(WastelandVision.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WastelandVision.MODID);

    private static final Map<String, Vision> VISIONS = new LinkedHashMap<>();

    private VisionManager() {}

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve(WastelandVision.MODID);
    }

    public static Path visionsDir() {
        return root().resolve("visions");
    }

    public static void init(IEventBus modBus) {
        installDefaults();
        scan();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }

    // ------------------------------------------------------------------------------------ access

    public static synchronized List<Vision> all() {
        return List.copyOf(VISIONS.values());
    }

    public static synchronized Vision get(String id) {
        return VISIONS.get(id);
    }

    // ------------------------------------------------------------------------------------ live reload

    /**
     * Re-reads one vision's vision.json so rules, plants, hazards, atmosphere and recolour sets can be
     * edited without restarting. Stand-ins are registry entries and can't change at runtime: the ones
     * registered at startup are kept, and a warning is logged if the file's "standins" block changed.
     * On a parse error the last good version stays in use.
     *
     * @return the current vision (reloaded, or the previous one if the file couldn't be read)
     */
    public static synchronized Vision reload(Vision old) {
        if (old == null) return null;
        Path json = old.folder().resolve("vision.json");
        JsonObject obj;
        try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            obj = JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            lastReloadError = old.id() + ": " + e.getMessage();
            LOGGER.error("Wasteland Vision: couldn't re-read {} - keeping the last good version", json, e);
            return VISIONS.getOrDefault(old.id(), old);
        }
        lastReloadError = null;
        if (obj.equals(old.json())) return old;
        JsonElement before = old.json().get("standins");
        JsonElement after = obj.get("standins");
        if (before == null ? after != null : !before.equals(after)) {
            LOGGER.warn("Wasteland Vision: [{}] the \"standins\" block changed - stand-ins are registered blocks, "
                    + "so those changes need a game restart (everything else was reloaded)", old.id());
        }
        String name = obj.has("name") ? obj.get("name").getAsString() : old.id();
        String desc = obj.has("description") ? obj.get("description").getAsString() : "";
        Vision v = new Vision(old.id(), name, desc, old.folder(), obj, old.standins(), old.items());
        VISIONS.put(old.id(), v);
        LOGGER.info("Wasteland Vision: reloaded vision.json for '{}'", old.id());
        return v;
    }

    /** Re-reads every loaded vision (used before the block atlas is rebuilt, i.e. F3+T). */
    public static synchronized void reloadAll() {
        for (Vision v : List.copyOf(VISIONS.values())) reload(v);
    }

    private static volatile String lastReloadError;

    /** The error from the most recent failed {@link #reload}, or null if it succeeded. */
    public static String lastReloadError() {
        return lastReloadError;
    }

    /** Items declared in vision.json "items" blocks (for creative tabs). */
    public static List<Item> allVisionItems() {
        List<Item> out = new ArrayList<>();
        for (Vision v : VISIONS.values()) for (VisionItem i : v.items().values()) out.add(i.item().get());
        return out;
    }

    public static List<Item> allStandInItems() {
        List<Item> out = new ArrayList<>();
        for (Vision v : VISIONS.values()) for (StandIn s : v.standins().values()) out.add(s.item().get());
        return out;
    }

    // ------------------------------------------------------------------------------------ discovery

    private static void scan() {
        Path dir = visionsDir();
        if (!Files.isDirectory(dir)) return;
        List<Path> folders = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory).forEach(folders::add);
        } catch (IOException e) {
            LOGGER.error("Wasteland Vision: can't list {}", dir, e);
            return;
        }
        Collections.sort(folders);
        for (Path folder : folders) {
            String id = folder.getFileName().toString();
            Path json = folder.resolve("vision.json");
            if (!Files.isRegularFile(json)) continue;
            if (!ID.matcher(id).matches()) {
                LOGGER.error("Wasteland Vision: skipping vision folder '{}' - folder names must be lowercase [a-z0-9_.-]", id);
                continue;
            }
            try (Reader r = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
                JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
                ensurePackMeta(folder, id);
                VISIONS.put(id, parse(id, folder, obj));
                LOGGER.info("Wasteland Vision: loaded vision '{}' ({} stand-ins)", id, VISIONS.get(id).standins().size());
            } catch (Exception e) {
                LOGGER.error("Wasteland Vision: failed to load {} - vision skipped", json, e);
            }
        }
    }

    private static Vision parse(String id, Path folder, JsonObject obj) {
        String name = obj.has("name") ? obj.get("name").getAsString() : id;
        String desc = obj.has("description") ? obj.get("description").getAsString() : "";
        Map<String, StandIn> standins = new LinkedHashMap<>();
        if (obj.has("standins")) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("standins").entrySet()) {
                String sName = e.getKey();
                if (!ID.matcher(sName).matches()) {
                    LOGGER.error("Wasteland Vision: [{}] stand-in name '{}' must be lowercase [a-z0-9_.-] - skipped", id, sName);
                    continue;
                }
                JsonObject def = e.getValue().getAsJsonObject();
                StandInType type;
                boolean custom = false;
                String typeName = def.has("type") ? def.get("type").getAsString() : "cube";
                if (typeName.equalsIgnoreCase("custom")) {
                    // ships its own blockstate; "shape" picks the block class (for property copying)
                    custom = true;
                    typeName = def.has("shape") ? def.get("shape").getAsString() : "cube";
                }
                try {
                    type = StandInType.parse(typeName);
                } catch (IllegalArgumentException ex) {
                    LOGGER.error("Wasteland Vision: [{}] stand-in '{}' has unknown type '{}' - skipped", id, sName, typeName);
                    continue;
                }
                JsonObject textures = def.has("textures") ? def.getAsJsonObject("textures") : new JsonObject();
                String path = id + "/" + sName;
                DeferredBlock<Block> block = BLOCKS.registerBlock(path, type.factory(), type.properties());
                DeferredItem<net.minecraft.world.item.BlockItem> item = ITEMS.registerSimpleBlockItem(block);
                standins.put(sName, new StandIn(id, sName, type, textures, custom, block, item));
            }
        }
        Map<String, VisionItem> items = new LinkedHashMap<>();
        if (obj.has("items")) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("items").entrySet()) {
                String iName = e.getKey();
                if (!ID.matcher(iName).matches()) {
                    LOGGER.error("Wasteland Vision: [{}] item name '{}' must be lowercase [a-z0-9_.-] - skipped", id, iName);
                    continue;
                }
                JsonObject def = e.getValue().getAsJsonObject();
                String display = def.has("name") ? def.get("name").getAsString() : null;
                String texture = def.has("texture") ? def.get("texture").getAsString() : WastelandVision.MODID + ":item/" + id + "/" + iName;
                boolean key = def.has("key") && def.get("key").getAsBoolean();
                int stack = def.has("stack_size") ? Math.max(1, Math.min(99, def.get("stack_size").getAsInt())) : 1;
                net.minecraft.world.item.Rarity rarity = net.minecraft.world.item.Rarity.COMMON;
                if (def.has("rarity")) {
                    try {
                        rarity = net.minecraft.world.item.Rarity.valueOf(def.get("rarity").getAsString().toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        LOGGER.error("Wasteland Vision: [{}] item '{}': rarity is common/uncommon/rare/epic", id, iName);
                    }
                }
                net.minecraft.world.entity.EquipmentSlot slot = null;
                if (def.has("slot")) {
                    String sl = def.get("slot").getAsString().toLowerCase(java.util.Locale.ROOT);
                    slot = switch (sl) {
                        case "head" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
                        case "chest" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
                        case "legs" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
                        case "feet" -> net.minecraft.world.entity.EquipmentSlot.FEET;
                        default -> null;
                    };
                    if (slot == null && !sl.equals("none")) LOGGER.error("Wasteland Vision: [{}] item '{}': slot is head/chest/legs/feet/none", id, iName);
                }
                net.minecraft.world.item.Item.Properties props = new net.minecraft.world.item.Item.Properties().stacksTo(stack).rarity(rarity);
                if (def.has("glint") && def.get("glint").getAsBoolean())
                    props = props.component(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                final net.minecraft.world.item.Item.Properties fp = props;
                final net.minecraft.world.entity.EquipmentSlot fs = slot;
                DeferredItem<net.minecraft.world.item.Item> item = ITEMS.register(id + "/" + iName,
                        () -> fs == null ? new VisionItem.Plain(fp, display) : new VisionItem.Wearable(fp, display, fs));
                items.put(iName, new VisionItem(id, iName, display, texture, key, item));
            }
        }
        return new Vision(id, name, desc, folder, obj, Collections.unmodifiableMap(standins), Collections.unmodifiableMap(items));
    }

    /** Each vision folder is also mounted as a resource pack, which needs a pack.mcmeta. */
    private static void ensurePackMeta(Path folder, String id) throws IOException {
        Path meta = folder.resolve("pack.mcmeta");
        if (!Files.exists(meta)) {
            Files.writeString(meta, "{\"pack\":{\"description\":\"Wasteland Vision: " + id + "\",\"pack_format\":34}}");
        }
    }

    // ------------------------------------------------------------------------------------ defaults

    /**
     * Installs bundled example visions. Each example is copied ONCE, the first time this mod version
     * ships it (tracked in .installed_examples), so new examples arrive with updates, but examples a
     * pack dev deleted stay deleted and edited ones are never overwritten. README.md is always refreshed.
     */
    private static void installDefaults() {
        Path root = root();
        Path marker = root.resolve(".installed_examples");
        try {
            Path bundled = ModList.get().getModFileById(WastelandVision.MODID).getFile().findResource("wastelandvision_defaults");
            if (!Files.exists(bundled)) return;
            Files.createDirectories(root);
            java.util.Set<String> installed = new java.util.HashSet<>();
            if (Files.exists(marker)) installed.addAll(Files.readAllLines(marker));
            java.util.Set<String> newlyInstalled = new java.util.TreeSet<>();
            try (Stream<Path> walk = Files.walk(bundled)) {
                for (Path src : (Iterable<Path>) walk::iterator) {
                    if (Files.isDirectory(src)) continue;
                    String rel = bundled.relativize(src).toString().replace('\\', '/');
                    if (rel.equals("README.md")) {
                        Files.copy(src, root.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                        continue;
                    }
                    if (!rel.startsWith("visions/")) continue;
                    String vision = rel.split("/")[1];
                    if (installed.contains(vision)) continue;              // offered before: respect deletes/edits
                    Path dst = root.resolve(rel);
                    if (newlyInstalled.contains(vision) || !Files.exists(root.resolve("visions").resolve(vision))) {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        newlyInstalled.add(vision);
                    } else {
                        installed.add(vision);                             // a folder with that name already exists
                    }
                }
            }
            if (!newlyInstalled.isEmpty()) {
                installed.addAll(newlyInstalled);
                LOGGER.info("Wasteland Vision: installed example visions {} into {}", newlyInstalled, visionsDir());
            }
            Files.write(marker, new java.util.TreeSet<>(installed));
        } catch (Exception e) {
            LOGGER.error("Wasteland Vision: couldn't install example visions", e);
        }
    }
}
