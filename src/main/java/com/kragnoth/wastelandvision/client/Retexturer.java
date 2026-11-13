package com.kragnoth.wastelandvision.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.IQuadTransformer;

/**
 * One compiled "then": "retexture" rule. Keeps the real model's geometry and swaps sprites:
 * each quad's absolute atlas UVs are re-expressed relative to the source sprite and mapped into
 * the target sprite, so partial-face UVs (slabs, Create parts), different resolutions and animated
 * sprites all work. Sprite and quad results are cached; a new Retexturer is built on every rebuild,
 * so caches never outlive an atlas reload.
 */
public final class Retexturer {
    /** source glob -> target sprite id, first match wins (exact ids are just globs without "*"). */
    private final List<Map.Entry<Pattern, ResourceLocation>> textures;
    /** Recolour sets, in order: a sprite matching a set's globs gets that set's runtime twin. */
    public record SetRef(String vision, String set, List<Pattern> sprites, int variants,
                         @javax.annotation.Nullable DecalRef decal) {}

    /** A set's world-space decal: sheet ids + size in blocks. */
    public record DecalRef(ResourceLocation wall, ResourceLocation floor, int blocks) {}

    /** resolved per source sprite: [wall, floor] sheet sprites, or EMPTY when the sprite has no decal */
    private final Map<TextureAtlasSprite, Object[]> decalCache = new ConcurrentHashMap<>();
    private static final Object[] NO_DECAL = new Object[0];

    private Boolean hasDecals;

    public boolean hasDecals() {
        Boolean h = hasDecals;
        if (h == null) {
            boolean any = blight != null && blight.hasDecals();
            for (SetRef r : recolor) any |= r.decal() != null;
            hasDecals = h = any;
        }
        return h;
    }

    /** Decal overlays for {@code quads} (the block's ORIGINAL quads) at {@code pos}; empty if none apply. */
    public List<BakedQuad> decals(List<BakedQuad> quads, net.minecraft.core.BlockPos pos) {
        Retexturer b = at(pos);
        if (b != this) return b.decals(quads, pos);
        List<BakedQuad> out = null;
        for (BakedQuad q : quads) {
            Object[] d = decalCache.computeIfAbsent(q.getSprite(), this::resolveDecal);
            if (d.length == 0) continue;
            BakedQuad o = Decals.overlay(q, pos, (TextureAtlasSprite) d[0], (TextureAtlasSprite) d[1], (Integer) d[2]);
            if (o == null) continue;
            if (out == null) out = new ArrayList<>();
            out.add(o);
        }
        return out == null ? List.of() : out;
    }

    private Object[] resolveDecal(TextureAtlasSprite src) {
        ResourceLocation id = src.contents().name();
        for (Map.Entry<Pattern, ResourceLocation> e : textures) {
            if (e.getKey().matcher(id.toString()).matches()) return NO_DECAL;   // explicit texture maps win
        }
        for (SetRef ref : recolor) {
            if (!Globs.any(ref.sprites(), id)) continue;
            if (ref.decal() == null) return NO_DECAL;
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            TextureAtlasSprite w = atlas.getSprite(ref.decal().wall()), f = atlas.getSprite(ref.decal().floor());
            if (w.contents().name().equals(MissingTextureAtlasSprite.getLocation())) return NO_DECAL;
            return new Object[] {w, f, ref.decal().blocks()};
        }
        return NO_DECAL;
    }
    private final List<SetRef> recolor;
    private final boolean dropTint;

    /** sprite -> its targets, one per variant (length 1 for plain texture maps / single-variant sets). */
    private final Map<TextureAtlasSprite, TextureAtlasSprite[]> spriteCache = new ConcurrentHashMap<>();
    /** one quad cache per variant index */
    @SuppressWarnings("unchecked")
    private final Map<BakedQuad, BakedQuad>[] quadCache = new Map[8];
    {
        for (int i = 0; i < quadCache.length; i++) quadCache[i] = new ConcurrentHashMap<>();
    }

    public Retexturer(List<Map.Entry<Pattern, ResourceLocation>> textures, List<SetRef> recolor, boolean dropTint) {
        this.textures = textures;
        this.recolor = recolor;
        this.dropTint = dropTint;
    }

    /**
     * "blight": inside blighted areas (2D world noise, so a whole tree - trunk and canopy - agrees) this
     * block uses {@code blight} instead (e.g. dead bark / dead sparse leaves). Null = no blight.
     */
    @javax.annotation.Nullable private Retexturer blight;
    private float blightThreshold = 2F, blightScale = 14F;

    public void setBlight(Retexturer alt, float threshold, float scale) {
        this.blight = alt;
        this.blightThreshold = threshold;
        this.blightScale = scale;
    }

    /** The retexturer that applies at {@code pos}: the blight alternate inside blighted areas, else this. */
    public Retexturer at(@javax.annotation.Nullable net.minecraft.core.BlockPos pos) {
        if (blight == null || pos == null) return this;
        return Variation.blight(pos, blightScale) >= blightThreshold ? blight : this;
    }

    /** Target sprite for {@code src} (variant 0), or {@code src} itself when unmapped (or the target is missing). */
    public TextureAtlasSprite target(TextureAtlasSprite src) {
        return spriteCache.computeIfAbsent(src, this::resolve)[0];
    }

    /** Target sprite for {@code src} at variant selector {@code n} (0..1, see Variation.pick). */
    public TextureAtlasSprite target(TextureAtlasSprite src, float n) {
        TextureAtlasSprite[] t = spriteCache.computeIfAbsent(src, this::resolve);
        return t[Math.min(t.length - 1, (int) (n * t.length))];
    }

