package com.kragnoth.wastelandvision.compat.jade;

import com.kragnoth.wastelandvision.WastelandState;
import com.kragnoth.wastelandvision.WastelandVision;
import com.kragnoth.wastelandvision.client.PlantHallucination;
import com.kragnoth.wastelandvision.client.SubstitutionTable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade integration (only active while the vision is on):
 *  - remapped blocks: fake block = real item, so name/icon go through the item-level hooks
 *  - per-position field plants: icon of the exact stand-in, "Withered Poppy"-style name
 *  - vanished blocks (hidden plants, vines...): no tooltip at all
 *  - mod name: always the REAL block's mod, never "Wasteland Vision"
 *
 * Only loaded by Jade when Jade is installed (@WailaPlugin("jade")).
 */
@WailaPlugin("jade")
public final class WastelandJadePlugin implements IWailaPlugin {
    private static final String ORIGIN_KEY = "wastelandvision_origin";
    private IWailaClientRegistration client;

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        this.client = registration;
        registration.addRayTraceCallback(this::onRayTrace);
        registration.addItemModNameCallback(WastelandJadePlugin::modName);
    }

    private Accessor<?> onRayTrace(net.minecraft.world.phys.HitResult hit, Accessor<?> accessor, Accessor<?> original) {
        if (!WastelandState.active || !(accessor instanceof BlockAccessor block)) return accessor;
        BlockState real = block.getBlockState();

        byte kind = SubstitutionTable.plantKind(real);
        if (kind != PlantHallucination.NONE) {
            BlockState sub = PlantHallucination.resolve(real, block.getPosition());
            if (sub.isAir()) return null;            // hidden plant: no plate at all
            return fakeStandIn(block, real, sub);
        }

        BlockState sub = SubstitutionTable.get(real);
        if (sub == null) return accessor;            // not hallucinated
        if (sub.isAir()) return null;                // vines, lichen, ...

        // Jade names ordinary blocks via Block#getName(), which bypasses our ItemStack rename.
        // Hand it the REAL item as a fake block instead: the name goes through ItemStackMixin
        // ("Dead Oak Log"), the icon through ItemRendererMixin (dead log), and the mod line stays
        // the real mod.
        Item realItem = real.getBlock().asItem();
        if (realItem != Items.AIR) {
            return client.blockAccessor().from(block).fakeBlock(new ItemStack(realItem)).build();
        }
        return fakeStandIn(block, real, sub);
    }

    /**
     * Per-position plants (and item-less blocks): icon = the exact stand-in drawn at this spot,
     * name = the same "Withered Poppy"-style name JEI/inventories use, mod line = real block's mod.
     */
    private Accessor<?> fakeStandIn(BlockAccessor block, BlockState real, BlockState sub) {
        if (sub.getBlock().asItem() == Items.AIR) return block;
        ItemStack stack = new ItemStack(sub.getBlock());

        Item realItem = real.getBlock().asItem();
        SubstitutionTable.ItemRule rule = realItem == Items.AIR ? null : SubstitutionTable.itemRule(realItem);
        String fmt = rule != null && rule.nameFormat() != null ? rule.nameFormat() : "%s";
        stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback(fmt, fmt, real.getBlock().getName()));

        CompoundTag tag = new CompoundTag();
        tag.putString(ORIGIN_KEY, BuiltInRegistries.BLOCK.getKey(real.getBlock()).getNamespace());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return client.blockAccessor().from(block).fakeBlock(stack).build();
    }

    /** Never let "Wasteland Vision" show up as the mod name of something in the world. */
    private static String modName(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        String modId = null;
        if (data != null && data.contains(ORIGIN_KEY)) {
            modId = data.copyTag().getString(ORIGIN_KEY);
        } else if (WastelandState.active
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(WastelandVision.MODID)) {
            modId = "minecraft";
        }
        if (modId == null) return null;
        String id = modId;
        return ModList.get().getModContainerById(id)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(id);
    }
}
