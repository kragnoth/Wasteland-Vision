package com.kragnoth.wastelandvision;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A lens worn on the head (or held). A vision with {"key_item": "wastelandvision:vision_lens"} can only be switched on
 * while the player wears or holds it, and turns off if they take it away (unless they're locked into it).
 */
public class VisionLensItem extends Item implements Equipable {
    public VisionLensItem(Properties props) {
        super(props);
    }

    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return swapWithEquipmentSlot(this, level, player, hand);
    }
}
