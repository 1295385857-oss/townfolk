package com.townfolk.town;

import com.townfolk.entity.NameGenerator;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ai.Terrain;
import com.townfolk.network.TownDataPayload;
import com.townfolk.network.TownView;
import com.townfolk.registry.ModRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/** 建镇、解散、生成居民、管理分区、给管理界面准备数据。 */
public final class TownManager {
    public static final int FOUNDER_COUNT = 6;
    /** 重新放置镇公所时，会把这个距离内没有归属的居民收编进新镇。 */
    private static final int ADOPT_RANGE = 48;

    private static final String[] TOWN_NAMES = {
            "橡木镇", "青石镇", "河湾镇", "麦田镇", "松林镇", "磨坊镇", "白桦镇", "石桥镇", "谷仓镇", "溪谷镇"
    };

    /** 在 center 建立小镇。离别的小镇太近时返回 null。 */
    @Nullable
    public static Town foundTown(ServerLevel level, BlockPos center, ServerPlayer mayor) {
        TownSavedData data = TownSavedData.get(level);
        if (data.nearestTown(center, Town.RADIUS * 2) != null) {
            return null;
        }

        String name = TOWN_NAMES[level.random.nextInt(TOWN_NAMES.length)];
        Town town = new Town(UUID.randomUUID(), name, mayor.getUUID(), mayor.getGameProfile().getName(), center);
        data.addTown(town);

        // 先收编附近没有归属的居民（比如镇公所被拆掉后重新放置），没有才生成新的开局居民
        AABB box = new AABB(center).inflate(ADOPT_RANGE, 32, ADOPT_RANGE);
        List<ResidentEntity> homeless = level.getEntitiesOfClass(ResidentEntity.class, box, r -> r.isAlive() && r.getTownId() == null);
        if (homeless.isEmpty()) {
            spawnFounders(level, town);
            mayor.sendSystemMessage(Component.translatable("townfolk.message.founded", town.name, FOUNDER_COUNT));
        } else {
            for (ResidentEntity resident : homeless) {
                resident.joinTown(town);
            }
            mayor.sendSystemMessage(Component.translatable("townfolk.message.founded_adopted", town.name, homeless.size()));
        }
        data.setDirty();
        return town;
    }

    /** 镇公所被拆除：小镇解散，居民留在原地但不再有归属。 */
    public static void dissolveTown(ServerLevel level, Town town) {
        TownSavedData data = TownSavedData.get(level);
        data.removeTown(town.id);
        for (UUID residentId : town.residents.keySet()) {
            if (level.getEntity(residentId) instanceof ResidentEntity resident) {
                resident.leaveTown();
            }
        }
        notifyMayor(level, town, Component.translatable("townfolk.message.dissolved", town.name));
    }

    /** 给镇长发一条消息。镇长不在线就不发。 */
    public static void notifyMayor(ServerLevel level, Town town, Component message) {
        ServerPlayer mayor = level.getServer().getPlayerList().getPlayer(town.mayorId);
        if (mayor != null) {
            mayor.sendSystemMessage(message);
        }
    }

    /** 开局居民：男女各半，姓氏互不相同（互相没有亲属关系），20 到 30 岁。 */
    private static void spawnFounders(ServerLevel level, Town town) {
        RandomSource random = level.random;
        List<String> surnames = NameGenerator.distinctSurnames(random, FOUNDER_COUNT);
        for (int i = 0; i < FOUNDER_COUNT; i++) {
            ResidentEntity resident = ModRegistry.RESIDENT.get().create(level);
            if (resident == null) continue;
            boolean male = i % 2 == 0;
            String fullName = surnames.get(i % surnames.size()) + NameGenerator.givenName(random, male);
            int age = 20 + random.nextInt(11);
            resident.initResident(fullName, male, age, random.nextInt(ResidentEntity.VARIANT_COUNT));

            BlockPos pos = findSpawnPos(level, town.center, random);
            resident.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
            level.addFreshEntity(resident);
            resident.joinTown(town);
            Economy.grantAllowance(level, town, town.residents.get(resident.getUUID()));
        }
    }

