package com.townfolk.test;

import com.townfolk.Townfolk;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.town.Demographics;
import com.townfolk.town.Home;
import com.townfolk.town.Industry;
import com.townfolk.town.ResidentRecord;
import com.townfolk.town.Town;
import com.townfolk.town.TownManager;
import com.townfolk.town.TownTicker;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import com.townfolk.town.ZoneType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 房型、住户、结婚、搬家、生孩子、老死的游戏内自动测试。 */
@GameTestHolder(Townfolk.MODID)
@PrefixGameTestTemplate(false)
public class FamilyGameTests {
    private static final int GROUND = TestSupport.GROUND;

    @GameTest(template = "empty_big", batch = "family", timeoutTicks = 400, skyAccess = true)
    public static void familyLife(GameTestHelper helper) {
        Demographics.autoEvents = false;
        ServerLevel level = helper.getLevel();
        TestSupport.layGround(helper, 32);
        level.setDayTime(6000L);

        // ---- 楼房分区 (1,1)-(20,10)
        // 甲楼：两间房，中间的墙上装了门。左间 2 张床，右间 1 张床 → 应该认成两户
        TestSupport.buildShell(helper, 2, 2, 8, 6);
        TestSupport.buildWallZ(helper, 5, 2, 6);
        TestSupport.openDoorway(helper, 5, 4, true);
        TestSupport.openDoorway(helper, 3, 6, true);            // 左间通向屋外的门
        TestSupport.placeBed(helper, new BlockPos(3, GROUND + 1, 3));
        TestSupport.placeBed(helper, new BlockPos(4, GROUND + 1, 3));
        TestSupport.placeBed(helper, new BlockPos(7, GROUND + 1, 3));
        // 乙楼：两间房，中间只是门洞没有装门。共 3 张床 → 应该认成一户
        TestSupport.buildShell(helper, 11, 2, 17, 6);
        TestSupport.buildWallZ(helper, 14, 2, 6);
        TestSupport.openDoorway(helper, 14, 4, false);
        TestSupport.openDoorway(helper, 12, 6, true);
        TestSupport.placeBed(helper, new BlockPos(12, GROUND + 1, 3));
        TestSupport.placeBed(helper, new BlockPos(13, GROUND + 1, 3));
        TestSupport.placeBed(helper, new BlockPos(16, GROUND + 1, 3));
        // 四张露天的床，每张各算一户
        for (int i = 0; i < 4; i++) {
            TestSupport.placeBed(helper, new BlockPos(3 + i * 2, GROUND + 1, 8));
        }

        // ---- 独栋分区 (24,1)-(30,7)：只是一块地上摆了 4 张床，没有墙也算一栋
        List<BlockPos> houseBeds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            houseBeds.add(TestSupport.placeBed(helper, new BlockPos(25 + i, GROUND + 1, 3)));
        }

