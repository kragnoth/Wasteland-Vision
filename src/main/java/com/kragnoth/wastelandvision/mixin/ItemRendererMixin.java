package com.kragnoth.wastelandvision.mixin;

import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.client.SubstitutionTable;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item icon swap. ItemRenderer#getModel is the common path for GUI (inventories, JEI, Jade icons),
 * held items and dropped items. We return the stand-in item's REAL model (no wrappers), so nothing
 * that casts item models (Create's CustomRenderedItemModel etc.) is affected.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    @Inject(method = "getModel", at = @At("RETURN"), cancellable = true)
    private void wastelandvision$swapIcon(ItemStack stack, @Nullable Level level, @Nullable LivingEntity entity, int seed,
                                          CallbackInfoReturnable<BakedModel> cir) {
        if (!WastelandState.active || stack.isEmpty()) return;
        SubstitutionTable.ItemRule rule = SubstitutionTable.itemRule(stack.getItem());
        if (rule == null || rule.alt() == null) return;
        ItemRenderer self = (ItemRenderer) (Object) this;
        BakedModel alt = self.getItemModelShaper().getItemModel(rule.alt());
        if (alt == null) return;
        BakedModel resolved = alt.getOverrides().resolve(alt, new ItemStack(rule.alt()),
                level instanceof ClientLevel cl ? cl : null, entity, seed);
        if (resolved != null) cir.setReturnValue(resolved);
    }
}
