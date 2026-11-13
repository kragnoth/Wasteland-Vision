package com.kragnoth.wastelandvision.client;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.model.IQuadTransformer;

/**
 * World-space variation for retextured blocks (vision.json "variation"), so identical blocks don't
 * look stamped:
 *
 *  1. VARIANT PICK - recolour sets with "variants": N have N twins per texture (clean -> filthy, each
 *     with its own stain pattern). Each block position picks one from smooth low-frequency noise plus
 *     a little per-block jitter: neighbours are similar but never identical, and dirty patches fade
 *     into cleaner stretches instead of tiling.
 *
 *  2. VERTEX GRIME - every quad corner is darkened toward "grime.color" by 3D value noise sampled at
 *     its WORLD position. Shared corners of neighbouring blocks get the same value, so soot shading
 *     flows across block edges. Baked into the chunk mesh (vertex colour), no per-frame cost.
 *
 * Both only run inside chunk meshing (they need the block position from ModelData).
 */
public final class Variation {
    private Variation() {}

    /** Variant-noise feature size in blocks, and how much per-block randomness is mixed in (0..1). */
    private static volatile float scale = 7F, jitter = 0.35F;
    /** Vertex grime: strength 0 = off. */
    private static volatile float grimeStrength = 0F, grimeScale = 5F, grimeBias = 0.45F;
    private static volatile float gr = 0.16F, gg = 0.13F, gb = 0.10F;

    public static void load(@Nullable JsonObject v) {
        scale = 7F; jitter = 0.35F; grimeStrength = 0F; grimeScale = 5F; grimeBias = 0.45F;
        gr = 0.16F; gg = 0.13F; gb = 0.10F;
        if (v == null) return;
        if (v.has("scale")) scale = Math.max(1F, v.get("scale").getAsFloat());
        if (v.has("jitter")) jitter = clamp01(v.get("jitter").getAsFloat());
        if (v.has("grime") && v.get("grime").isJsonObject()) {
            JsonObject g = v.getAsJsonObject("grime");
            grimeStrength = g.has("strength") ? clamp01(g.get("strength").getAsFloat()) : 0.3F;
            if (g.has("scale")) grimeScale = Math.max(1F, g.get("scale").getAsFloat());
            if (g.has("coverage")) grimeBias = 1F - clamp01(g.get("coverage").getAsFloat());
            if (g.has("color")) {
                int c = Integer.parseInt(g.get("color").getAsString().replace("#", ""), 16);
                gr = ((c >> 16) & 0xFF) / 255F; gg = ((c >> 8) & 0xFF) / 255F; gb = (c & 0xFF) / 255F;
            }
        }
    }

    public static boolean grimeOn() {
        return grimeStrength > 0;
    }

    /** 0..1 variant selector for a block: smooth world noise + per-block jitter. */
    public static float pick(BlockPos pos) {
        float s = scale;
        float n = 0.65F * noise(pos.getX() / s, pos.getY() / s, pos.getZ() / s, 101)
                + 0.35F * noise(pos.getX() / (s * 0.45F), pos.getY() / (s * 0.45F), pos.getZ() / (s * 0.45F), 202);
        float j = (hash(pos.getX(), pos.getY(), pos.getZ(), 303) & 0xFFFF) / 65535F - 0.5F;
        // stretch the (centre-heavy) noise so the extreme variants actually appear
        n = (n - 0.5F) * 1.7F + 0.5F + j * jitter;
        return Math.max(0F, Math.min(0.9999F, n));
    }

