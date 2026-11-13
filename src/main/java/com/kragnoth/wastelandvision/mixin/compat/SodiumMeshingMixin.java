package com.kragnoth.wastelandvision.mixin.compat;

import com.kragnoth.wastelandvision.WastelandState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium (verified against 0.8.13 for 1.21.1): every chunk mesh - async and sync - is built in
 * ChunkBuilderMeshingTask#execute(ChunkBuildContext, CancellationToken), which fetches each block's
 * model through BlockModelShaper#getBlockModel. Marks the thread as meshing for that call.
 *
 * @Pseudo + no target args in the handlers: compiles and loads with no Sodium on the classpath, and
 * is silently skipped in packs without Sodium. If a future Sodium renames this, WastelandClient
 * warns (sodiumHookSeen never flips) instead of anything crashing.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask")
public abstract class SodiumMeshingMixin {

    @Inject(method = "execute", at = @At("HEAD"), require = 0)
    private void wastelandvision$meshStart(CallbackInfoReturnable<?> cir) {
        WastelandState.setMeshing(true);
        WastelandState.sodiumHookSeen = true;
    }

    @Inject(method = "execute", at = @At("RETURN"), require = 0)
    private void wastelandvision$meshEnd(CallbackInfoReturnable<?> cir) {
        WastelandState.setMeshing(false);
    }
}
