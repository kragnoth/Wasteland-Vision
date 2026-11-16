package com.kragnoth.wastelandvision.net;

import com.kragnoth.wastelandvision.WastelandVision;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: "my vision is now on/off, and this is the one". The server mirrors it as a wv_<vision> tag. */
public record VisionStatePayload(boolean active, String vision) implements CustomPacketPayload {
    public static final Type<VisionStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WastelandVision.MODID, "state"));
    public static final StreamCodec<ByteBuf, VisionStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, VisionStatePayload::active,
            ByteBufCodecs.STRING_UTF8, VisionStatePayload::vision,
            VisionStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
