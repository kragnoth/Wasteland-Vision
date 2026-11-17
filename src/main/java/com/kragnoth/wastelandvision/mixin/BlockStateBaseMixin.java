package com.kragnoth.wastelandvision.mixin;

import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.client.PlantHallucination;
import com.kragnoth.wastelandvision.client.SubstitutionTable;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Plants/blocks the player can't "see" get an empty OUTLINE shape on the render thread only: crosshair
 * picking, the block outline and Jade/WTHIT raycasts pass through them. Collision shapes and anything
 * on the server thread (integrated server included) are untouched.
 *
 * 0.24.4: not while the client ticks entities ({@link WastelandState#realShapes}). The local player's movement runs
 * on the render thread too, and block code called from it (e.g. Atmospheric's yucca entityInside reads
 * getShape(...).bounds()) crashes on an empty shape ("No bounds for empty shape").
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    @Inject(method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void wastelandvision$hidePlantOutline(BlockGetter level, BlockPos pos, CollisionContext context,
                                                  CallbackInfoReturnable<VoxelShape> cir) {
        if (!WastelandState.active || !WastelandState.hidePlantOutlines || WastelandState.realShapes || SubstitutionTable.rebuilding) return;
        BlockState self = (BlockState) (Object) this;
        byte kind = SubstitutionTable.plantKind(self);
        if (kind != PlantHallucination.NONE) {
            if (RenderSystem.isOnRenderThread() && PlantHallucination.resolve(self, pos).isAir()) {
                cir.setReturnValue(Shapes.empty());
            }
            return;
        }
        BlockState sub = SubstitutionTable.get(self);
        if (sub != null && sub.isAir() && RenderSystem.isOnRenderThread()) {
            cir.setReturnValue(Shapes.empty()); // vines, lichen, lily pads, moss carpet
        }
    }
}
