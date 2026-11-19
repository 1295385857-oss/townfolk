package com.townfolk.town;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 一个维度里所有小镇的存档数据。 */
public class TownSavedData extends SavedData {
    private static final String FILE_NAME = "townfolk_towns";
    private static final SavedData.Factory<TownSavedData> FACTORY = new SavedData.Factory<>(TownSavedData::new, TownSavedData::load);

    private final Map<UUID, Town> towns = new LinkedHashMap<>();

    public static TownSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public Collection<Town> towns() {
        return towns.values();
    }

    @Nullable
    public Town getTown(@Nullable UUID id) {
        return id == null ? null : towns.get(id);
    }

    /** 镇公所正好在这个位置的小镇。 */
    @Nullable
    public Town getTownAt(BlockPos center) {
        for (Town town : towns.values()) {
            if (town.center.equals(center)) return town;
        }
        return null;
    }

    /** 镇公所离 pos 的水平距离在 distance 以内的最近的小镇。 */
    @Nullable
    public Town nearestTown(BlockPos pos, int distance) {
        Town best = null;
        double bestDist = (double) distance * distance;
        for (Town town : towns.values()) {
            double dist = town.horizontalDistSqr(pos);
            if (dist <= bestDist) {
                best = town;
                bestDist = dist;
            }
        }
        return best;
    }

    public void addTown(Town town) {
        towns.put(town.id, town);
        setDirty();
    }

    public void removeTown(UUID id) {
        if (towns.remove(id) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Town town : towns.values()) {
            list.add(town.save());
        }
        tag.put("Towns", list);
        return tag;
    }

    private static TownSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TownSavedData data = new TownSavedData();
        ListTag list = tag.getList("Towns", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Town town = Town.load(list.getCompound(i));
            data.towns.put(town.id, town);
        }
        return data;
    }
}
