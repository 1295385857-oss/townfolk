package com.townfolk.town;

import com.townfolk.entity.ResidentEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/**
 * 安排谁住哪一户。规则：
 * 一套房或一栋独栋只住一户；单身的人住楼房；夫妻优先搬进空着的独栋，没有独栋就合住一套床位够的楼房，
 * 都没有就先各住各的；未成年的孩子跟父母住；孩子成年后搬去楼房的空房。
 * 居民不会为了离上班近而搬家。
 */
public final class Housing {

    /** 重新安排一遍住处。有人搬了家时返回 true。 */
    public static boolean update(Town town) {
        boolean changed = dropLostBeds(town);
        List<ResidentRecord> everyone = new ArrayList<>(town.residents.values());

        for (ResidentRecord husband : everyone) {
            if (!husband.male || husband.spouse == null) continue;
            ResidentRecord wife = town.residents.get(husband.spouse);
            if (wife != null) changed |= settleCouple(town, husband, wife);
        }
        for (ResidentRecord record : everyone) {
            if (isAdult(record)) continue;
            changed |= followParents(town, record);
        }
        for (ResidentRecord record : everyone) {
            if (!isAdult(record) && livingParent(town, record) != null) continue;   // 有父母的孩子不自己找房
            changed |= settleSingle(town, record);
        }
        return changed;
    }

    private static boolean isAdult(ResidentRecord record) {
        return record.ageYears >= ResidentEntity.ADULT_AGE;
    }

    /** 床已经不属于任何一户（被拆了、分区删了、房型改了），或者和别人记了同一张床：收回。 */
    private static boolean dropLostBeds(Town town) {
        boolean changed = false;
        Set<BlockPos> taken = new HashSet<>();
        for (ResidentRecord record : town.residents.values()) {
            if (record.bedPos == null) continue;
            if (town.homeOfBed(record.bedPos) == null || !taken.add(record.bedPos)) {
                record.bedPos = null;
                changed = true;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------ 夫妻

    private static boolean settleCouple(Town town, ResidentRecord husband, ResidentRecord wife) {
        Home husbandHome = town.homeOf(husband);
        Home wifeHome = town.homeOf(wife);
        boolean together = husbandHome != null && husbandHome == wifeHome;
        if (together && husbandHome.detached) return false;

        List<ResidentRecord> family = new ArrayList<>();
        family.add(husband);
        family.add(wife);
        for (ResidentRecord record : town.residents.values()) {
            if (!isAdult(record) && isChildOf(record, husband, wife)) family.add(record);
        }

        // 先看独栋：自己已经住着的，或者空着的
        Home target = null;
        if (husbandHome != null && husbandHome.detached && fits(town, husbandHome, family)) target = husbandHome;
        else if (wifeHome != null && wifeHome.detached && fits(town, wifeHome, family)) target = wifeHome;
        else target = findEmpty(town, true, family.size());

        // 没有独栋，而且两人还没住在一起：合住一套床位够的楼房
        if (target == null && !together) {
            if (husbandHome != null && fits(town, husbandHome, family)) target = husbandHome;
            else if (wifeHome != null && fits(town, wifeHome, family)) target = wifeHome;
            else target = findEmpty(town, false, family.size());
        }
        if (target == null) return false;
        return moveInto(town, target, family);
    }

    private static boolean isChildOf(ResidentRecord child, ResidentRecord father, ResidentRecord mother) {
        return father.id.equals(child.father) || mother.id.equals(child.mother)
                || father.id.equals(child.mother) || mother.id.equals(child.father);
    }

    /** 这一户的床够这家人住，并且里面现在没有外人。 */
    private static boolean fits(Town town, Home home, List<ResidentRecord> family) {
        if (home.beds.size() < family.size()) return false;
        for (ResidentRecord occupant : town.occupants(home)) {
            if (!family.contains(occupant)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ 孩子和单身的人

    @Nullable
    private static ResidentRecord livingParent(Town town, ResidentRecord child) {
        ResidentRecord mother = child.mother == null ? null : town.residents.get(child.mother);
        if (mother != null) return mother;
        return child.father == null ? null : town.residents.get(child.father);
    }

    /** 未成年的孩子跟着父母住，优先跟母亲。父母家里没有空床时留在原处。 */
    private static boolean followParents(Town town, ResidentRecord child) {
        Home parentHome = null;
        ResidentRecord mother = child.mother == null ? null : town.residents.get(child.mother);
        ResidentRecord father = child.father == null ? null : town.residents.get(child.father);
        if (mother != null) parentHome = town.homeOf(mother);
        if (parentHome == null && father != null) parentHome = town.homeOf(father);
        if (parentHome == null || parentHome == town.homeOf(child)) return false;
        List<BlockPos> free = town.freeBeds(parentHome);
        if (free.isEmpty()) return false;
        child.bedPos = free.get(0);
        return true;
    }

    /** 没有住处的人，以及成年后还住在父母家的单身子女，搬进楼房里的空房。 */
    private static boolean settleSingle(Town town, ResidentRecord record) {
        Home home = town.homeOf(record);
        if (home != null) {
            boolean grownChildAtHome = isAdult(record) && record.spouse == null && livesWithParent(town, record, home);
            if (!grownChildAtHome) return false;
        }
        Home target = findEmpty(town, false, 1);
        if (target == null) return false;
        record.bedPos = target.beds.get(0);
        return true;
    }

    private static boolean livesWithParent(Town town, ResidentRecord record, Home home) {
        ResidentRecord father = record.father == null ? null : town.residents.get(record.father);
        ResidentRecord mother = record.mother == null ? null : town.residents.get(record.mother);
        return (father != null && town.homeOf(father) == home) || (mother != null && town.homeOf(mother) == home);
    }

    // ------------------------------------------------------------ 工具

    /** 找一户没人住、床位够的住处，离镇公所最近的优先。 */
    @Nullable
    private static Home findEmpty(Town town, boolean detached, int minBeds) {
        Set<BlockPos> taken = new HashSet<>();
        for (ResidentRecord record : town.residents.values()) {
            if (record.bedPos != null) taken.add(record.bedPos);
        }
        Home best = null;
        double bestDist = Double.MAX_VALUE;
        for (Home home : town.homes) {
            if (home.detached != detached || home.beds.size() < minBeds) continue;
            boolean empty = true;
            for (BlockPos bed : home.beds) {
                if (taken.contains(bed)) {
                    empty = false;
                    break;
                }
            }
            if (!empty) continue;
            double dist = home.pos().distSqr(town.center);
            if (dist < bestDist) {
                best = home;
                bestDist = dist;
            }
        }
        return best;
    }

    /** 让这家人都住进 target。已经住在里面的人不动。 */
    private static boolean moveInto(Town town, Home target, List<ResidentRecord> family) {
        List<ResidentRecord> movers = new ArrayList<>();
        for (ResidentRecord member : family) {
            if (town.homeOf(member) != target) movers.add(member);
        }
        if (movers.isEmpty()) return false;
        for (ResidentRecord mover : movers) {
            mover.bedPos = null;
        }
        List<BlockPos> free = town.freeBeds(target);
        for (int i = 0; i < movers.size() && i < free.size(); i++) {
            movers.get(i).bedPos = free.get(i);
        }
        return true;
    }

    private Housing() {}
}
