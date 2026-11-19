package com.townfolk.town;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * 一户的住处：一栋独栋，或者楼房里的一套房。里面有几张床就能住几个人。
 * 住处是从世界里的床和墙认出来的，会定期重新认一遍；居民记的是自己睡哪张床，住在哪一户由床推出来。
 */
public final class Home {
    public final UUID zoneId;
    public final boolean detached;
    /** 这一户里所有床头的位置，按固定顺序排好。 */
    public final List<BlockPos> beds;

    public Home(UUID zoneId, boolean detached, List<BlockPos> beds) {
        this.zoneId = zoneId;
        this.detached = detached;
        List<BlockPos> sorted = new ArrayList<>(beds);
        Collections.sort(sorted);
        this.beds = List.copyOf(sorted);
    }

    /** 代表这一户的位置（第一张床）。 */
    public BlockPos pos() {
        return beds.get(0);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Zone", zoneId);
        tag.putBoolean("Detached", detached);
        ListTag list = new ListTag();
        for (BlockPos bed : beds) {
            list.add(NbtUtils.writeBlockPos(bed));
        }
        tag.put("Beds", list);
        return tag;
    }

    public static Home load(CompoundTag tag) {
        List<BlockPos> beds = new ArrayList<>();
        ListTag list = tag.getList("Beds", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            int[] xyz = list.getIntArray(i);
            if (xyz.length == 3) beds.add(new BlockPos(xyz[0], xyz[1], xyz[2]));
        }
        return new Home(tag.getUUID("Zone"), tag.getBoolean("Detached"), beds);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Home home && detached == home.detached && zoneId.equals(home.zoneId) && beds.equals(home.beds);
    }

    @Override
    public int hashCode() {
        return 31 * zoneId.hashCode() + beds.hashCode();
    }
}
