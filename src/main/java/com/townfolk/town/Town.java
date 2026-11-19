package com.townfolk.town;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

public class Town {
    /** 小镇范围：以镇公所为中心、边长 2×RADIUS 的正方形。分区只能划在这个范围里。 */
    public static final int RADIUS = 64;
    /** 没有住处的居民在镇公所周围活动的范围。 */
    public static final int WANDER_RADIUS = 32;
    /** 有住处的居民空闲时在家附近活动的范围。 */
    public static final int HOME_RADIUS = 14;
    public static final int MIN_POP_CAP = 2;
    public static final int MAX_POP_CAP = 200;
    public static final int MAX_NAME_LENGTH = 16;

    public final UUID id;
    public String name;
    public UUID mayorId;
    public String mayorName;
    public BlockPos center;
    public int popCap = 12;
    /** 镇长上一次给居民区选的房型，新画的居民区默认沿用。 */
    public Industry lastHousing = Industry.NONE;
    /** 小镇金库里的金币。建镇时先有一笔，之后靠镇长存入。 */
    public int treasury = 100;
    /** 安家费政策：每个出生或新来的居民从金库领多少金币。0 表示不发。 */
    public int allowance = 10;
    /** 上一次结算工资和调价是在第几个钟点（世界时间 ÷ 1000），-1 表示还没结算过。 */
    public long lastHourIndex = -1L;
    /** 全体居民共用的时间表：下标是钟点（0 到 23 点），值是 Activity 的序号。 */
    public final byte[] schedule = defaultSchedule();

    public final Map<UUID, ResidentRecord> residents = new LinkedHashMap<>();
    public final Map<UUID, Zone> zones = new LinkedHashMap<>();
    /** 认出来的所有住户的住处，由 TownTicker 定期更新。 */
    public final List<Home> homes = new ArrayList<>();
    /** 在这个镇生活过的所有人的名字，居民去世后仍然查得到其父母的姓名。 */
    public final Map<UUID, String> knownNames = new HashMap<>();

    public Town(UUID id, String name, UUID mayorId, String mayorName, BlockPos center) {
        this.id = id;
        this.name = name;
        this.mayorId = mayorId;
        this.mayorName = mayorName;
        this.center = center;
    }

    public double horizontalDistSqr(BlockPos pos) {
        double dx = pos.getX() - center.getX();
        double dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz;
    }

    public String nameOf(@Nullable UUID residentId) {
        if (residentId == null) return "";
        ResidentRecord record = residents.get(residentId);
        if (record != null) return record.name;
        return knownNames.getOrDefault(residentId, "");
    }

    // ------------------------------------------------------------ 时间表

    /** 默认的一天：6 点吃早饭，上午干活，12 点吃午饭，13 点休息，下午干活，傍晚自由活动，19 点吃晚饭，20 点睡觉。 */
    public static byte[] defaultSchedule() {
        byte[] hours = new byte[24];
        for (int hour = 0; hour < 24; hour++) {
            Activity activity;
            if (hour < 6 || hour >= 20) activity = Activity.SLEEP;
            else if (hour == 6 || hour == 12 || hour == 19) activity = Activity.EAT;
            else if (hour == 13) activity = Activity.REST;
            else if (hour >= 17) activity = Activity.FREE;
            else activity = Activity.WORK;
            hours[hour] = (byte) activity.ordinal();
        }
        return hours;
    }

    /** 世界时间对应的钟点。世界时间的 0 是早上 6 点。 */
    public static int hourOf(long dayTime) {
        return (int) Math.floorMod(dayTime / 1000L + 6L, 24L);
    }

    public Activity activityAt(int hour) {
        return Activity.byOrdinal(schedule[Math.floorMod(hour, 24)]);
    }

    public void setActivity(int hour, Activity activity) {
        schedule[Math.floorMod(hour, 24)] = (byte) activity.ordinal();
    }

    public void resetSchedule() {
        System.arraycopy(defaultSchedule(), 0, schedule, 0, 24);
    }

    // ------------------------------------------------------------ 分区

    public List<ZoneRect> zoneRects() {
        List<ZoneRect> rects = new ArrayList<>();
        for (Zone zone : zones.values()) {
            rects.add(zone.rect);
        }
        return rects;
    }

    @Nullable
    public Zone zoneAt(int x, int z) {
        for (Zone zone : zones.values()) {
            if (zone.rect.contains(x, z)) return zone;
        }
        return null;
    }

    /** 在这个分区工作的居民。 */
    public List<ResidentRecord> workers(UUID zoneId) {
        List<ResidentRecord> list = new ArrayList<>();
        for (ResidentRecord record : residents.values()) {
            if (zoneId.equals(record.zoneId)) list.add(record);
        }
        return list;
    }

    // ------------------------------------------------------------ 住户

