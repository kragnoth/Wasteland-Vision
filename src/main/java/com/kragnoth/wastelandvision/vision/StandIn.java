package com.kragnoth.wastelandvision.vision;

import com.google.gson.JsonObject;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * One stand-in block declared in a vision.json "standins" entry. Registered as
 * {@code wastelandvision:<vision>/<name>} (hidden block + hidden item).
 *
 * @param custom true = the vision ships its own blockstate/models for it (no generation)
 */
public record StandIn(String vision, String name, StandInType type, JsonObject textures, boolean custom,
                      DeferredBlock<Block> block, DeferredItem<BlockItem> item) {

    /** Registry path, e.g. "wasteland/dead_log". */
    public String path() {
        return vision + "/" + name;
    }
}
