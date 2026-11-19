package com.townfolk.network;

import com.townfolk.town.ZoneRect;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** 服务端发给管理界面和上帝视角的小镇快照。 */
public record TownView(UUID id, String name, String mayorName, boolean isMayor, int popCap, int beds, int homes, int freeHomes,
                       int treasury, int allowance, BlockPos center, String schedule, List<ResidentView> residents, List<ZoneView> zones) {
    public static final int HOME_NONE = 0;
    public static final int HOME_APARTMENT = 1;
    public static final int HOME_DETACHED = 2;

    public record ResidentView(UUID id, String name, boolean male, int age, int job, int status, int home, int money, int satiety,
                               String spouse, String father, String mother) {
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name);
            buf.writeBoolean(male);
            buf.writeVarInt(age);
            buf.writeVarInt(job);
            buf.writeVarInt(status);
            buf.writeVarInt(home);
            buf.writeVarInt(money);
            buf.writeVarInt(satiety);
            buf.writeUtf(spouse);
            buf.writeUtf(father);
            buf.writeUtf(mother);
        }

        static ResidentView read(FriendlyByteBuf buf) {
            return new ResidentView(buf.readUUID(), buf.readUtf(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readUtf());
        }
    }

    /** 一户的住处：位置、床数、住在里面的人。 */
    public record HomeView(BlockPos pos, int beds, List<String> names) {
        void write(FriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            buf.writeVarInt(beds);
            buf.writeCollection(names, FriendlyByteBuf::writeUtf);
        }

        static HomeView read(FriendlyByteBuf buf) {
            return new HomeView(buf.readBlockPos(), buf.readVarInt(), buf.readList(FriendlyByteBuf::readUtf));
        }
    }

    public record ZoneView(UUID id, int type, int industry, ZoneRect rect, int workerTarget, int chests, int registers,
                           List<String> workers, List<HomeView> homes) {
        void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeVarInt(type);
            buf.writeVarInt(industry);
            buf.writeInt(rect.minX());
            buf.writeInt(rect.minZ());
            buf.writeInt(rect.maxX());
            buf.writeInt(rect.maxZ());
            buf.writeVarInt(workerTarget);
            buf.writeVarInt(chests);
            buf.writeVarInt(registers);
            buf.writeCollection(workers, FriendlyByteBuf::writeUtf);
            buf.writeCollection(homes, (b, home) -> home.write(b));
        }

        static ZoneView read(FriendlyByteBuf buf) {
            return new ZoneView(buf.readUUID(), buf.readVarInt(), buf.readVarInt(),
                    new ZoneRect(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readList(FriendlyByteBuf::readUtf), buf.readList(HomeView::read));
        }

        /** 住在这个居民区里的总人数。 */
        public int occupantCount() {
            int count = 0;
            for (HomeView home : homes) {
                count += home.names().size();
            }
            return count;
        }

        public int bedCount() {
            int count = 0;
            for (HomeView home : homes) {
                count += home.beds();
            }
            return count;
        }
    }

    @Nullable
    public ZoneView zoneAt(int x, int z) {
        for (ZoneView zone : zones) {
            if (zone.rect().contains(x, z)) return zone;
        }
        return null;
    }

    @Nullable
    public ZoneView zone(UUID zoneId) {
        for (ZoneView zone : zones) {
            if (zone.id().equals(zoneId)) return zone;
        }
        return null;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name);
        buf.writeUtf(mayorName);
        buf.writeBoolean(isMayor);
        buf.writeVarInt(popCap);
        buf.writeVarInt(beds);
        buf.writeVarInt(homes);
        buf.writeVarInt(freeHomes);
        buf.writeVarInt(treasury);
        buf.writeVarInt(allowance);
        buf.writeBlockPos(center);
        buf.writeUtf(schedule);
        buf.writeCollection(residents, (b, resident) -> resident.write(b));
        buf.writeCollection(zones, (b, zone) -> zone.write(b));
    }

    public static TownView read(FriendlyByteBuf buf) {
        return new TownView(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBlockPos(), buf.readUtf(), buf.readList(ResidentView::read), buf.readList(ZoneView::read));
    }
}
