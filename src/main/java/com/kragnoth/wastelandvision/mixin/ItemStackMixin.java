package com.kragnoth.wastelandvision.mixin;

import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.client.SubstitutionTable;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "Oak Log" -> "Dead Oak Log" while the vision is on. Render thread only, so the integrated server,
 * commands, loot etc. never see the fake name. Player-renamed items (CUSTOM_NAME) are left alone.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$hallucinatedName(CallbackInfoReturnable<Component> cir) {
        if (!WastelandState.active || !RenderSystem.isOnRenderThread()) return;
        ItemStack self = (ItemStack) (Object) this;
        if (self.has(DataComponents.CUSTOM_NAME)) return;
        SubstitutionTable.ItemRule rule = SubstitutionTable.itemRule(self.getItem());
        if (rule != null && rule.nameFormat() != null) {
            // nameFormat is a lang key OR a literal format like "Dead %s" (used as the fallback)
            cir.setReturnValue(Component.translatableWithFallback(rule.nameFormat(), rule.nameFormat(), cir.getReturnValue()));
        }
    }
}
