package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kragnoth.wastelandvision.WastelandVision;
import com.kragnoth.wastelandvision.vision.Vision;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.neoforged.neoforge.client.model.IQuadTransformer;

/**
 * World-space decals (vision.json "decals"), the CTM "repeat" idea applied to grime: one large generated
 * sheet covers an N x N block area (default 16x16 blocks = 256x256 px). Every face of a block whose recolour
 * set names a decal gets an extra translucent quad showing ITS slice of the sheet, chosen by world position,
 * so soot patches and drips run continuously across block edges and only repeat every N blocks.
 *
 * Two sheets per decal: "wall" (vertical faces; drips run down in world space) and "floor" (up/down
 * faces). UVs come from each vertex's world position, so slabs, stairs and other partial faces line up.
 * Faces whose slice is empty get no overlay at all.
 */
public final class Decals {
    private Decals() {}

    /**
     * @param blocks  sheet size in blocks (N x N)
     * @param px      pixels per block (16 for vanilla-res packs)
     * @param shades  soot colours, dark -> light
     */
    public record Decal(String vision, String name, int blocks, int px, int[] shades, float coverage, float patch,
                        float drips, int dripMin, int dripMax, float floorCoverage, float opacity, long seed) {}

    /** sprite id -> per-cell "has any opaque pixel" (index = cv * blocks + cu). Filled at stitch time. */
    private static final Map<ResourceLocation, boolean[]> CELLS = new ConcurrentHashMap<>();

    public static ResourceLocation id(String vision, String name, boolean floor) {
        return ResourceLocation.fromNamespaceAndPath(WastelandVision.MODID, "decal/" + vision + "/" + name + (floor ? "/floor" : "/wall"));
    }

