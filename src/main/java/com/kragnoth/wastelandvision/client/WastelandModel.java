package com.kragnoth.wastelandvision.client;

import com.kragnoth.wastelandvision.WastelandState;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.common.util.TriState;

/**
 * Wrapper for REMAPPED blockstate models only (handed out by BlockModelShaperMixin). While the wasteland is visible, quads/render types/AO are borrowed
 * from a substitute state's model instead. Hitboxes, drops, light etc. all still come from the real
 * block — only chunk meshing is fooled.
 *
 * Two kinds of substitution:
 *  - per-STATE (logs, planks, ground...): looked up directly from SubstitutionTable.
 *  - per-POSITION (field plants): getModelData() runs once per block during meshing with the
 *    BlockPos, so the decision is stashed in ModelData and read back in getQuads().
 *
 * Cost when off, or for unmapped blocks: one volatile read + one array lookup per call.
 */
public final class WastelandModel extends BakedModelWrapper<BakedModel> {
    /** Per-position plant decision (AIR = render nothing). */
    public static final ModelProperty<BlockState> PLANT_SUB = new ModelProperty<>();
    /** Block position of a retextured block, for per-position variants and world-space grime. */
    public static final ModelProperty<BlockPos> RETEX_POS = new ModelProperty<>();

    /** original model -> wrapper; models are immutable after baking so identity caching is safe. */
    private static final java.util.Map<BakedModel, WastelandModel> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public WastelandModel(BakedModel original) {
        super(original);
    }

    /** @return wrapper for remapped states, or null to leave the model alone. */
    @Nullable
    public static BakedModel wrapIfMapped(BlockState state, BakedModel original) {
        if (original == null || original instanceof WastelandModel) return null;
        if (SubstitutionTable.get(state) == null && SubstitutionTable.plantKind(state) == PlantHallucination.NONE
                && SubstitutionTable.retexturer(state) == null) return null;
        return CACHE.computeIfAbsent(original, WastelandModel::new);
    }

    /** Called when models are re-baked (resource reload). */
    public static void clearCache() {
        CACHE.clear();
    }

    @Nullable
    private static BlockState substitute(@Nullable BlockState state) {
        if (!WastelandState.active || state == null) return null;
        return SubstitutionTable.get(state);
    }

    private static boolean isPlant(@Nullable BlockState state) {
        return WastelandState.active && state != null
                && SubstitutionTable.plantKind(state) != PlantHallucination.NONE;
    }

    /**
     * The stand-in's REAL model. While meshing, getBlockModel() hands out our wrapper again, so if the
     * stand-in is itself mapped (a rule glob also matching a stand-in, or A->B / B->A between two rules)
     * calling into the wrapper recursed forever (0.19.0 StackOverflowError in getRenderTypes). Always
     * unwrap: a substitution is exactly one level deep.
     */
    private static BakedModel modelFor(BlockState sub) {
        BakedModel m = Minecraft.getInstance().getBlockRenderer().getBlockModel(sub);
        return m instanceof WastelandModel w ? w.originalModel : m;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        ModelData data = super.getModelData(level, pos, state, modelData);
        if (isPlant(state)) {
            BlockState sub = PlantHallucination.resolve(state, pos);
            return data.derive().with(PLANT_SUB, sub).build();
        }
        if (WastelandState.active && SubstitutionTable.get(state) == null && SubstitutionTable.retexturer(state) != null) {
            return data.derive().with(RETEX_POS, pos.immutable()).build();
        }
        return data;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        if (isPlant(state)) {
            BlockState sub = data.get(PLANT_SUB);
            // No position data (non-chunk render path) => treat as hidden.
            if (sub == null || sub.isAir()) return List.of();
            if (sub == state) return super.getQuads(state, side, rand, data, renderType);   // "self": the living plant
            return modelFor(sub).getQuads(sub, side, rand, ModelData.EMPTY, renderType);
        }
        BlockState sub = substitute(state);
        if (sub == null) {
            Retexturer rx = retexturer(state);
            if (rx == null) return super.getQuads(state, side, rand, data, renderType);
            BlockPos pos = data.get(RETEX_POS);
            if (pos != null && renderType == DECAL_TYPE && rx.hasDecals()) {
                // the decal layer: this block's own cutout quads (if it has any) + world-space overlays
                ChunkRenderTypeSet base = super.getRenderTypes(state, RandomSource.create(42L), data);
                List<BakedQuad> out = new java.util.ArrayList<>();
                if (base.contains(DECAL_TYPE)) out.addAll(rx.remapAll(super.getQuads(state, side, rand, data, renderType), pos));
                java.util.Set<BakedQuad> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (RenderType t : base) {
                    for (BakedQuad q : super.getQuads(state, side, rand, data, t)) {
                        if (seen.add(q)) out.addAll(rx.decals(List.of(q), pos));
                    }
                }
                return out;
            }
            return rx.remapAll(super.getQuads(state, side, rand, data, renderType), pos);
        }
        if (sub.isAir()) return List.of();
        return modelFor(sub).getQuads(sub, side, rand, ModelData.EMPTY, renderType);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        if (isPlant(state)) return List.of();
        BlockState sub = substitute(state);
        if (sub == null) {
            Retexturer rx = retexturer(state);
            List<BakedQuad> quads = super.getQuads(state, side, rand);
            return rx == null ? quads : rx.remapAll(quads);
        }
        if (sub.isAir()) return List.of();
        return modelFor(sub).getQuads(sub, side, rand);
    }