        Town town = TestSupport.foundTown(helper, new BlockPos(16, GROUND + 1, 20));
        ZoneRect flatsRect = TestSupport.rect(helper, 1, 1, 20, 10);
        ZoneRect houseRect = TestSupport.rect(helper, 24, 1, 30, 7);
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.RESIDENTIAL, flatsRect) == null, "楼房分区应该能建");
        Zone flats = town.zoneAt(flatsRect.minX(), flatsRect.minZ());
        TownManager.setIndustry(level, town, flats.id, Industry.APARTMENT);
        TownManager.setIndustry(level, town, flats.id, Industry.DETACHED);
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.RESIDENTIAL, houseRect) == null, "独栋分区应该能建");
        Zone house = town.zoneAt(houseRect.minX(), houseRect.minZ());
        helper.assertTrue(house.industry == Industry.DETACHED, "新画的居民区应该沿用上一次选的房型");
        TownManager.setIndustry(level, town, flats.id, Industry.APARTMENT);

        // ---- 认住户
        TownTicker.tickTown(level, town);
        List<Integer> flatSizes = new ArrayList<>();
        Home detached = null;
        for (Home home : town.homes) {
            if (home.detached) detached = home;
            else flatSizes.add(home.beds.size());
        }
        Collections.sort(flatSizes);
        helper.assertTrue(flatSizes.equals(List.of(1, 1, 1, 1, 1, 2, 3)), "楼房应该按门认成 7 户，床数 1,1,1,1,1,2,3，实际 " + flatSizes);
        helper.assertTrue(detached != null && detached.beds.size() == 4 && new HashSet<>(detached.beds).equals(new HashSet<>(houseBeds)),
                "独栋分区里的 4 张床应该属于同一户");
        Home theHouse = detached;

        // ---- 单身的人住楼房，一人一套
        Set<Home> used = new HashSet<>();
        for (ResidentRecord record : town.residents.values()) {
            Home home = town.homeOf(record);
            helper.assertTrue(home != null && !home.detached, "单身的人应该住进楼房：" + record.name);
            helper.assertTrue(used.add(home), "一套房只住一户，不应该有两个单身的人合住");
        }

        // ---- 结婚：搬进独栋
        ResidentRecord man = null;
        ResidentRecord woman = null;
        ResidentRecord otherMan = null;
        for (ResidentRecord record : town.residents.values()) {
            if (record.male && man == null) man = record;
            else if (record.male && otherMan == null) otherMan = record;
            else if (!record.male && woman == null) woman = record;
        }
        man.ageYears = 28;
        woman.ageYears = 26;
        ((ResidentEntity) level.getEntity(man.id)).setAgeYears(28);
        ((ResidentEntity) level.getEntity(woman.id)).setAgeYears(26);
        helper.assertTrue(Demographics.canMarry(man, woman), "没有亲属关系的成年单身男女应该可以结婚");
        helper.assertTrue(!Demographics.canMarry(man, otherMan), "两个男人不在自动婚配的范围里");
        helper.assertTrue(Demographics.bedForNewChild(town, woman) == null, "没结婚不能生孩子");
        Demographics.marry(level, town, man, woman);
        helper.assertTrue(!Demographics.canMarry(otherMan, woman), "已婚的人不能再结婚");
        TownTicker.tickTown(level, town);
        helper.assertTrue(town.homeOf(man) == town.homeOfBed(houseBeds.get(0)) && town.homeOf(woman) == town.homeOf(man),
                "夫妻应该一起搬进空着的独栋");

        // ---- 生孩子
        helper.assertTrue(Demographics.bedForNewChild(town, woman) != null, "住在一起、家里有空床的夫妻应该可以生孩子");
        town.popCap = town.residents.size();
        helper.assertTrue(Demographics.bedForNewChild(town, woman) == null, "到了人口上限不能生");
        town.popCap = 12;
        woman.ageYears = Demographics.MAX_PARENT_AGE + 1;
        helper.assertTrue(Demographics.bedForNewChild(town, woman) == null, "过了生育年龄不能生");
        woman.ageYears = 26;

        ResidentEntity baby = Demographics.birth(level, town, woman);
        helper.assertTrue(baby != null, "应该生下一个孩子");
        ResidentRecord child = town.residents.get(baby.getUUID());
        helper.assertTrue(town.residents.size() == 7 && child != null, "孩子应该记进名册");
        helper.assertTrue(baby.getAgeYears() == 0 && !baby.isAdult(), "新生儿应该是 0 岁");
        helper.assertTrue(baby.getFullName().startsWith(man.name.substring(0, 1)), "孩子应该跟父亲姓");
        helper.assertTrue(man.id.equals(child.father) && woman.id.equals(child.mother), "孩子应该记得父母是谁");
        helper.assertTrue(town.homeOf(child) == town.homeOf(woman), "孩子应该住在父母家");
        helper.assertTrue(Demographics.isCloseKin(child, woman) && Demographics.isCloseKin(man, child), "父母和子女是近亲");
        helper.assertTrue(!Demographics.isCloseKin(man, otherMan), "开局居民之间没有亲属关系");
        helper.assertTrue(Demographics.bedForNewChild(town, woman) != null, "独栋里还有空床，可以再生");
        ResidentEntity second = Demographics.birth(level, town, woman);
        helper.assertTrue(second != null && Demographics.isCloseKin(child, town.residents.get(second.getUUID())), "亲兄弟姐妹是近亲");
        helper.assertTrue(Demographics.bedForNewChild(town, woman) == null, "独栋的 4 张床住满了，不能再生");

        // ---- 孩子长大：搬去楼房的空房，也可以被招工
        baby.setAgeYears(ResidentEntity.ADULT_AGE);
        baby.syncWithTown(level);
        TownTicker.tickTown(level, town);
        Home childHome = town.homeOf(child);
        helper.assertTrue(childHome != null && !childHome.detached, "孩子成年后应该搬去楼房的空房");
        helper.assertTrue(town.homeOf(town.residents.get(second.getUUID())) == theHouseNow(town, houseBeds), "未成年的孩子留在父母家");

        // ---- 老死
        ResidentEntity husband = (ResidentEntity) level.getEntity(man.id);
        ResidentRecord wife = woman;
        ResidentRecord secondChild = town.residents.get(second.getUUID());
        husband.setAgeYears(husband.getLifespanYears());
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(!husband.isAlive(), "到了寿命的居民应该去世");
            helper.assertTrue(!town.residents.containsKey(husband.getUUID()), "去世的居民应该从名册里除名");
            helper.assertTrue(wife.spouse == null, "丧偶的人变回单身");
            TownTicker.tickTown(level, town);
            Home widowHome = town.homeOf(wife);
            helper.assertTrue(widowHome != null && widowHome.detached && town.homeOf(secondChild) == widowHome, "丧偶的人和孩子继续住在独栋");

            level.removeBlock(town.center, false);
            for (ResidentEntity resident : level.getEntitiesOfClass(ResidentEntity.class, new net.minecraft.world.phys.AABB(town.center).inflate(64))) {
                resident.discard();
            }
            helper.succeed();
        });
    }

    private static Home theHouseNow(Town town, List<BlockPos> houseBeds) {
        return town.homeOfBed(houseBeds.get(0));
    }
}
