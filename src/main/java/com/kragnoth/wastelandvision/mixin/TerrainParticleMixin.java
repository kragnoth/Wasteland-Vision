package com.kragnoth.wastelandvision.mixin;

import com.kragnoth.wastelandvision.client.WastelandModel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Break / run / landing particles follow the vision. Opt-in like meshing: only the sprite lookups
 * made BY terrain particles are remapped, never BlockModelShaper's methods for every caller (map mods
 * sample getParticleIcon for block colours).
 */
@Mixin(TerrainParticle.class)
public abstract class TerrainParticleMixin {

    /** Constructor path (no position known yet). */
    @WrapOperation(method = "<init>*", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockModelShaper;getParticleIcon(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite wastelandvision$particle(BlockModelShaper shaper, BlockState state,
                                                       Operation<TextureAtlasSprite> original) {
        return WastelandModel.particle(state, null, original.call(shaper, state));
    }

    /** NeoForge's position-aware path (block breaking, running, landing). */
    @WrapOperation(method = "updateSprite", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockModelShaper;getTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite wastelandvision$particleAt(BlockModelShaper shaper, BlockState state, Level level, BlockPos pos,
                                                         Operation<TextureAtlasSprite> original) {
        return WastelandModel.particle(state, pos, original.call(shaper, state, level, pos));
    }
}
