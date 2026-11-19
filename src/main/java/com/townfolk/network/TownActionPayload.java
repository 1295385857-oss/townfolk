package com.townfolk.network;

import com.townfolk.Townfolk;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：玩家在管理界面里做的操作。 */
public record TownActionPayload(UUID townId, int action, UUID target, int value, String text) implements CustomPacketPayload {
    /** 只请求最新数据。 */
    public static final int REFRESH = 0;
    /** value = 新的人口上限。 */
    public static final int SET_POP_CAP = 1;
    /** text = 新镇名。 */
    public static final int RENAME = 3;
    /** value = 钟点 × 16 + 活动序号。 */
    public static final int SET_SCHEDULE = 4;
    /** 时间表恢复默认。 */
    public static final int RESET_SCHEDULE = 5;
    /** 把背包里的金币全部存进金库。 */
    public static final int DEPOSIT = 6;
    /** value = 从金库取出多少金币。 */
    public static final int WITHDRAW = 7;
    /** value = 安家费的金额，0 表示不发。 */
    public static final int SET_ALLOWANCE = 8;

    public static final Type<TownActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "town_action"));

    public static final StreamCodec<FriendlyByteBuf, TownActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.townId);
                buf.writeVarInt(payload.action);
                buf.writeUUID(payload.target);
                buf.writeVarInt(payload.value);
                buf.writeUtf(payload.text, 64);
            },
            buf -> new TownActionPayload(buf.readUUID(), buf.readVarInt(), buf.readUUID(), buf.readVarInt(), buf.readUtf(64)));

    public static TownActionPayload refresh(UUID townId) {
        return new TownActionPayload(townId, REFRESH, Util.NIL_UUID, 0, "");
    }

    public static TownActionPayload setPopCap(UUID townId, int cap) {
        return new TownActionPayload(townId, SET_POP_CAP, Util.NIL_UUID, cap, "");
    }

    public static TownActionPayload rename(UUID townId, String name) {
        return new TownActionPayload(townId, RENAME, Util.NIL_UUID, 0, name);
    }

    public static TownActionPayload setSchedule(UUID townId, int hour, int activity) {
        return new TownActionPayload(townId, SET_SCHEDULE, Util.NIL_UUID, hour * 16 + activity, "");
    }

    public static TownActionPayload simple(UUID townId, int action, int value) {
        return new TownActionPayload(townId, action, Util.NIL_UUID, value, "");
    }

    public static TownActionPayload resetSchedule(UUID townId) {
        return new TownActionPayload(townId, RESET_SCHEDULE, Util.NIL_UUID, 0, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
