package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kragnoth.wastelandvision.WastelandVision;
import com.kragnoth.wastelandvision.vision.Vision;
import com.kragnoth.wastelandvision.vision.VisionManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;

/**
 * Block-atlas sprite source ("wastelandvision:recolor", declared in the generated pack's
 * assets/minecraft/atlases/blocks.json). For every vision's "recolor_sets", every texture whose id
 * matches the set's "sprites" globs gets a gradient-mapped twin stitched into the atlas at
 *   wastelandvision:recolor/<vision>/<set>/<namespace>/<path>
 * Retexture rules with "recolor": "<set>" then point quads at those twins - no hand-made art needed.
 *
 * The twin is served as a normal Resource (re-encoded PNG + the original's metadata), so vanilla
 * handles animation frames, .mcmeta and mipmaps exactly as for the original.
 */
public final class RecolorSource implements SpriteSource {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(WastelandVision.MODID, "recolor");
    public static final MapCodec<RecolorSource> CODEC = MapCodec.unit(RecolorSource::new);
    public static final SpriteSourceType TYPE = new SpriteSourceType(CODEC);

    public static void onRegister(RegisterSpriteSourceTypesEvent event) {
        event.register(ID, TYPE);
    }

    public static ResourceLocation twinId(String vision, String set, ResourceLocation src) {
        return twinId(vision, set, 0, src);
    }

    /** Variant k's twin. Variant 0 keeps the pre-0.14 id, so single-variant sets are unchanged. */
    public static ResourceLocation twinId(String vision, String set, int variant, ResourceLocation src) {
        return ResourceLocation.fromNamespaceAndPath(WastelandVision.MODID,
                "recolor/" + vision + "/" + set + (variant == 0 ? "" : "/v" + variant) + "/" + src.getNamespace() + "/" + src.getPath());
    }

    /**
     * Parsed "recolor_sets" entry.
     * @param palettes   zero or more dark->light palettes ("palette" or "palettes"); none = keep the
     *                   texture's own colours (grade/stain only)
     * @param weights    relative weight per palette
     * @param clump      0 = one palette per sprite (picked per texture, seeded by its id);
     *                   N = each NxN pixel clump picks its own palette (mixed autumn canopies, mottling)
     * @param knockout   0..1: share of clumps knocked out to transparent (sparse leaves; cutout blocks only)
     * @param mix        0..1: how much the gradient-mapped colour replaces the (graded) original
     * @param saturation saturation of the original colour before mixing (0 = grey, 1 = unchanged)
     * @param brightness brightness multiplier of the original colour before mixing
     * @param stain      optional soot/tar blotches and drips painted over the result
     */
    public record RecolorSet(String vision, String name, List<Pattern> sprites, int[][] palettes, int[] weights,
                             int clump, float knockout, float gamma, float contrast, float bias,
                             float mix, float saturation, float brightness, @Nullable Stain stain,
                             int variants, float spread, @Nullable String decal) {

        /**
         * Grime multiplier of variant k: variants run from clean (1 - spread) to filthy (1 + spread).
         * Applied to the stain's coverage/strength and to how far saturation/brightness/mix move.
         */
        public float intensity(int k) {
            if (variants <= 1) return 1F;
            return Math.max(0F, 1F + spread * (2F * k / (variants - 1) - 1F));
        }

        /** This set with variant k's intensity applied. */
        public RecolorSet variant(int k) {
            float m = intensity(k);
            if (m == 1F) return this;
            Stain st = stain == null ? null : new Stain(stain.color(), clamp01(stain.coverage() * m),
                    clamp01(stain.strength() * (0.5F + 0.5F * m)), stain.clump(), clamp01(stain.drips() * m), stain.dripLength());
            return new RecolorSet(vision, name, sprites, palettes, weights, clump, knockout, gamma, contrast, bias,
                    clamp01(mix * m), Math.max(0F, 1F - (1F - saturation) * m), Math.max(0F, 1F - (1F - brightness) * m),
                    st, variants, spread, decal);
        }
    }

    /**
     * "stain": grime painted over a recoloured texture. Deterministic per texture, identical on every
     * animation frame.
     * @param color      the grime colour (soot, tar, rust...)
     * @param coverage   0..1 share of clumps that get a blotch
     * @param strength   0..1 how far a blotch / drip pulls toward {@code color}
     * @param clump      blotch scale in pixels (per 16px; scaled for higher resolutions) - soft-edged noise
     * @param drips      0..1 share of pixel columns with a streak running down from the top edge
     * @param dripLength 0..1 longest streak, as a share of the frame height
     */
    public record Stain(int color, float coverage, float strength, int clump, float drips, float dripLength) {}