    private TextureAtlasSprite[] resolve(TextureAtlasSprite src) {
        ResourceLocation id = src.contents().name();
        String s = id.toString();
        for (Map.Entry<Pattern, ResourceLocation> e : textures) {
            if (e.getKey().matcher(s).matches()) return new TextureAtlasSprite[] {sprite(e.getValue(), src, id)};
        }
        for (SetRef ref : recolor) {
            if (Globs.any(ref.sprites(), id)) {
                TextureAtlasSprite[] out = new TextureAtlasSprite[Math.max(1, ref.variants())];
                for (int k = 0; k < out.length; k++) {
                    out[k] = sprite(RecolorSource.twinId(ref.vision(), ref.set(), k, id), k == 0 ? src : out[0], id);
                }
                return out;
            }
        }
        return new TextureAtlasSprite[] {src};
    }

    private static TextureAtlasSprite sprite(ResourceLocation to, TextureAtlasSprite fallback, ResourceLocation forId) {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite t = atlas.getSprite(to);
        if (t.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            WastelandClient.LOGGER.warn("Wasteland Vision: retexture target {} (for {}) isn't in the block atlas", to, forId);
            return fallback;
        }
        return t;
    }

    private final Map<net.minecraft.client.resources.model.BakedModel, Boolean> affectsCache = new ConcurrentHashMap<>();

    /**
     * Would this rule visibly change {@code state}? Samples the REAL model once per unique model
     * (all faces + unculled, empty model data). States whose quads use none of the mapped sprites are
     * left unwrapped - so a loose rule like id_contains "stairs" doesn't wrap every stone stair.
     * Models that emit no quads without world data (dynamic/CTM) are kept, to be safe.
     */
    public boolean affects(net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.client.resources.model.BakedModel model =
                Minecraft.getInstance().getBlockRenderer().getBlockModel(state); // not meshing -> real model
        return affectsCache.computeIfAbsent(model, m -> sample(m, state));
    }

    private boolean sample(net.minecraft.client.resources.model.BakedModel model, net.minecraft.world.level.block.state.BlockState state) {
        try {
            net.minecraft.util.RandomSource rand = net.minecraft.util.RandomSource.create();
            int seen = 0;
            for (int i = -1; i < 6; i++) {
                net.minecraft.core.Direction side = i < 0 ? null : net.minecraft.core.Direction.from3DDataValue(i);
                rand.setSeed(42L);
                List<BakedQuad> quads = model.getQuads(state, side, rand,
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
                for (BakedQuad q : quads) {
                    seen++;
                    if (target(q.getSprite()) != q.getSprite() || (dropTint && q.getTintIndex() >= 0)) return true;
                }
            }
            return seen == 0;
        } catch (Throwable t) {
            return true; // model needs real world data: keep it, the remap is per-quad anyway
        }
    }

    public List<BakedQuad> remapAll(List<BakedQuad> quads) {
        return remapAll(quads, null);
    }

    /**
     * With a block position (chunk meshing): picks the texture variant for that position and applies
     * world-space vertex grime (see {@link Variation}). Without one: variant 0, no grime.
     */
    public List<BakedQuad> remapAll(List<BakedQuad> quads, @javax.annotation.Nullable net.minecraft.core.BlockPos pos) {
        if (quads.isEmpty()) return quads;
        Retexturer b = at(pos);
        if (b != this) return b.remapAll(quads, pos);
        float n = pos == null ? 0F : Variation.pick(pos);
        boolean grime = pos != null && Variation.grimeOn();
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad q : quads) {
            BakedQuad r = remap(q, n);
            out.add(grime ? Variation.grime(r, pos) : r);
        }
        return out;
    }

    public BakedQuad remap(BakedQuad q) {
        return remap(q, 0F);
    }

    public BakedQuad remap(BakedQuad q, float n) {
        TextureAtlasSprite src = q.getSprite();
        TextureAtlasSprite[] t = spriteCache.computeIfAbsent(src, this::resolve);
        int k = Math.min(Math.min(t.length, quadCache.length) - 1, (int) (n * t.length));
        TextureAtlasSprite dst = t[k];
        if (dst == src && !(dropTint && q.getTintIndex() >= 0)) return q;
        return quadCache[k].computeIfAbsent(q, key -> build(key, src, dst));
    }

    private BakedQuad build(BakedQuad q, TextureAtlasSprite src, TextureAtlasSprite dst) {
        int[] v = q.getVertices().clone();
        if (dst != src) {
            float su = src.getU1() - src.getU0(), sv = src.getV1() - src.getV0();
            for (int i = 0; i < 4; i++) {
                int o = i * IQuadTransformer.STRIDE + IQuadTransformer.UV0;
                float u = Float.intBitsToFloat(v[o]);
                float w = Float.intBitsToFloat(v[o + 1]);
                float ru = su == 0 ? 0 : (u - src.getU0()) / su;
                float rv = sv == 0 ? 0 : (w - src.getV0()) / sv;
                v[o] = Float.floatToRawIntBits(dst.getU0() + ru * (dst.getU1() - dst.getU0()));
                v[o + 1] = Float.floatToRawIntBits(dst.getV0() + rv * (dst.getV1() - dst.getV0()));
            }
        }
        return new BakedQuad(v, dropTint ? -1 : q.getTintIndex(), q.getDirection(), dst, q.isShade(), q.hasAmbientOcclusion());
    }
}
