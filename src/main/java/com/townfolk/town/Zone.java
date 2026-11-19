package com.townfolk.town;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/** 镇长在上帝视角里划出的一块分区。 */
public class Zone {
    public static final int MAX_WORKERS = 8;

    public final UUID id;
    public final ZoneType type;
    public final ZoneRect rect;
    public Industry industry = Industry.NONE;
    /** 镇长给这个分区定的工作人数。 */
    public int workerTarget;
    /** 这个分区开商店时的账本：各种货的价格和销量。 */
    public final ShopData shop = new ShopData();

    public Zone(UUID id, ZoneType type, ZoneRect rect) {
        this.id = id;
        this.type = type;
        this.rect = rect;
    }

    /** 分区正中的位置，高度用 y。 */
    public BlockPos center(int y) {
        return new BlockPos((rect.minX() + rect.maxX()) / 2, y, (rect.minZ() + rect.maxZ()) / 2);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putInt("Type", type.ordinal());
        tag.putInt("Industry", industry.ordinal());
        tag.putInt("Workers", workerTarget);
        if (!shop.goods.isEmpty()) tag.put("Shop", shop.save());
        tag.putInt("MinX", rect.minX());
        tag.putInt("MinZ", rect.minZ());
        tag.putInt("MaxX", rect.maxX());
        tag.putInt("MaxZ", rect.maxZ());
        return tag;
    }

    public static Zone load(CompoundTag tag) {
        Zone zone = new Zone(tag.getUUID("Id"), ZoneType.byOrdinal(tag.getInt("Type")),
                new ZoneRect(tag.getInt("MinX"), tag.getInt("MinZ"), tag.getInt("MaxX"), tag.getInt("MaxZ")));
        Industry industry = Industry.byOrdinal(tag.getInt("Industry"));
        zone.industry = industry.zoneType == zone.type ? industry : Industry.NONE;
        zone.workerTarget = zone.industry.hasWorkers() ? Mth.clamp(tag.getInt("Workers"), 0, MAX_WORKERS) : 0;
        zone.shop.load(tag.getList("Shop", ShopData.listTagType()));
        return zone;
    }
}