    public static List<RecolorSet> sets(Vision v) {
        List<RecolorSet> out = new ArrayList<>();
        if (!v.json().has("recolor_sets")) return out;
        for (Map.Entry<String, JsonElement> e : v.json().getAsJsonObject("recolor_sets").entrySet()) {
            if (e.getKey().startsWith("_")) continue;   // "_comment" entries
            try {
                JsonObject o = e.getValue().getAsJsonObject();
                List<String> globs = new ArrayList<>();
                for (JsonElement g : o.getAsJsonArray("sprites")) globs.add(g.getAsString());
                List<int[]> pals = new ArrayList<>();
                if (o.has("palettes")) {
                    for (JsonElement p : o.getAsJsonArray("palettes")) pals.add(palette(p.getAsJsonArray()));
                } else if (o.has("palette")) {
                    pals.add(palette(o.getAsJsonArray("palette")));
                }
                int[] weights = new int[pals.size()];
                java.util.Arrays.fill(weights, 1);
                if (o.has("weights")) {
                    var w = o.getAsJsonArray("weights");
                    for (int i = 0; i < weights.length && i < w.size(); i++) weights[i] = Math.max(0, w.get(i).getAsInt());
                }
                Stain stain = o.has("stain") ? stain(o.getAsJsonObject("stain")) : null;
                float mix = pals.isEmpty() ? 0F : clamp01(f(o, "mix", 1.0F));
                float sat = Math.max(0F, f(o, "saturation", 1.0F));
                float bri = Math.max(0F, f(o, "brightness", 1.0F));
                if (pals.isEmpty() && stain == null && sat == 1F && bri == 1F) {
                    throw new IllegalArgumentException("needs a palette, a stain, or saturation/brightness - it would change nothing");
                }
                out.add(new RecolorSet(v.id(), e.getKey(), Globs.compileAll(globs), pals.toArray(new int[0][]), weights,
                        o.has("clump") ? Math.max(0, o.get("clump").getAsInt()) : 0,
                        clamp01(f(o, "knockout", 0F)),
                        f(o, "gamma", 1.0F), f(o, "contrast", 0.8F), f(o, "bias", 0.0F),
                        mix, sat, bri, stain,
                        o.has("variants") ? Math.max(1, Math.min(8, o.get("variants").getAsInt())) : 1,
                        Math.max(0F, f(o, "variant_spread", 0.7F)),
                        o.has("decal") ? o.get("decal").getAsString() : null));
            } catch (Exception ex) {
                WastelandClient.LOGGER.error("Wasteland Vision: [{}] recolor set '{}' is invalid: {}", v.id(), e.getKey(), ex.toString());
            }
        }
        return out;
    }

    private static Stain stain(JsonObject o) {
        return new Stain(hex(o.has("color") ? o.get("color").getAsString() : "#14110e"),
                clamp01(f(o, "coverage", 0.3F)), clamp01(f(o, "strength", 0.5F)),
                Math.max(1, o.has("clump") ? o.get("clump").getAsInt() : 2),
                clamp01(f(o, "drips", 0F)), clamp01(f(o, "drip_length", 0.6F)));
    }

    private static float f(JsonObject o, String key, float def) {
        return o.has(key) ? o.get(key).getAsFloat() : def;
    }

    private static float clamp01(float v) {
        return Math.max(0F, Math.min(1F, v));
    }

    private static int hex(String s) {
        return Integer.parseInt(s.replace("#", ""), 16);
    }

    private static int[] palette(com.google.gson.JsonArray arr) {
        int[] p = new int[arr.size()];
        for (int i = 0; i < p.length; i++) p[i] = hex(arr.get(i).getAsString());
        if (p.length < 2) throw new IllegalArgumentException("a palette needs at least 2 colours");
        return p;
    }

