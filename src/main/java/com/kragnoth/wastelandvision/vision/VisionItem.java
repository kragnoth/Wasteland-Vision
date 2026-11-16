package com.kragnoth.wastelandvision.vision;

import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * An item declared in a vision.json "items" block (registered at startup, like stand-ins).
 * {"foundry_lens": {"name": "Foundry Lens", "texture": "wastelandvision:item/foundry_lens", "slot": "head", "key": true}}
 * "key": true makes it a key for its own vision (see "key_item"); "slot" (head/chest/legs/feet) makes it wearable.
 */
public record VisionItem(String vision, String name, @Nullable String displayName, String texture, boolean key,
                         DeferredItem<Item> item) {

    public String path() {
        return vision + "/" + name;
    }

    /** Plain item with an optional default display name (lang files still win). */
    public static class Plain extends Item {
        @Nullable private final String displayName;

        public Plain(Properties props, @Nullable String displayName) {
            super(props);
            this.displayName = displayName;
        }

        @Override
        public Component getName(ItemStack stack) {
            return displayName == null ? super.getName(stack) : Component.translatableWithFallback(getDescriptionId(stack), displayName);
        }
    }

    /** Worn in an armour slot (right-click to put it on). */
    public static class Wearable extends Plain implements Equipable {
        private final EquipmentSlot slot;

        public Wearable(Properties props, @Nullable String displayName, EquipmentSlot slot) {
            super(props, displayName);
            this.slot = slot;
        }

        @Override
        public EquipmentSlot getEquipmentSlot() {
            return slot;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            return swapWithEquipmentSlot(this, level, player, hand);
        }
    }
}
