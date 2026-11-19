package com.townfolk.network;

import com.townfolk.Townfolk;
import com.townfolk.town.ZoneRect;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：镇长在上帝视角里对分区的操作。 */
public record ZoneActionPayload(UUID townId, int action, UUID zoneId, int value, ZoneRect rect) implements CustomPacketPayload {
    /** value = 分区大类序号，rect = 范围。 */
    public static final int CREATE = 0;
    /** zoneId = 要删除的分区。 */
    public static final int DELETE = 1;
    /** zoneId = 分区，value = 产业序号。 */
    public static final int SET_INDUSTRY = 2;
    /** zoneId = 分区，value = 工作人数。 */
    public static final int SET_WORKERS = 3;

    private static final ZoneRect NO_RECT = new ZoneRect(0, 0, 0, 0);

    public static final Type<ZoneActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "zone_action"));

    public static final StreamCodec<FriendlyByteBuf, ZoneActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.townId);
                buf.writeVarInt(payload.action);
                buf.writeUUID(payload.zoneId);
                buf.writeVarInt(payload.value);
                buf.writeInt(payload.rect.minX());
                buf.writeInt(payload.rect.minZ());
                buf.writeInt(payload.rect.maxX());
                buf.writeInt(payload.rect.maxZ());
            },
            buf -> new ZoneActionPayload(buf.readUUID(), buf.readVarInt(), buf.readUUID(), buf.readVarInt(),
                    new ZoneRect(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt())));

    public static ZoneActionPayload create(UUID townId, int zoneType, ZoneRect rect) {
        return new ZoneActionPayload(townId, CREATE, Util.NIL_UUID, zoneType, rect);
    }

    public static ZoneActionPayload delete(UUID townId, UUID zoneId) {
        return new ZoneActionPayload(townId, DELETE, zoneId, 0, NO_RECT);
    }

    public static ZoneActionPayload setIndustry(UUID townId, UUID zoneId, int industry) {
        return new ZoneActionPayload(townId, SET_INDUSTRY, zoneId, industry, NO_RECT);
    }

    public static ZoneActionPayload setWorkers(UUID townId, UUID zoneId, int count) {
        return new ZoneActionPayload(townId, SET_WORKERS, zoneId, count, NO_RECT);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
