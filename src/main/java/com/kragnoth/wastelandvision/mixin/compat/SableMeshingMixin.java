package com.kragnoth.wastelandvision.mixin.compat;

import com.kragnoth.wastelandvision.WastelandState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable (verified against 2.0.6): ships/sub-levels with the "fancy" renderer are meshed in
 * SubLevelMeshBuilder#compile, which fetches models via BlockRenderDispatcher#getBlockModel.
 * (Sable's "vanilla" renderer goes through SectionCompiler, already covered.)
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.fancy.SubLevelMeshBuilder")
public abstract class SableMeshingMixin {

    @Inject(method = "compile", at = @At("HEAD"), require = 0)
    private void wastelandvision$meshStart(CallbackInfoReturnable<?> cir) {
        WastelandState.setMeshing(true);
    }

    @Inject(method = "compile", at = @At("RETURN"), require = 0)
    private void wastelandvision$meshEnd(CallbackInfoReturnable<?> cir) {
        WastelandState.setMeshing(false);
    }
}
