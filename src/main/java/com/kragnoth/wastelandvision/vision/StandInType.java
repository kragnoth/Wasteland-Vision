package com.kragnoth.wastelandvision.vision;

import java.util.Locale;
import java.util.function.Function;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Shapes a stand-in can have. Each maps to a vanilla block class (so properties like axis, facing,
 * half, shape, connections exist and can be copied from the real block) and to an asset template
 * that {@code VisionPacks} generates.
 */
public enum StandInType {
    CUBE(Block::new, false),
    CUBE_BOTTOM_TOP(Block::new, false),
    PILLAR(RotatedPillarBlock::new, false),
    LEAVES(Block::new, true),
    STAIRS(p -> new StairBlock(Blocks.STONE.defaultBlockState(), p), false),
    SLAB(SlabBlock::new, false),
    FENCE(FenceBlock::new, false),
    WALL(WallBlock::new, false),
    CARPET(CarpetBlock::new, true),
    CROSS(Block::new, true),
    CROP(Block::new, true),
    TALL_CROSS(DoublePlantBlock::new, true);

    private final Function<BlockBehaviour.Properties, ? extends Block> factory;
    private final boolean seeThrough;

    StandInType(Function<BlockBehaviour.Properties, ? extends Block> factory, boolean seeThrough) {
        this.factory = factory;
        this.seeThrough = seeThrough;
    }

    public Function<BlockBehaviour.Properties, ? extends Block> factory() {
        return factory;
    }

    /** Stand-ins are never placed in the world; properties only matter for rendering. */
    public BlockBehaviour.Properties properties() {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of().strength(1.0F).sound(SoundType.STONE);
        if (seeThrough) p = p.noOcclusion();
        if (this == CROSS || this == TALL_CROSS || this == CROP) p = p.noCollission().instabreak();
        return p;
    }

    public boolean isPlantLike() {
        return this == CROSS || this == TALL_CROSS || this == CROP;
    }

    public static StandInType parse(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
