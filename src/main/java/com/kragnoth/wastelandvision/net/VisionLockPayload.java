package com.kragnoth.wastelandvision.net;

import com.kragnoth.wastelandvision.WastelandVision;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: the vision this player is locked into ("" = not locked). Set with /wastelandvision lock. */
public record VisionLockPayload(String vision) implements CustomPacketPayload {
    public static final Type<VisionLockPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WastelandVision.MODID, "lock"));
    public static final StreamCodec<ByteBuf, VisionLockPayload> CODEC = ByteBufCodecs.STRING_UTF8.map(VisionLockPayload::new, VisionLockPayload::vision);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
