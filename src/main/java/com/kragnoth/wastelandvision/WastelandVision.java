package com.kragnoth.wastelandvision;

import com.kragnoth.wastelandvision.client.WastelandClient;
import com.kragnoth.wastelandvision.vision.VisionManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(WastelandVision.MODID)
public final class WastelandVision {
    public static final String MODID = "wastelandvision";

    public WastelandVision(IEventBus modBus, Dist dist, ModContainer container) {
        // Reads config/wastelandvision/visions/*/vision.json and registers every declared stand-in
        // block (hidden, no creative tab). Same on client and server -> registries stay in sync.
        VisionManager.init(modBus);
        WVItems.init(modBus);
        com.kragnoth.wastelandvision.net.VisionServer.init(modBus);
        container.registerConfig(ModConfig.Type.CLIENT, WVConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, WVServerConfig.SPEC);

        if (dist == Dist.CLIENT) {
            WastelandClient.init(modBus);
        }
    }
}
