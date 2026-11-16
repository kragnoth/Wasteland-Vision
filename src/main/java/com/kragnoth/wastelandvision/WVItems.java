package com.kragnoth.wastelandvision;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WVItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WastelandVision.MODID);
    public static final DeferredItem<VisionLensItem> VISION_LENS = ITEMS.register("vision_lens",
            () -> new VisionLensItem(new Item.Properties().stacksTo(1)));

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                e.accept(VISION_LENS.get());
                com.kragnoth.wastelandvision.vision.VisionManager.allVisionItems().forEach(e::accept);
            }
        });
    }

    private WVItems() {}
}
