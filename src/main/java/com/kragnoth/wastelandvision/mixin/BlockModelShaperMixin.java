package com.kragnoth.wastelandvision.mixin;

import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.client.WastelandModel;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands out a WastelandModel wrapper ONLY for remapped states, ONLY while the vision is on, and ONLY
 * to world meshers (see WastelandState.meshing() and MeshingMixins). Anything else asking for a
 * model - including other mods that cast or cache models - gets the untouched original.
 */
@Mixin(BlockModelShaper.class)
public abstract class BlockModelShaperMixin {

    @Inject(method = "getBlockModel", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$wrap(BlockState state, CallbackInfoReturnable<BakedModel> cir) {
        if (!WastelandState.active || state == null || !WastelandState.meshing()) return;
        BakedModel wrapped = WastelandModel.wrapIfMapped(state, cir.getReturnValue());
        if (wrapped != null) cir.setReturnValue(wrapped);
    }
}
