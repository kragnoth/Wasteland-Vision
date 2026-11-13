package com.kragnoth.wastelandvision.mixin;

import com.kragnoth.wastelandvision.WastelandState;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla chunk meshing (used when Sodium isn't installed, for sync rebuilds without Sodium, and by
 * Sable's "vanilla" ship renderer). Marks the thread as meshing for the whole compile so
 * BlockModelShaperMixin hands out hallucinated models only here.
 *
 * Targets BOTH compile() overloads explicitly: a name-only selector ("compile") only hooks the FIRST
 * match (the 4-arg one), but NeoForge's RebuildTask calls the 5-arg overload directly - before 0.14 the
 * vanilla mesher was never hooked. Nesting (4-arg delegating to 5-arg) is handled by a depth count.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    private static final String C4 = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";
    private static final String C5 = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";

    @Inject(method = {C4, C5}, at = @At("HEAD"), require = 1)
    private void wastelandvision$meshStart(CallbackInfoReturnable<?> cir) {
        WastelandState.enterMeshing();
    }

    @Inject(method = {C4, C5}, at = @At("RETURN"), require = 1)
    private void wastelandvision$meshEnd(CallbackInfoReturnable<?> cir) {
        WastelandState.exitMeshing();
    }
}
