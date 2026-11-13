package com.kragnoth.wastelandvision.mixin;

import com.kragnoth.wastelandvision.Atmosphere;
import com.kragnoth.wastelandvision.ColorGrade;
import com.kragnoth.wastelandvision.WastelandState;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-grades biome colours toward the active vision's atmosphere tints. Grass/foliage/water are baked
 * into chunk meshes (re-meshed on toggle); sky/fog are read every frame. Client-only mixin.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin {

    @Unique
    private static int wv$grade(int rgb, Atmosphere.Tint t) {
        return (WastelandState.active && t != null) ? ColorGrade.apply(rgb, t) : rgb;
    }

    @Inject(method = "getGrassColor", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$grass(double x, double z, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(wv$grade(cir.getReturnValue(), Atmosphere.grass));
    }

    @Inject(method = "getFoliageColor", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$foliage(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(wv$grade(cir.getReturnValue(), Atmosphere.foliage));
    }

    @Inject(method = "getWaterColor", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$water(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(wv$grade(cir.getReturnValue(), Atmosphere.water));
    }

    @Inject(method = "getWaterFogColor", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$waterFog(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(wv$grade(cir.getReturnValue(), Atmosphere.water));
    }

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$sky(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(wv$grade(cir.getReturnValue(), Atmosphere.sky));
    }

    @Inject(method = "getFogColor", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$fog(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(wv$grade(cir.getReturnValue(), Atmosphere.fog));
    }
}