    @Override
    public void run(ResourceManager resourceManager, SpriteSource.Output output) {
        VisionManager.reloadAll(); // F3+T picks up recolor_sets edits without a restart
        List<RecolorSet> all = new ArrayList<>();
        int sheets = 0;
        for (Vision v : VisionManager.all()) {
            all.addAll(sets(v));
            for (Decals.Decal d : Decals.parse(v)) {            // world-space grime sheets
                output.add(Decals.id(d.vision(), d.name(), false), loader -> Decals.sheet(d, false));
                output.add(Decals.id(d.vision(), d.name(), true), loader -> Decals.sheet(d, true));
                sheets += 2;
            }
        }
        if (sheets > 0) WastelandClient.LOGGER.info("Wasteland Vision: queued {} decal sheets for the block atlas", sheets);
        if (all.isEmpty()) return;
        int made = 0;
        for (Map.Entry<ResourceLocation, Resource> e : TEXTURE_ID_CONVERTER.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation spriteId = TEXTURE_ID_CONVERTER.fileToId(e.getKey());
            if (spriteId.getNamespace().equals(WastelandVision.MODID)) continue;
            Resource res = e.getValue();
            for (RecolorSet set : all) {
                if (!Globs.any(set.sprites(), spriteId)) continue;
                for (int k = 0; k < set.variants(); k++) {
                    RecolorSet vs = set.variant(k);
                    long salt = k * 0x632BE59BD9B4E019L;
                    output.add(twinId(set.vision(), set.name(), k, spriteId),
                            new Resource(res.source(), () -> recolored(res, vs, spriteId, salt), res::metadata));
                    made++;
                }
            }
        }
        WastelandClient.LOGGER.info("Wasteland Vision: queued {} recoloured sprites for the block atlas", made);
    }

    private static InputStream recolored(Resource res, RecolorSet set, ResourceLocation spriteId, long salt) throws IOException {
        try (InputStream in = res.open(); NativeImage img = NativeImage.read(in)) {
            gradientMap(img, set, (spriteId.toString().hashCode() * 31L + set.name().hashCode()) ^ salt);
            return new ByteArrayInputStream(img.asByteArray());
        }
    }

