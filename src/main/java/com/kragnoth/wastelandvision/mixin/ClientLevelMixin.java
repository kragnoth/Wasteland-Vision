package com.kragnoth.wastelandvision.mixin;

import com.kragnoth.wastelandvision.Atmosphere;
import com.kragnoth.wastelandvision.ColorGrade;
import com.kragnoth.wastelandvision.WastelandState;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Atmosphere bits that live on the client level, not the biome:
 *  - "ambient_particles": soot/ash drifting through the air while a vision is on. Piggybacks on the
 *    random positions vanilla already samples each tick for ambient effects, so the cost when off is
 *    one volatile read per sample and nothing is ever sent to the server.
 *  - "clouds": cloud colour pulled toward the vision's tint (smog banks). Sodium's cloud renderer
 *    reads the same method.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "doAnimateTick", at = @At("TAIL"))
    private void wastelandvision$ambient(int posX, int posY, int posZ, int range, RandomSource random,
                                         @Nullable Block block, BlockPos.MutableBlockPos pos, CallbackInfo ci) {
        if (!WastelandState.active) return;
        Atmosphere.AmbientParticle[] ps = Atmosphere.particles;
        if (ps.length == 0) return;
        ClientLevel self = (ClientLevel) (Object) this;
        for (Atmosphere.AmbientParticle p : ps) {
            if (random.nextFloat() >= p.probability()) continue;
            if (!self.getBlockState(pos).isAir()) return;
            if (p.skyOnly() && !self.canSeeSky(pos)) return;
            self.addParticle(p.options(), pos.getX() + random.nextDouble(), pos.getY() + random.nextDouble(),
                    pos.getZ() + random.nextDouble(), 0.0, 0.0, 0.0);
        }
    }

    @Inject(method = "getCloudColor", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$clouds(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        Atmosphere.Tint t = Atmosphere.clouds;
        if (!WastelandState.active || t == null) return;
        Vec3 c = cir.getReturnValue();
        double[] g = ColorGrade.apply(c.x, c.y, c.z, t);
        cir.setReturnValue(new Vec3(g[0], g[1], g[2]));
    }

    /** Entity ticking (player movement, entityInside callbacks) sees real block shapes - see BlockStateBaseMixin. */
    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void wastelandvision$realShapesOn(CallbackInfo ci) {
        WastelandState.realShapes = true;
    }

    @Inject(method = "tickEntities", at = @At("RETURN"))
    private void wastelandvision$realShapesOff(CallbackInfo ci) {
        WastelandState.realShapes = false;
    }
}
