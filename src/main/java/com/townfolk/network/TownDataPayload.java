package com.townfolk.network;

import com.townfolk.Townfolk;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：小镇数据。openScreen 为 true 时打开管理界面，否则只刷新已打开的界面。 */
public record TownDataPayload(TownView view, boolean openScreen) implements CustomPacketPayload {
    public static final Type<TownDataPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "town_data"));

    public static final StreamCodec<FriendlyByteBuf, TownDataPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                payload.view.write(buf);
                buf.writeBoolean(payload.openScreen);
            },
            buf -> new TownDataPayload(TownView.read(buf), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
