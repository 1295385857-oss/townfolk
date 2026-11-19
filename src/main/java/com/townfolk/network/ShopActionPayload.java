package com.townfolk.network;

import com.townfolk.Townfolk;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：玩家在收银台账本界面里的操作。 */
public record ShopActionPayload(BlockPos pos, int action) implements CustomPacketPayload {
    /** 只请求最新数据。 */
    public static final int REFRESH = 0;
    /** 把钱箱里的金币取出来。 */
    public static final int TAKE_MONEY = 1;

    public static final Type<ShopActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "shop_action"));

    public static final StreamCodec<FriendlyByteBuf, ShopActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeVarInt(payload.action);
            },
            buf -> new ShopActionPayload(buf.readBlockPos(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