    @Nullable
    private static Retexturer retexturer(@Nullable BlockState state) {
        if (!WastelandState.active || state == null) return null;
        return SubstitutionTable.retexturer(state);
    }

    /**
     * Break/run/landing particle sprite while the vision is on: the stand-in's particle, the
     * retextured sprite, or (with a position) the per-position plant stand-in's particle.
     */
    public static net.minecraft.client.renderer.texture.TextureAtlasSprite particle(
            BlockState state, @Nullable BlockPos pos, net.minecraft.client.renderer.texture.TextureAtlasSprite original) {
        if (!WastelandState.active || state == null) return original;
        BlockState sub = SubstitutionTable.get(state);
        if (sub == null && pos != null && SubstitutionTable.plantKind(state) != PlantHallucination.NONE) {
            sub = PlantHallucination.resolve(state, pos);
        }
        if (sub != null) {
            return sub.isAir() ? original : modelFor(sub).getParticleIcon(ModelData.EMPTY);
        }
        Retexturer rx = SubstitutionTable.retexturer(state);
        return rx == null ? original : rx.target(original);
    }

    /** Render layer of decal overlays: translucent, so soot fades softly (drawn over the solid block face). */
    private static final RenderType DECAL_TYPE = RenderType.translucent();
    private static final ChunkRenderTypeSet DECAL_SET = ChunkRenderTypeSet.of(DECAL_TYPE);

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        // Plants: keep the real plant's (cutout) render types; dead stand-ins are cutout too.
        BlockState sub = substitute(state);
        if (sub == null) {
            ChunkRenderTypeSet base = super.getRenderTypes(state, rand, data);
            Retexturer rx = retexturer(state);
            if (rx != null && rx.hasDecals() && data.get(RETEX_POS) != null && !base.contains(DECAL_TYPE)) {
                return ChunkRenderTypeSet.union(base, DECAL_SET);
            }
            return base;
        }
        if (sub.isAir()) return super.getRenderTypes(state, rand, data);
        return modelFor(sub).getRenderTypes(sub, rand, ModelData.EMPTY);
    }

    @Override
    public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
        if (isPlant(state)) {
            BlockState sub = data.get(PLANT_SUB);
            if (sub != null && !sub.isAir() && sub != state) return modelFor(sub).useAmbientOcclusion(sub, ModelData.EMPTY, renderType);
            return super.useAmbientOcclusion(state, data, renderType);
        }
        BlockState sub = substitute(state);
        if (sub == null || sub.isAir()) return super.useAmbientOcclusion(state, data, renderType);
        return modelFor(sub).useAmbientOcclusion(sub, ModelData.EMPTY, renderType);
    }
}