    /** 这张床属于哪一户。 */
    @Nullable
    public Home homeOfBed(@Nullable BlockPos bed) {
        if (bed == null) return null;
        for (Home home : homes) {
            if (home.beds.contains(bed)) return home;
        }
        return null;
    }

    @Nullable
    public Home homeOf(ResidentRecord record) {
        return homeOfBed(record.bedPos);
    }

    /** 住在这一户里的居民。 */
    public List<ResidentRecord> occupants(Home home) {
        List<ResidentRecord> list = new ArrayList<>();
        for (ResidentRecord record : residents.values()) {
            if (record.bedPos != null && home.beds.contains(record.bedPos)) list.add(record);
        }
        return list;
    }

    /** 这一户里还没人睡的床。 */
    public List<BlockPos> freeBeds(Home home) {
        Set<BlockPos> taken = new HashSet<>();
        for (ResidentRecord record : residents.values()) {
            if (record.bedPos != null) taken.add(record.bedPos);
        }
        List<BlockPos> free = new ArrayList<>();
        for (BlockPos bed : home.beds) {
            if (!taken.contains(bed)) free.add(bed);
        }
        return free;
    }

    public int bedCount() {
        int count = 0;
        for (Home home : homes) {
            count += home.beds.size();
        }
        return count;
    }

    // ------------------------------------------------------------ 存档

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putUUID("Mayor", mayorId);
        tag.putString("MayorName", mayorName);
        tag.put("Center", NbtUtils.writeBlockPos(center));
        tag.putInt("PopCap", popCap);
        tag.putInt("LastHousing", lastHousing.ordinal());
        tag.putByteArray("Schedule", schedule);
        tag.putInt("Treasury", treasury);
        tag.putInt("Allowance", allowance);
        tag.putLong("LastHour", lastHourIndex);

        ListTag residentList = new ListTag();
        for (ResidentRecord record : residents.values()) {
            residentList.add(record.save());
        }
        tag.put("Residents", residentList);

        ListTag zoneList = new ListTag();
        for (Zone zone : zones.values()) {
            zoneList.add(zone.save());
        }
        tag.put("Zones", zoneList);

        ListTag homeList = new ListTag();
        for (Home home : homes) {
            homeList.add(home.save());
        }
        tag.put("Homes", homeList);

        ListTag nameList = new ListTag();
        for (Map.Entry<UUID, String> entry : knownNames.entrySet()) {
            CompoundTag nameTag = new CompoundTag();
            nameTag.putUUID("Id", entry.getKey());
            nameTag.putString("Name", entry.getValue());
            nameList.add(nameTag);
        }
        tag.put("KnownNames", nameList);
        return tag;
    }

    public static Town load(CompoundTag tag) {
        BlockPos center = NbtUtils.readBlockPos(tag, "Center").orElse(BlockPos.ZERO);
        Town town = new Town(tag.getUUID("Id"), tag.getString("Name"), tag.getUUID("Mayor"), tag.getString("MayorName"), center);
        town.popCap = tag.getInt("PopCap");
        Industry lastHousing = Industry.byOrdinal(tag.getInt("LastHousing"));
        town.lastHousing = lastHousing.isHousing() ? lastHousing : Industry.NONE;
        if (tag.contains("Treasury")) town.treasury = tag.getInt("Treasury");
        if (tag.contains("Allowance")) town.allowance = tag.getInt("Allowance");
        if (tag.contains("LastHour")) town.lastHourIndex = tag.getLong("LastHour");
        byte[] schedule = tag.getByteArray("Schedule");
        if (schedule.length == 24) {
            for (int hour = 0; hour < 24; hour++) {
                town.schedule[hour] = (byte) Activity.byOrdinal(schedule[hour]).ordinal();
            }
        }

        ListTag residentList = tag.getList("Residents", Tag.TAG_COMPOUND);
        for (int i = 0; i < residentList.size(); i++) {
            ResidentRecord record = ResidentRecord.load(residentList.getCompound(i));
            town.residents.put(record.id, record);
        }
        ListTag zoneList = tag.getList("Zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < zoneList.size(); i++) {
            Zone zone = Zone.load(zoneList.getCompound(i));
            town.zones.put(zone.id, zone);
        }
        ListTag homeList = tag.getList("Homes", Tag.TAG_COMPOUND);
        for (int i = 0; i < homeList.size(); i++) {
            Home home = Home.load(homeList.getCompound(i));
            if (!home.beds.isEmpty() && town.zones.containsKey(home.zoneId)) town.homes.add(home);
        }
        ListTag nameList = tag.getList("KnownNames", Tag.TAG_COMPOUND);
        for (int i = 0; i < nameList.size(); i++) {
            CompoundTag nameTag = nameList.getCompound(i);
            town.knownNames.put(nameTag.getUUID("Id"), nameTag.getString("Name"));
        }
        return town;
    }
}
