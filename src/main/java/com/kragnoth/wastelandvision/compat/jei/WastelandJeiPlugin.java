package com.kragnoth.wastelandvision.compat.jei;

import com.kragnoth.wastelandvision.vision.VisionManager;
import com.kragnoth.wastelandvision.WastelandVision;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI integration. JEI draws items through vanilla ItemRenderer and reads names from
 * ItemStack#getHoverName, so the hallucinated icons/names already apply inside JEI (they are swapped
 * at the item-model / name level). This plugin just hides the mod's internal stand-in items so the
 * illusion doesn't leak through the ingredient list.
 */
@JeiPlugin
public final class WastelandJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(WastelandVision.MODID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        // Only remove stand-ins JEI actually listed: removing absent ones logs an ERROR per item.
        java.util.Set<net.minecraft.world.item.Item> standIns = new java.util.HashSet<>(VisionManager.allStandInItems());
        List<ItemStack> internal = runtime.getIngredientManager().getAllItemStacks().stream()
                .filter(st -> standIns.contains(st.getItem()))
                .toList();
        if (!internal.isEmpty()) runtime.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, internal);
    }
}
