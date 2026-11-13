package com.kragnoth.wastelandvision;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config: config/wastelandvision-client.toml. Everything about WHAT a vision looks like lives
 * in config/wastelandvision/visions/<id>/vision.json instead.
 */
public final class WVConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> ACTIVE_VISION = B
            .comment("Which vision (folder name in config/wastelandvision/visions/) the toggle key shows.",
                     "The cycle key changes this and saves it.")
            .define("activeVision", "soot_and_tar");

    public static final ModConfigSpec.BooleanValue START_ACTIVE = B
            .comment("Start every session with the vision already on (e.g. a modpack built around it).")
            .define("startActive", false);

    public static final ModConfigSpec SPEC = B.build();

    private WVConfig() {}
}