    /** 在镇公所周围找一个能站人的位置。 */
    private static BlockPos findSpawnPos(ServerLevel level, BlockPos center, RandomSource random) {
        for (int attempt = 0; attempt < 40; attempt++) {
            int dx = random.nextInt(9) - 4;
            int dz = random.nextInt(9) - 4;
            if (dx == 0 && dz == 0) continue;
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos pos = center.offset(dx, dy, dz);
                if (canStandAt(level, pos)) return pos;
            }
        }
        return center.above();
    }

    private static boolean canStandAt(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        return isPassable(level, pos) && isPassable(level, pos.above());
    }

    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------ 分区

    /** 建立分区。成功返回 null，否则返回失败原因的翻译键。 */
    @Nullable
    public static String createZone(ServerLevel level, Town town, ZoneType type, ZoneRect rect) {
        String problem = rect.problem(town.center.getX(), town.center.getZ(), town.zoneRects());
        if (problem != null) return problem;
        Zone zone = new Zone(UUID.randomUUID(), type, rect);
        if (type == ZoneType.RESIDENTIAL) {
            zone.industry = town.lastHousing;       // 新画的居民区沿用上一次选的房型
        }
        town.zones.put(zone.id, zone);
        TownSavedData.get(level).setDirty();
        return null;
    }

    public static void deleteZone(ServerLevel level, Town town, UUID zoneId) {
        if (town.zones.remove(zoneId) != null) {
            town.homes.removeIf(home -> home.zoneId.equals(zoneId));
            TownSavedData.get(level).setDirty();
        }
    }

    /** 给分区选产业或房型。选项必须属于这类分区；选 NONE 表示停掉。 */
    public static void setIndustry(ServerLevel level, Town town, UUID zoneId, Industry industry) {
        Zone zone = town.zones.get(zoneId);
        if (zone == null || zone.industry == industry) return;
        if (industry != Industry.NONE && industry.zoneType != zone.type) return;

        boolean hadWorkers = zone.industry.hasWorkers();
        zone.industry = industry;
        if (!industry.hasWorkers()) {
            zone.workerTarget = 0;
        } else if (!hadWorkers) {
            zone.workerTarget = 1;                  // 刚开张的产业默认招一个人
        }
        if (zone.type == ZoneType.RESIDENTIAL) {
            if (industry.isHousing()) town.lastHousing = industry;
            town.homes.removeIf(home -> home.zoneId.equals(zoneId));    // 房型变了，住户要重新认
        }
        TownSavedData.get(level).setDirty();
    }

    /** 镇长给分区定工作人数。 */
    public static void setWorkers(ServerLevel level, Town town, UUID zoneId, int count) {
        Zone zone = town.zones.get(zoneId);
        if (zone == null || !zone.industry.hasWorkers()) return;
        zone.workerTarget = Mth.clamp(count, 0, Zone.MAX_WORKERS);
        TownSavedData.get(level).setDirty();
    }

    // ------------------------------------------------------------ 界面数据

    public static TownView buildView(ServerLevel level, Town town, ServerPlayer viewer) {
        List<TownView.ResidentView> residents = new ArrayList<>();
        for (ResidentRecord record : town.residents.values()) {
            Home home = town.homeOf(record);
            int homeType = home == null ? TownView.HOME_NONE : home.detached ? TownView.HOME_DETACHED : TownView.HOME_APARTMENT;
            residents.add(new TownView.ResidentView(
                    record.id, record.name, record.male, record.ageYears, record.job.ordinal(), record.status.ordinal(), homeType,
                    record.money, record.satiety,
                    town.nameOf(record.spouse), town.nameOf(record.father), town.nameOf(record.mother)));
        }

        int freeHomes = 0;
        List<TownView.ZoneView> zones = new ArrayList<>();
        for (Zone zone : town.zones.values()) {
            List<String> workerNames = new ArrayList<>();
            for (ResidentRecord worker : town.workers(zone.id)) {
                workerNames.add(worker.name);
            }
            List<TownView.HomeView> homes = new ArrayList<>();
            for (Home home : town.homes) {
                if (!home.zoneId.equals(zone.id)) continue;
                List<String> names = new ArrayList<>();
                for (ResidentRecord occupant : town.occupants(home)) {
                    names.add(occupant.name);
                }
                if (names.isEmpty()) freeHomes++;
                homes.add(new TownView.HomeView(home.pos(), home.beds.size(), names));
            }
            // 农场和树场数箱子，商业区数货架和收银台
            boolean commercial = zone.type == ZoneType.COMMERCIAL;
            int chests = commercial ? Terrain.findShelves(level, zone.rect).size()
                    : zone.industry.hasWorkers() ? Terrain.findStorage(level, zone.rect).size() : 0;
            int registers = commercial ? Terrain.findRegisters(level, zone.rect).size() : 0;
            zones.add(new TownView.ZoneView(zone.id, zone.type.ordinal(), zone.industry.ordinal(), zone.rect,
                    zone.workerTarget, chests, registers, workerNames, homes));
        }
        return new TownView(town.id, town.name, town.mayorName, town.mayorId.equals(viewer.getUUID()),
                town.popCap, town.bedCount(), town.homes.size(), freeHomes, town.treasury, town.allowance, town.center, Activity.encode(town.schedule), residents, zones);
    }

    public static void sendView(ServerLevel level, Town town, ServerPlayer player, boolean openScreen) {
        PacketDistributor.sendToPlayer(player, new TownDataPayload(buildView(level, town, player), openScreen));
    }

    private TownManager() {}
}
