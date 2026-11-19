package com.townfolk.town;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** 小镇的例行检查：每隔几秒重新认一遍住户，再安排婚事和出生、住处、工作。 */
public final class TownTicker {
    /** 每个小镇多久检查一次（tick）。 */
    public static final int INTERVAL = 100;
    private static final int STEP = 20;

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long time = level.getGameTime();
        if (time % STEP != 0) return;
        TownSavedData data = TownSavedData.get(level);
        int index = 0;
        for (Town town : data.towns()) {
            // 把各个小镇错开，不在同一刻检查
            boolean due = (time / STEP + index++) % (INTERVAL / STEP) == 0;
            if (due && level.isLoaded(town.center)) {
                tickTown(level, town);
            }
        }
    }

    /** 立刻给这个小镇做一次例行检查。 */
    public static void tickTown(ServerLevel level, Town town) {
        boolean changed = refreshHomes(level, town);
        changed |= Demographics.update(level, town);
        changed |= Housing.update(town);
        changed |= Employment.update(town);
        changed |= Economy.update(level, town);
        if (changed) {
            TownSavedData.get(level).setDirty();
        }
    }

    /** 重新认一遍各个居民区里的住户。区块没加载的居民区保持上一次的结果。 */
    private static boolean refreshHomes(ServerLevel level, Town town) {
        List<Home> result = new ArrayList<>();
        for (Zone zone : town.zones.values()) {
            if (zone.type != ZoneType.RESIDENTIAL || !zone.industry.isHousing()) continue;
            boolean detached = zone.industry == Industry.DETACHED;
            List<Home> scanned = HomeScanner.scan(level, zone);
            if (scanned != null) {
                result.addAll(scanned);
                continue;
            }
            for (Home home : town.homes) {
                if (home.zoneId.equals(zone.id) && home.detached == detached) result.add(home);
            }
        }
        if (result.equals(town.homes)) return false;
        town.homes.clear();
        town.homes.addAll(result);
        return true;
    }

    private TownTicker() {}
}