    public static List<Decal> parse(Vision v) {
        List<Decal> out = new ArrayList<>();
        if (!v.json().has("decals")) return out;
        for (Map.Entry<String, JsonElement> e : v.json().getAsJsonObject("decals").entrySet()) {
            if (e.getKey().startsWith("_")) continue;
            try {
                JsonObject o = e.getValue().getAsJsonObject();
                int[] shades;
                if (o.has("shades")) {
                    var a = o.getAsJsonArray("shades");
                    shades = new int[a.size()];
                    for (int i = 0; i < shades.length; i++) shades[i] = hex(a.get(i).getAsString());
                } else {
                    shades = new int[] {0x0e0b09, 0x1a1511, 0x271f18};
                }
                if (shades.length == 0) throw new IllegalArgumentException("needs at least one shade");
                int blocks = o.has("blocks") ? Math.max(2, Math.min(32, o.get("blocks").getAsInt())) : 16;
                int px = o.has("pixels") ? Math.max(4, Math.min(64, o.get("pixels").getAsInt())) : 16;
                if (Integer.bitCount(blocks * px) != 1) {
                    throw new IllegalArgumentException("blocks x pixels must be a power of two (e.g. 8 x 16 = 128)");
                }
                out.add(new Decal(v.id(), e.getKey(), blocks, px, shades,
                        clamp01(f(o, "coverage", 0.2F)), Math.max(0.5F, f(o, "patch_size", 2.5F)),
                        clamp01(f(o, "drips", 0.35F)),
                        o.has("drip_min") ? o.get("drip_min").getAsInt() : 6,
                        o.has("drip_max") ? o.get("drip_max").getAsInt() : 40,
                        clamp01(f(o, "floor_coverage", 0.6F)), clamp01(f(o, "opacity", 0.8F)),
                        (long) (v.id() + "/" + e.getKey()).hashCode() * 0x9E3779B97F4A7C15L));
            } catch (Exception ex) {
                WastelandClient.LOGGER.error("Wasteland Vision: [{}] decal '{}' is invalid: {}", v.id(), e.getKey(), ex.toString());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sheet generation (stitch time)

    /** Builds one sheet. Records which cells are non-empty so empty faces get no overlay quad. */
    public static SpriteContents sheet(Decal d, boolean floor) {
        ResourceLocation id = id(d.vision(), d.name(), floor);
        int size = d.blocks() * d.px();
        NativeImage img = new NativeImage(size, size, true);
        float cov = floor ? d.coverage() * d.floorCoverage() : d.coverage();
        float cell = d.patch() * d.px();                                  // noise feature size in pixels
        int g1 = Math.max(1, Math.round(size / cell)), g2 = Math.max(1, Math.round(size / (cell * 0.4F)));
        long seed = d.seed() ^ (floor ? 0x5DEECE66DL : 0L);
        float[] a = new float[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            float n = 0.65F * tileNoise(seed, x, y, size, g1) + 0.35F * tileNoise(seed ^ 0x1234567L, x, y, size, g2);
            float t = (n - (1F - cov - 0.12F)) / 0.3F;                    // soft-edged patches
            t = Math.max(0, Math.min(1, t));
            a[y * size + x] = t * t * (3 - 2 * t);
        }
        if (!floor && d.drips() > 0) {                                    // streaks running DOWN the wall
            int columns = Math.round(size * d.drips() * 0.08F);
            for (int i = 0; i < columns; i++) {
                long h = mix(seed ^ 0xD1B54A32D192ED03L, i, 7);
                int x = (int) Long.remainderUnsigned(h, size);
                int y0 = (int) Long.remainderUnsigned(h >>> 20, size);
                if (a[y0 * size + x] < 0.3F) continue;                    // drips start inside a soot patch
                int len = d.dripMin() + (int) Long.remainderUnsigned(h >>> 40, Math.max(1, d.dripMax() - d.dripMin()));
                int width = 1 + (int) Long.remainderUnsigned(h >>> 52, 2);       // 1-2 px wide streaks
                for (int k = 0; k < len; k++) {
                    int y = (y0 + k) % size;                              // wraps: the sheet tiles vertically
                    float fade = 1F - (float) k / len;
                    for (int wx = 0; wx < width; wx++) {
                        int idx = y * size + (x + wx) % size;
                        a[idx] = Math.max(a[idx], (0.45F + 0.55F * fade) * (wx == 0 ? 1F : 0.6F));
                    }
                }
            }
        }
        boolean[] cells = new boolean[d.blocks() * d.blocks()];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            float v = a[y * size + x];
            // soft translucent soot: alpha follows the density, with fine grain so it reads as grime
            float grain = ((mix(seed ^ 0xABCDL, x, y) >>> 40) & 0xFF) / 255F;
            float alpha = v * (0.75F + 0.25F * grain) * d.opacity();
            if (alpha < 0.03F) { img.setPixelRGBA(x, y, 0); continue; }
            int shade = d.shades()[Math.min(d.shades().length - 1, (int) ((1F - v) * d.shades().length))];
            int al = Math.min(255, Math.round(alpha * 255));
            img.setPixelRGBA(x, y, (al << 24) | ((shade & 0xFF) << 16) | (shade & 0xFF00) | ((shade >> 16) & 0xFF));
            cells[(y / d.px()) * d.blocks() + (x / d.px())] = true;
        }
        CELLS.put(id, cells);
        return new SpriteContents(id, new FrameSize(size, size), img, ResourceMetadata.EMPTY);
    }


    /** Value noise with g x g cells that wraps at the sheet edge (seamless tiling). */
    private static float tileNoise(long seed, int x, int y, int size, int g) {
        float fx = (x + 0.5F) * g / size, fy = (y + 0.5F) * g / size;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        float c00 = cv(seed, x0, y0, g), c10 = cv(seed, x0 + 1, y0, g), c01 = cv(seed, x0, y0 + 1, g), c11 = cv(seed, x0 + 1, y0 + 1, g);
        float top = c00 + (c10 - c00) * tx, bot = c01 + (c11 - c01) * tx;
        return top + (bot - top) * ty;
    }

    private static float cv(long seed, int i, int j, int g) {
        return ((mix(seed, Math.floorMod(i, g), Math.floorMod(j, g)) >>> 40) & 0xFFFF) / 65535F;
    }

    // ------------------------------------------------------------------ overlay quads (meshing)

    /**
     * The decal overlay for one base quad, or null if its slice of the sheet is empty / the quad isn't
     * axis-aligned. Copies positions, normals and light from the base quad; UVs from world position.
     */
    @Nullable
    public static BakedQuad overlay(BakedQuad base, BlockPos pos, TextureAtlasSprite wall, TextureAtlasSprite floor, int blocks) {
        Direction dir = base.getDirection();
        boolean flat = dir.getAxis() == Direction.Axis.Y;
        TextureAtlasSprite sheet = flat ? floor : wall;
        boolean[] cells = CELLS.get(sheet.contents().name());
        if (cells == null) return null;
        // which cell of the sheet this block face maps to (u: horizontal, v: down the wall / along z)
        int cu, cv;
        switch (dir.getAxis()) {
            case Z -> { cu = Math.floorMod(pos.getX(), blocks); cv = Math.floorMod(-pos.getY() - 1, blocks); }
            case X -> { cu = Math.floorMod(pos.getZ() + blocks / 2, blocks); cv = Math.floorMod(-pos.getY() - 1 + blocks / 3, blocks); }
            default -> { cu = Math.floorMod(pos.getX(), blocks); cv = Math.floorMod(pos.getZ(), blocks); }
        }
        if (!cells[cv * blocks + cu]) return null;
        int[] v = base.getVertices().clone();
        // only flat, axis-aligned faces (skip cross-shaped plants and other diagonal quads)
        int axis = dir.getAxis().ordinal();                               // X=0, Y=1, Z=2
        float plane = Float.intBitsToFloat(v[IQuadTransformer.POSITION + axis]);
        for (int i = 1; i < 4; i++) {
            if (Math.abs(Float.intBitsToFloat(v[i * IQuadTransformer.STRIDE + IQuadTransformer.POSITION + axis]) - plane) > 1e-4F) return null;
        }
        float du = sheet.getU1() - sheet.getU0(), dv = sheet.getV1() - sheet.getV0();
        for (int i = 0; i < 4; i++) {
            int o = i * IQuadTransformer.STRIDE;
            float lx = Float.intBitsToFloat(v[o + IQuadTransformer.POSITION]);
            float ly = Float.intBitsToFloat(v[o + IQuadTransformer.POSITION + 1]);
            float lz = Float.intBitsToFloat(v[o + IQuadTransformer.POSITION + 2]);
            float u, w;
            switch (dir.getAxis()) {
                case Z -> { u = cu + clamp01(lx); w = cv + (1F - clamp01(ly)); }
                case X -> { u = cu + clamp01(lz); w = cv + (1F - clamp01(ly)); }
                default -> { u = cu + clamp01(lx); w = cv + clamp01(lz); }
            }
            v[o + IQuadTransformer.UV0] = Float.floatToRawIntBits(sheet.getU0() + du * u / blocks);
            v[o + IQuadTransformer.UV0 + 1] = Float.floatToRawIntBits(sheet.getV0() + dv * w / blocks);
            v[o + IQuadTransformer.COLOR] = 0xFFFFFFFF;
        }
        return new BakedQuad(v, -1, dir, sheet, base.isShade(), base.hasAmbientOcclusion());
    }

    // ------------------------------------------------------------------ helpers

    private static long mix(long seed, int x, int y) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static float f(JsonObject o, String k, float d) {
        return o.has(k) ? o.get(k).getAsFloat() : d;
    }

    private static float clamp01(float v) {
        return Math.max(0F, Math.min(1F, v));
    }

    private static int hex(String s) {
        return Integer.parseInt(s.replace("#", ""), 16);
    }
}
