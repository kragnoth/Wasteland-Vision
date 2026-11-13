package com.kragnoth.wastelandvision.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.kragnoth.wastelandvision.WastelandVision;
import com.kragnoth.wastelandvision.vision.StandIn;
import com.kragnoth.wastelandvision.vision.Vision;
import com.kragnoth.wastelandvision.vision.VisionManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Client resources for visions:
 *  1. Each vision folder is mounted as an always-on resource pack (its assets/ tree: textures,
 *     shaders, custom models, lang...). Edits apply with F3+T.
 *  2. A generated pack (config/wastelandvision/.generated/) holds blockstates + block/item models
 *     for every stand-in, built from its "type" + "textures". Rewritten every launch - don't edit it;
 *     use "type": "custom" and ship your own blockstate in the vision folder instead.
 */
public final class VisionPacks {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private VisionPacks() {}

    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        Path generated = VisionManager.root().resolve(".generated");
        try {
            generate(generated);
        } catch (IOException e) {
            WastelandClient.LOGGER.error("Wasteland Vision: couldn't generate stand-in assets", e);
        }
        event.addRepositorySource(consumer -> {
            add(consumer, "wastelandvision/generated", "Wasteland Vision: stand-in models", generated);
            for (Vision v : VisionManager.all()) {
                add(consumer, "wastelandvision/vision/" + v.id(), "Wasteland Vision: " + v.displayName(), v.folder());
            }
        });
    }

    private static void add(java.util.function.Consumer<Pack> consumer, String id, String title, Path dir) {
        PackLocationInfo info = new PackLocationInfo(id, Component.literal(title), PackSource.BUILT_IN, Optional.empty());
        Pack pack = Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(dir),
                PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) consumer.accept(pack);
        else WastelandClient.LOGGER.error("Wasteland Vision: couldn't mount {} as a resource pack (pack.mcmeta?)", dir);
    }

    // ------------------------------------------------------------------------------------ generation

    private static void generate(Path root) throws IOException {
        if (Files.exists(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(root);
        Files.writeString(root.resolve("pack.mcmeta"),
                "{\"pack\":{\"description\":\"Wasteland Vision generated stand-in models\",\"pack_format\":34}}");
        Files.writeString(root.resolve("DO_NOT_EDIT.txt"),
                "Regenerated every launch from config/wastelandvision/visions/*/vision.json.\n"
                + "To hand-write a stand-in's blockstate/models, give it \"type\": \"custom\" and put the files in its vision folder.\n");
        // Adds the runtime recolour source to the block atlas (merged with every other pack's sources).
        write(root.resolve("assets/minecraft/atlases/blocks.json"),
                "{\"sources\":[{\"type\":\"" + RecolorSource.ID + "\"}]}");
        for (Vision v : VisionManager.all()) {
            for (StandIn s : v.standins().values()) {
                if (s.custom()) continue;
                try {
                    writeStandIn(root, s);
                } catch (Exception e) {
                    WastelandClient.LOGGER.error("Wasteland Vision: [{}] stand-in '{}': {}", v.id(), s.name(), e.toString());
                }
            }
        }
    }

    private static void writeStandIn(Path root, StandIn s) throws IOException {
        Path assets = root.resolve("assets").resolve(WastelandVision.MODID);
        String path = s.path();                                       // wasteland/dead_log
        String m = WastelandVision.MODID + ":block/" + path;          // model base id
        JsonObject t = s.textures();
        String itemModel;

        switch (s.type()) {
            case CUBE -> {
                single(assets, path, m);
                model(assets, path, "minecraft:block/cube_all", Map.of("all", tex(t, "all")), null);
                itemModel = m;
            }
            case CUBE_BOTTOM_TOP -> {
                single(assets, path, m);
                model(assets, path, "minecraft:block/cube_bottom_top",
                        Map.of("top", tex(t, "top", "all"), "bottom", tex(t, "bottom", "all"), "side", tex(t, "side", "all")), null);
                itemModel = m;
            }
            case LEAVES -> {
                single(assets, path, m);
                model(assets, path, "minecraft:block/cube_all", Map.of("all", tex(t, "all")), "minecraft:cutout_mipped");
                itemModel = m;
            }
            case PILLAR -> {
                blockstate(assets, path, "{\"variants\":{"
                        + "\"axis=x\":{\"model\":\"" + m + "_horizontal\",\"x\":90,\"y\":90},"
                        + "\"axis=y\":{\"model\":\"" + m + "\"},"
                        + "\"axis=z\":{\"model\":\"" + m + "_horizontal\",\"x\":90}}}");
                Map<String, String> tx = Map.of("side", tex(t, "side", "all"), "end", tex(t, "end", "all"));
                model(assets, path, "minecraft:block/cube_column", tx, null);
                model(assets, path + "_horizontal", "minecraft:block/cube_column_horizontal", tx, null);
                itemModel = m;
            }
            case STAIRS -> {
                blockstate(assets, path, template("stairs", m));
                Map<String, String> tx = Map.of("bottom", tex(t, "bottom", "all"), "top", tex(t, "top", "all"), "side", tex(t, "side", "all"));
                model(assets, path, "minecraft:block/stairs", tx, null);
                model(assets, path + "_inner", "minecraft:block/inner_stairs", tx, null);
                model(assets, path + "_outer", "minecraft:block/outer_stairs", tx, null);
                itemModel = m;
            }
            case SLAB -> {
                blockstate(assets, path, template("slab", m));
                Map<String, String> tx = Map.of("bottom", tex(t, "bottom", "all"), "top", tex(t, "top", "all"), "side", tex(t, "side", "all"));
                model(assets, path, "minecraft:block/slab", tx, null);
                model(assets, path + "_top", "minecraft:block/slab_top", tx, null);
                model(assets, path + "_double", "minecraft:block/cube_bottom_top", tx, null);
                itemModel = m;
            }
            case FENCE -> {
                blockstate(assets, path, template("fence", m));
                Map<String, String> tx = Map.of("texture", tex(t, "texture", "all"));
                model(assets, path + "_post", "minecraft:block/fence_post", tx, null);
                model(assets, path + "_side", "minecraft:block/fence_side", tx, null);
                model(assets, path + "_inventory", "minecraft:block/fence_inventory", tx, null);
                itemModel = m + "_inventory";
            }
            case WALL -> {
                blockstate(assets, path, template("wall", m));
                Map<String, String> tx = Map.of("wall", tex(t, "wall", "all"));
                model(assets, path + "_post", "minecraft:block/template_wall_post", tx, null);
                model(assets, path + "_side", "minecraft:block/template_wall_side", tx, null);
                model(assets, path + "_side_tall", "minecraft:block/template_wall_side_tall", tx, null);
                model(assets, path + "_inventory", "minecraft:block/wall_inventory", tx, null);
                itemModel = m + "_inventory";
            }
            case CARPET -> {
                single(assets, path, m);
                model(assets, path, "minecraft:block/carpet", Map.of("wool", tex(t, "wool", "all")), null);
                itemModel = m;
            }
            case CROP -> {
                single(assets, path, m);
                model(assets, path, "minecraft:block/crop", Map.of("crop", tex(t, "crop", "all")), "minecraft:cutout");
                itemModel = null;
                generatedItem(assets, path, tex(t, "crop", "all"));
            }
            case CROSS -> {
                single(assets, path, m);
                model(assets, path, "minecraft:block/cross", Map.of("cross", tex(t, "cross", "all")), "minecraft:cutout");
                itemModel = null;
                generatedItem(assets, path, tex(t, "cross", "all"));
            }
            case TALL_CROSS -> {
                blockstate(assets, path, "{\"variants\":{"
                        + "\"half=lower\":{\"model\":\"" + m + "_bottom\"},"
                        + "\"half=upper\":{\"model\":\"" + m + "_top\"}}}");
                model(assets, path + "_bottom", "minecraft:block/cross", Map.of("cross", tex(t, "bottom")), "minecraft:cutout");
                model(assets, path + "_top", "minecraft:block/cross", Map.of("cross", tex(t, "top")), "minecraft:cutout");
                itemModel = null;
                generatedItem(assets, path, tex(t, "top"));
            }
            default -> throw new IllegalStateException("unhandled type " + s.type());
        }
        if (itemModel != null) {
            JsonObject item = new JsonObject();
            item.addProperty("parent", itemModel);
            write(assets.resolve("models/item/" + path + ".json"), GSON.toJson(item));
        }
    }

    private static String tex(JsonObject t, String... keys) {
        for (String k : keys) if (t.has(k)) return t.get(k).getAsString();
        throw new IllegalArgumentException("missing texture '" + keys[0] + "'" + (keys.length > 1 ? " (or '" + keys[keys.length - 1] + "')" : ""));
    }

    private static void single(Path assets, String path, String m) throws IOException {
        blockstate(assets, path, "{\"variants\":{\"\":{\"model\":\"" + m + "\"}}}");
    }

    private static void blockstate(Path assets, String path, String json) throws IOException {
        write(assets.resolve("blockstates/" + path + ".json"), json);
    }

    private static void model(Path assets, String path, String parent, Map<String, String> textures, String renderType) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("parent", parent);
        if (renderType != null) o.addProperty("render_type", renderType);
        JsonObject tx = new JsonObject();
        textures.forEach(tx::addProperty);
        o.add("textures", tx);
        write(assets.resolve("models/block/" + path + ".json"), GSON.toJson(o));
    }

    private static void generatedItem(Path assets, String path, String texture) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("parent", "minecraft:item/generated");
        JsonObject tx = new JsonObject();
        tx.addProperty("layer0", texture);
        o.add("textures", tx);
        write(assets.resolve("models/item/" + path + ".json"), GSON.toJson(o));
    }

    /** Vanilla blockstate templates bundled in the jar, with "${m}" as the model base id. */
    private static String template(String name, String m) throws IOException {
        try (InputStream in = VisionPacks.class.getResourceAsStream("/wastelandvision_templates/" + name + ".json")) {
            if (in == null) throw new IOException("missing template " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${m}", m);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
