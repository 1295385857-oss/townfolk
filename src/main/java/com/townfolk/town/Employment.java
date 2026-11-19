package com.townfolk.town;

import com.townfolk.entity.Profession;
import com.townfolk.entity.ResidentEntity;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * 招工。镇长只在分区上定工作人数，谁去干由这里安排：
 * 缺人时招住得离分区最近的无业成年人（没有住处的排在最后），人多了就放走住得最远的。
 * 已经有工作的人不会被别的分区抢走。职业跟着分区的产业走。
 */
public final class Employment {

    /** 重新安排一遍工作。有人上岗或离岗时返回 true。 */
    public static boolean update(Town town) {
        boolean changed = false;

        // 分区没了、产业停了、人还没成年：离岗
        for (ResidentRecord record : town.residents.values()) {
            Zone zone = record.zoneId == null ? null : town.zones.get(record.zoneId);
            boolean valid = zone != null && zone.industry.hasWorkers() && record.ageYears >= ResidentEntity.ADULT_AGE;
            if (!valid && record.zoneId != null) {
                record.zoneId = null;
                changed = true;
            }
            Profession job = valid ? zone.industry.profession : Profession.UNEMPLOYED;
            if (record.job != job) {
                record.job = job;
                changed = true;
            }
        }

        for (Zone zone : town.zones.values()) {
            if (!zone.industry.hasWorkers()) continue;
            BlockPos zoneCenter = zone.center(town.center.getY());
            List<ResidentRecord> workers = town.workers(zone.id);

            while (workers.size() > zone.workerTarget) {
                ResidentRecord farthest = null;
                double farthestScore = -1;
                for (ResidentRecord worker : workers) {
                    double score = commute(town, worker, zoneCenter);
                    if (score > farthestScore) {
                        farthest = worker;
                        farthestScore = score;
                    }
                }
                farthest.zoneId = null;
                farthest.job = Profession.UNEMPLOYED;
                workers.remove(farthest);
                changed = true;
            }

            while (workers.size() < zone.workerTarget) {
                ResidentRecord nearest = null;
                double nearestScore = Double.MAX_VALUE;
                for (ResidentRecord candidate : town.residents.values()) {
                    if (candidate.zoneId != null || candidate.ageYears < ResidentEntity.ADULT_AGE) continue;
                    double score = commute(town, candidate, zoneCenter);
                    if (score < nearestScore) {
                        nearest = candidate;
                        nearestScore = score;
                    }
                }
                if (nearest == null) break;
                nearest.zoneId = zone.id;
                nearest.job = zone.industry.profession;
                workers.add(nearest);
                changed = true;
            }
        }
        return changed;
    }

    /** 从住处到分区有多远。没有住处的人按镇公所算，并且一律排在有住处的人后面。 */
    private static double commute(Town town, ResidentRecord record, BlockPos zoneCenter) {
        @Nullable BlockPos home = record.bedPos;
        if (home != null) return horizontalDistSqr(home, zoneCenter);
        return 1.0e9 + horizontalDistSqr(town.center, zoneCenter);
    }

    private static double horizontalDistSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private Employment() {}
}
