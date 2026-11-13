package com.kragnoth.wastelandvision.client;

import com.kragnoth.wastelandvision.WastelandState;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Per-position plant decisions for "then": "plant" rules. Pure function of (rule, half, position),
 * so chunk meshing (worker threads), outline raycasts (render thread), Jade, and both halves of
 * tall plants always agree.
 */
public final class PlantHallucination {
    public static final byte NONE = 0;
    public static final byte SHORT = 1;
    public static final byte TALL_LOWER = 2;
    public static final byte TALL_UPPER = 3;

    /**
     * Compiled plant rule.
     * @param shorts       weighted single-block stand-ins (may include vanilla blocks)
     * @param cumulative   running weight totals, same length as shorts
     * @param tallLower    lower half of the two-high stand-in, or null
     * @param tallChance   % of visible TWO-HIGH plants that use the tall stand-in
     * @param visible      % of plants that render at all (-1 = use the vision default)
     */
    public record PlantRule(BlockState[] shorts, int[] cumulative, @Nullable BlockState tallLower,
                            @Nullable BlockState tallUpper, int tallChance, int visible, boolean tallSelf,
                            @Nullable PlantRule blight, float blightThreshold, float blightScale) {}

    private PlantHallucination() {}

    public static byte kindOf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? TALL_UPPER : TALL_LOWER;
        }
        return SHORT;
    }

    /**
     * What to draw for this real plant at this position: AIR (hidden), a stand-in state, or {@code real}
     * itself ("self" entries keep the living plant). Inside blighted areas the rule's "blight" variant is
     * used, so dead trees stand in dead undergrowth.
     */
    public static BlockState resolve(BlockState real, BlockPos pos) {
        byte kind = SubstitutionTable.plantKind(real);
        PlantRule rule = SubstitutionTable.plantRule(real);
        BlockState air = Blocks.AIR.defaultBlockState();
        if (kind == NONE || rule == null) return air;

        // Both halves of a tall plant hash (and test blight at) the LOWER block's position.
        int y = kind == TALL_UPPER ? pos.getY() - 1 : pos.getY();
        if (rule.blight() != null
                && Variation.blight(new BlockPos(pos.getX(), y, pos.getZ()), rule.blightScale()) >= rule.blightThreshold()) {
            rule = rule.blight();
        }
        long h = mix(pos.getX(), y, pos.getZ());
        int visible = rule.visible() >= 0 ? rule.visible() : WastelandState.visiblePlantPercent;
        if ((int) Long.remainderUnsigned(h, 100) >= visible) return air;

        if (kind != SHORT && (rule.tallLower() != null || rule.tallSelf())
                && (int) Long.remainderUnsigned(h >>> 16, 100) < rule.tallChance()) {
            if (rule.tallSelf()) return real;
            return kind == TALL_UPPER ? rule.tallUpper() : rule.tallLower();
        }
        if (rule.shorts().length == 0) return air;
        int total = rule.cumulative()[rule.cumulative().length - 1];
        int pick = (int) Long.remainderUnsigned(h >>> 32, Math.max(1, total));
        BlockState chosen = rule.shorts()[rule.shorts().length - 1];
        for (int i = 0; i < rule.cumulative().length; i++) {
            if (pick < rule.cumulative()[i]) { chosen = rule.shorts()[i]; break; }
        }
        if (chosen == null) return real;               // "self": keep the living plant (both halves)
        if (kind == TALL_UPPER) return air;            // short stand-in at the bottom, nothing on top
        return chosen;
    }

    /** SplitMix64-style avalanche over the block position. */
    private static long mix(int x, int y, int z) {
        long h = (x * 3129871L) ^ (z * 116129781L) ^ (long) y * 0x9E3779B97F4A7C15L;
        h += 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