    /**
     * Luminance -> palette gradient map, normalised to the image's own luminance range so every
     * palette step is used (same technique as tools/gen_textures.py), blended over the original
     * (graded by saturation/brightness) by {@code mix}, then stained. NativeImage pixels are ABGR.
     * With several palettes, a palette is chosen per sprite (clump = 0) or per clump, deterministically
     * from {@code seed}, so the same texture always recolours the same way.
     *
     * Animated textures are vertical strips of frames: every noise lookup uses the row WITHIN the
     * frame, so clumps, knockouts and stains sit still instead of flickering between frames.
     */
    static void gradientMap(NativeImage img, RecolorSet set, long seed) {
        int w = img.getWidth(), h = img.getHeight();
        int frameH = (h > w && h % w == 0) ? w : h;
        int scale = Math.max(1, w / 16);                     // 32x/64x packs: clumps and stains scale up
        float lo = 1, hi = 0;
        boolean mapped = set.palettes().length > 0 && set.mix() > 0;
        if (mapped) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = img.getPixelRGBA(x, y);
                if ((c >>> 24) == 0) continue;
                float l = lum(c);
                lo = Math.min(lo, l);
                hi = Math.max(hi, l);
            }
        }
        float range = hi > lo ? hi - lo : 1;
        int total = 0;
        for (int wt : set.weights()) total += wt;
        int clump = set.clump() * scale;
        Stain st = set.stain();
        int[] drip = st != null ? dripLengths(st, seed, w, frameH, scale) : null;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = img.getPixelRGBA(x, y);
            int a = c >>> 24;
            if (a == 0) continue;
            int fy = y % frameH;
            long cell = clump > 0 ? mix(seed, x / clump, fy / clump) : mix(seed, 0, 0);
            if (clump > 0 && set.knockout() > 0
                    && (cell >>> 40 & 0xFFFF) / 65536F < set.knockout()
                    && (mix(seed ^ 0x5DEECE66DL, x, fy) >>> 40 & 0xFF) < 218) {   // ~85% of a knocked clump
                img.setPixelRGBA(x, y, 0);
                continue;
            }
            // the original colour, graded
            float r = (c & 0xFF) / 255F, g = ((c >> 8) & 0xFF) / 255F, b = ((c >> 16) & 0xFF) / 255F;
            if (set.saturation() != 1F || set.brightness() != 1F) {
                float l = 0.299F * r + 0.587F * g + 0.114F * b;
                r = (l + (r - l) * set.saturation()) * set.brightness();
                g = (l + (g - l) * set.saturation()) * set.brightness();
                b = (l + (b - l) * set.saturation()) * set.brightness();
            }
            if (mapped) {
                int[] pal = pick(set, total, cell);
                float n = (lum(c) - lo) / range;
                float t = 0.5F + ((float) Math.pow(n, set.gamma()) - 0.5F) * set.contrast() + set.bias();
                int rgb = sample(pal, Math.max(0, Math.min(1, t)));
                float k = set.mix();
                r += (((rgb >> 16) & 0xFF) / 255F - r) * k;
                g += (((rgb >> 8) & 0xFF) / 255F - g) * k;
                b += ((rgb & 0xFF) / 255F - b) * k;
            }
            if (st != null) {
                float amt = stainAmount(st, seed, x, fy, w, frameH, scale, drip);
                if (amt > 0) {
                    r += (((st.color() >> 16) & 0xFF) / 255F - r) * amt;
                    g += (((st.color() >> 8) & 0xFF) / 255F - g) * amt;
                    b += ((st.color() & 0xFF) / 255F - b) * amt;
                }
            }
            img.setPixelRGBA(x, y, (a << 24) | (c255(b) << 16) | (c255(g) << 8) | c255(r));
        }
    }

    /** Per-column streak length in pixels (0 = no drip), from the top edge down. */
    private static int[] dripLengths(Stain st, long seed, int w, int frameH, int scale) {
        int[] out = new int[w];
        if (st.drips() <= 0) return out;
        long s = seed ^ 0x2545F4914F6CDD1DL;
        for (int col = 0; col < w / scale; col++) {
            long hsh = mix(s, col, 7);
            if ((hsh >>> 40 & 0xFFFF) / 65536F >= st.drips()) continue;
            float len = (0.35F + 0.65F * ((hsh >>> 8 & 0xFF) / 255F)) * st.dripLength() * frameH;
            if (col > 0 && out[(col - 1) * scale] > 0) continue;              // never two neighbouring columns
            for (int k = 0; k < scale; k++) out[col * scale + k] = Math.max(1, Math.round(len));
        }
        return out;
    }

    /**
     * 0..1 pull toward the stain colour: soft blotches (value noise that wraps at the frame edges, so
     * the stained texture still tiles seamlessly) plus drips fading downward from the top edge.
     */
    private static float stainAmount(Stain st, long seed, int x, int fy, int w, int frameH, int scale, int[] drip) {
        float amt = 0;
        if (st.coverage() > 0) {
            int sc = st.clump() * scale;
            float n = valueNoise(seed ^ 0x632BE59BD9B4E019L, x, fy, sc, Math.max(1, w / sc), Math.max(1, frameH / sc));
            float t = (n - (1F - st.coverage())) / 0.38F;                    // wide, soft threshold
            if (t > 0) {
                t = Math.min(1F, t);
                t = t * t * (3 - 2 * t);                                        // smoothstep: no hard rims
                float grain = (mix(seed ^ 0x1B873593L, x, fy) >>> 40 & 0xFF) / 255F;
                amt = st.strength() * t * (0.8F + 0.2F * grain);
            }
        }
        int len = drip[x];
        if (len > 0 && fy < len) {
            float fade = 1F - (float) fy / len;
            amt = Math.max(amt, st.strength() * 0.7F * fade * fade);          // drips stay fainter than blotches
        }
        return Math.min(1F, amt);
    }

    /** Smooth 0..1 value noise on a {@code gw x gh} grid of {@code cell}-pixel cells, wrapping at the edges. */
    private static float valueNoise(long seed, int x, int y, int cell, int gw, int gh) {
        float fx = (x + 0.5F) / cell - 0.5F, fy = (y + 0.5F) / cell - 0.5F;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        float a = cellValue(seed, x0, y0, gw, gh), b = cellValue(seed, x0 + 1, y0, gw, gh);
        float c = cellValue(seed, x0, y0 + 1, gw, gh), d = cellValue(seed, x0 + 1, y0 + 1, gw, gh);
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty;
    }

    private static float cellValue(long seed, int i, int j, int gw, int gh) {
        return (mix(seed, Math.floorMod(i, gw), Math.floorMod(j, gh)) >>> 40 & 0xFFFF) / 65535F;
    }

    private static int c255(float v) {
        int i = (int) (v * 255F + 0.5F);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    private static int[] pick(RecolorSet set, int total, long cell) {
        int[][] pals = set.palettes();
        if (pals.length == 1 || total <= 0) return pals[0];
        int roll = (int) Long.remainderUnsigned(cell, total);
        for (int i = 0; i < pals.length; i++) {
            roll -= set.weights()[i];
            if (roll < 0) return pals[i];
        }
        return pals[pals.length - 1];
    }

    /** SplitMix64-style hash of (seed, x, y). */
    private static long mix(long seed, int x, int y) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static float lum(int abgr) {
        int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF;
        return (0.299F * r + 0.587F * g + 0.114F * b) / 255F;
    }

    private static int sample(int[] pal, float t) {
        float f = t * (pal.length - 1);
        int i = Math.min((int) f, pal.length - 2);
        float k = f - i;
        int a = pal[i], b = pal[i + 1];
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k + 0.5F);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k + 0.5F);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k + 0.5F);
        return (r << 16) | (g << 8) | bl;
    }

    @Override
    public SpriteSourceType type() {
        return TYPE;
    }
}
