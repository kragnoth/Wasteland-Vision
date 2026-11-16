package com.kragnoth.wastelandvision;

import com.kragnoth.wastelandvision.vision.VisionManager;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Lists items declared in vision.json "items" blocks in the Tools tab. The mod registers no items of its own. */
public final class WVItems {
    public static void init(IEventBus modBus) {
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) VisionManager.allVisionItems().forEach(e::accept);
        });
    }

    private WVItems() {}
}
