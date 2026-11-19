package com.townfolk.network;

import com.townfolk.Townfolk;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：一个收银台所在商店的账本。
 * @param inShop 收银台是不是放在商店分区里
 * @param tended 现在有没有店员在台后（有才营业）
 */
public record ShopDataPayload(BlockPos pos, boolean openScreen, boolean inShop, boolean tended, int money, List<Line> lines)
        implements CustomPacketPayload {

    /** 账本里的一行：一种货的现价、库存、今天卖了多少、今天多少人想买没买到。 */
    public record Line(String item, int price, int basePrice, int stock, int soldToday, int missedToday) {
        void write(FriendlyByteBuf buf) {
            buf.writeUtf(item);
            buf.writeVarInt(price);
            buf.writeVarInt(basePrice);
            buf.writeVarInt(stock);
            buf.writeVarInt(soldToday);
            buf.writeVarInt(missedToday);
        }

        static Line read(FriendlyByteBuf buf) {
            return new Line(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    public static final Type<ShopDataPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "shop_data"));

    public static final StreamCodec<FriendlyByteBuf, ShopDataPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeBoolean(payload.openScreen);
                buf.writeBoolean(payload.inShop);
                buf.writeBoolean(payload.tended);
                buf.writeVarInt(payload.money);
                buf.writeCollection(payload.lines, (b, line) -> line.write(b));
            },
            buf -> new ShopDataPayload(buf.readBlockPos(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(),
                    buf.readList(Line::read)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
