package com.kragnoth.wastelandvision;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-world server config: <world>/serverconfig/wastelandvision-server.toml. NeoForge syncs SERVER
 * configs to every client on join, so a player's own config files can't override it. Modpacks ship
 * it as defaultconfigs/wastelandvision-server.toml so every new world gets it.
 */
public final class WVServerConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FORCE_VISION = B
            .comment("Lock every player into 'forcedVision': it turns on when they join and they can't toggle",
                     "it off or cycle away from it - unless they're allowed to bypass (below).")
            .define("forceVision", false);

    public static final ModConfigSpec.ConfigValue<String> FORCED_VISION = B
            .comment("Vision folder name (config/wastelandvision/visions/<name>) to lock players into.")
            .define("forcedVision", "soot_and_tar");

    public static final ModConfigSpec.BooleanValue CREATIVE_BYPASS = B
            .comment("Players in creative (or spectator) mode may toggle/cycle freely.")
            .define("creativeBypass", true);

    public static final ModConfigSpec.IntValue BYPASS_PERMISSION_LEVEL = B
            .comment("Players with at least this permission level may toggle/cycle freely (2 = op/cheats; 5 = nobody).")
            .defineInRange("bypassPermissionLevel", 2, 0, 5);

    public static final ModConfigSpec SPEC = B.build();

    private WVServerConfig() {}
}