    /** Copy of {@code q} with each corner's vertex colour pulled toward the grime colour by world noise. */
    public static BakedQuad grime(BakedQuad q, BlockPos pos) {
        int[] v = q.getVertices().clone();
        float s = grimeScale, k = grimeStrength;
        // Same strength on every face: weighting by direction made fronts of blocks visibly darker than
        // their tops (0.17 bug). Keep "strength" low instead - decals carry the heavy grime.
        for (int i = 0; i < 4; i++) {
            int o = i * IQuadTransformer.STRIDE;
            float x = pos.getX() + Float.intBitsToFloat(v[o + IQuadTransformer.POSITION]);
            float y = pos.getY() + Float.intBitsToFloat(v[o + IQuadTransformer.POSITION + 1]);
            float z = pos.getZ() + Float.intBitsToFloat(v[o + IQuadTransformer.POSITION + 2]);
            float n = 0.6F * noise(x / s, y / s, z / s, 404) + 0.4F * noise(x / (s * 0.4F), y / (s * 0.4F), z / (s * 0.4F), 505);
            // gentle ramp over the dirtiest part of the noise (no hard-edged islands)
            float a = (n - (grimeBias - 0.15F)) / 0.4F;
            if (a <= 0) continue;
            a = Math.min(1F, a);
            a = a * a * (3 - 2 * a) * k;
            int c = v[o + IQuadTransformer.COLOR];                     // ABGR (little-endian RGBA bytes)
            int r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
            r = (int) (r * (1F - a + a * gr * 1.6F));
            g = (int) (g * (1F - a + a * gg * 1.6F));
            b = (int) (b * (1F - a + a * gb * 1.6F));
            v[o + IQuadTransformer.COLOR] = (c & 0xFF000000) | (Math.min(255, b) << 16) | (Math.min(255, g) << 8) | Math.min(255, r);
        }
        return new BakedQuad(v, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion());
    }

    /** 0..1 blight field over x/z only (independent of y, so a whole tree gets the same answer). */
    public static float blight(BlockPos pos, float scale) {
        float x = pos.getX() / scale, z = pos.getZ() / scale;
        return 0.7F * noise(x, 0.5F, z, 606) + 0.3F * noise(x * 2.3F, 0.5F, z * 2.3F, 707);
    }

    /** Threshold so that {@code percent}% of the area is blighted (sampled quantile of the field). */
    public static float blightThreshold(float percent, float scale) {
        if (percent <= 0) return 2F;
        if (percent >= 100) return -1F;
        int n = 20000;
        float[] s = new float[n];
        java.util.Random r = new java.util.Random(1234);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) s[i] = blight(p.set(r.nextInt(200000) - 100000, 64, r.nextInt(200000) - 100000), scale);
        java.util.Arrays.sort(s);
        return s[Math.min(n - 1, Math.max(0, Math.round(n * (1F - percent / 100F))))];
    }

    // ------------------------------------------------------------------ noise

    /** Smooth 3D value noise, 0..1. */
    static float noise(float x, float y, float z, int salt) {
        int x0 = floor(x), y0 = floor(y), z0 = floor(z);
        float tx = fade(x - x0), ty = fade(y - y0), tz = fade(z - z0);
        float c000 = cell(x0, y0, z0, salt), c100 = cell(x0 + 1, y0, z0, salt);
        float c010 = cell(x0, y0 + 1, z0, salt), c110 = cell(x0 + 1, y0 + 1, z0, salt);
        float c001 = cell(x0, y0, z0 + 1, salt), c101 = cell(x0 + 1, y0, z0 + 1, salt);
        float c011 = cell(x0, y0 + 1, z0 + 1, salt), c111 = cell(x0 + 1, y0 + 1, z0 + 1, salt);
        float a = lerp(c000, c100, tx), b = lerp(c010, c110, tx), c = lerp(c001, c101, tx), d = lerp(c011, c111, tx);
        return lerp(lerp(a, b, ty), lerp(c, d, ty), tz);
    }

    private static float cell(int x, int y, int z, int salt) {
        return (hash(x, y, z, salt) & 0xFFFF) / 65535F;
    }

    private static int hash(int x, int y, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ salt * 0x27D4EB2F165667C5L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return (int) (h ^ (h >>> 31));
    }

    private static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static float fade(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return Math.max(0F, Math.min(1F, v));
    }
}
