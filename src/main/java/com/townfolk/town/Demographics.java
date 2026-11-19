package com.townfolk.town;

import com.townfolk.entity.NameGenerator;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.registry.ModRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** 结婚和生孩子。 */
public final class Demographics {
    /** 自动测试里关掉，免得随机发生的婚事和出生打乱测试。 */
    public static boolean autoEvents = true;

    /** 生育年龄的上限，夫妻双方都要在成年到这个岁数之间。 */
    public static final int MAX_PARENT_AGE = 45;
    /** 夫妻年龄最多相差几岁。 */
    private static final int MAX_AGE_GAP = 15;
    /** 每次检查时，一对合适的单身男女结婚的概率。平均约两分钟结成。 */
    private static final double MARRIAGE_CHANCE = 0.05;
    /** 住独栋的夫妻平均多久生一个孩子（tick）：3 个游戏日。 */
    private static final double DETACHED_BIRTH_TICKS = 72000.0;
    /** 住楼房的夫妻平均多久生一个孩子（tick）：6 个游戏日。 */
    private static final double APARTMENT_BIRTH_TICKS = 144000.0;
    /** 生完一个之后至少隔多久再生（tick）：1 个游戏日。 */
    private static final long BIRTH_COOLDOWN = 24000L;

    /** 每次小镇例行检查时调用一次。有变化时返回 true。 */
    public static boolean update(ServerLevel level, Town town) {
        boolean changed = cleanUp(town);
        if (autoEvents) {
            changed |= tryMarriages(level, town, MARRIAGE_CHANCE);
            changed |= tryBirths(level, town);
        }
        return changed;
    }

    /** 配偶已经不在名册里了：变回单身。 */
    private static boolean cleanUp(Town town) {
        boolean changed = false;
        for (ResidentRecord record : town.residents.values()) {
            if (record.spouse != null && !town.residents.containsKey(record.spouse)) {
                record.spouse = null;
                changed = true;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------ 结婚

    /** 父母和子女、亲兄弟姐妹不能结婚；堂表亲可以。 */
    public static boolean isCloseKin(ResidentRecord a, ResidentRecord b) {
        if (a.id.equals(b.father) || a.id.equals(b.mother) || b.id.equals(a.father) || b.id.equals(a.mother)) return true;
        boolean sameFather = a.father != null && a.father.equals(b.father);
        boolean sameMother = a.mother != null && a.mother.equals(b.mother);
        return sameFather || sameMother;
    }

    public static boolean canMarry(ResidentRecord man, ResidentRecord woman) {
        return man.male && !woman.male
                && man.spouse == null && woman.spouse == null
                && man.ageYears >= ResidentEntity.ADULT_AGE && woman.ageYears >= ResidentEntity.ADULT_AGE
                && Math.abs(man.ageYears - woman.ageYears) <= MAX_AGE_GAP
                && !isCloseKin(man, woman);
    }

    private static boolean tryMarriages(ServerLevel level, Town town, double chance) {
        List<ResidentRecord> men = new ArrayList<>();
        for (ResidentRecord record : town.residents.values()) {
            if (record.male && record.spouse == null && record.ageYears >= ResidentEntity.ADULT_AGE) men.add(record);
        }
        Collections.shuffle(men, new java.util.Random(level.random.nextLong()));

        boolean changed = false;
        for (ResidentRecord man : men) {
            ResidentRecord match = null;
            for (ResidentRecord woman : town.residents.values()) {
                if (!canMarry(man, woman)) continue;
                if (match == null || Math.abs(man.ageYears - woman.ageYears) < Math.abs(man.ageYears - match.ageYears)) {
                    match = woman;
                }
            }
            if (match != null && level.random.nextDouble() < chance) {
                marry(level, town, man, match);
                changed = true;
            }
        }
        return changed;
    }

    public static void marry(ServerLevel level, Town town, ResidentRecord man, ResidentRecord woman) {
        man.spouse = woman.id;
        woman.spouse = man.id;
        TownManager.notifyMayor(level, town, Component.translatable("townfolk.message.married", man.name, woman.name));
    }

    // ------------------------------------------------------------ 生孩子

    /**
     * 这位妻子现在能不能生孩子。条件：夫妻都在生育年龄、住在同一户、家里还有空床、小镇没到人口上限。
     * 可以时返回孩子要睡的那张床，否则返回 null。
     */
    @Nullable
    public static BlockPos bedForNewChild(Town town, ResidentRecord wife) {
        if (wife.male || wife.spouse == null) return null;
        ResidentRecord husband = town.residents.get(wife.spouse);
        if (husband == null) return null;
        if (!isFertile(wife) || !isFertile(husband)) return null;
        if (town.residents.size() >= town.popCap) return null;
        Home home = town.homeOf(wife);
        if (home == null || home != town.homeOf(husband)) return null;
        List<BlockPos> free = town.freeBeds(home);
        return free.isEmpty() ? null : free.get(0);
    }

    private static boolean isFertile(ResidentRecord record) {
        return record.ageYears >= ResidentEntity.ADULT_AGE && record.ageYears <= MAX_PARENT_AGE;
    }

    private static boolean tryBirths(ServerLevel level, Town town) {
        boolean changed = false;
        for (ResidentRecord wife : new ArrayList<>(town.residents.values())) {
            if (level.getGameTime() < wife.nextBirthTime || bedForNewChild(town, wife) == null) continue;
            Home home = town.homeOf(wife);
            double averageTicks = home != null && home.detached ? DETACHED_BIRTH_TICKS : APARTMENT_BIRTH_TICKS;
            if (level.random.nextDouble() < TownTicker.INTERVAL / averageTicks) {
                changed |= birth(level, town, wife) != null;
            }
        }
        return changed;
    }

    /** 让这位妻子生下一个孩子。条件不满足，或者母亲不在已加载的区块里时返回 null。 */
    @Nullable
    public static ResidentEntity birth(ServerLevel level, Town town, ResidentRecord wife) {
        BlockPos bed = bedForNewChild(town, wife);
        if (bed == null) return null;
        ResidentRecord husband = Objects.requireNonNull(town.residents.get(wife.spouse));
        if (!(level.getEntity(wife.id) instanceof ResidentEntity mother) || !mother.isAlive()) return null;
        ResidentEntity baby = ModRegistry.RESIDENT.get().create(level);
        if (baby == null) return null;

        RandomSource random = level.random;
        boolean male = random.nextBoolean();
        String surname = husband.name.isEmpty() ? "" : husband.name.substring(0, 1);     // 孩子跟父亲姓
        String fullName = surname + NameGenerator.givenName(random, male);
        baby.initResident(fullName, male, 0, random.nextInt(ResidentEntity.VARIANT_COUNT));
        baby.setParents(husband.id, wife.id);
        baby.moveTo(mother.getX(), mother.getY(), mother.getZ(), random.nextFloat() * 360.0F, 0.0F);
        level.addFreshEntity(baby);
        baby.joinTown(town);
        town.residents.get(baby.getUUID()).bedPos = bed;
        Economy.grantAllowance(level, town, town.residents.get(baby.getUUID()));
        wife.nextBirthTime = level.getGameTime() + BIRTH_COOLDOWN;

        String key = male ? "townfolk.message.born_boy" : "townfolk.message.born_girl";
        TownManager.notifyMayor(level, town, Component.translatable(key, husband.name, wife.name, fullName));
        return baby;
    }

    private Demographics() {}
}
