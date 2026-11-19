package com.townfolk.test;

import com.townfolk.Townfolk;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.town.Activity;
import com.townfolk.town.Demographics;
import com.townfolk.town.Industry;
import com.townfolk.town.ResidentRecord;
import com.townfolk.town.Town;
import com.townfolk.town.TownManager;
import com.townfolk.town.TownTicker;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import com.townfolk.town.ZoneType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 分区、农夫、伐木工和作息的游戏内自动测试。 */
@GameTestHolder(Townfolk.MODID)
@PrefixGameTestTemplate(false)
public class WorkGameTests {
    private static final int GROUND = TestSupport.GROUND;

    /** 水坑规划：任意长度的边上，每一格离最近的水坑都不超过 4 格。 */
    @GameTest(template = "empty", batch = "rules")
    public static void waterPitsCoverEveryCell(GameTestHelper helper) {
        for (int length = 1; length <= ZoneRect.MAX_SIZE; length++) {
            int[] pits = ZoneRect.pitOffsets(length);
            for (int cell = 0; cell < length; cell++) {
                int nearest = Integer.MAX_VALUE;
                for (int pit : pits) {
                    helper.assertTrue(pit >= 0 && pit < length, "水坑必须在边的范围内");
                    nearest = Math.min(nearest, Math.abs(pit - cell));
                }
                helper.assertTrue(nearest <= 4, "边长 " + length + " 时第 " + cell + " 格离水坑有 " + nearest + " 格");
            }
        }
        helper.succeed();
    }

    /** 时间表：默认的一天、世界时间和钟点的换算、修改、存读档。 */
    @GameTest(template = "empty", batch = "rules")
    public static void scheduleRules(GameTestHelper helper) {
        Town town = new Town(UUID.randomUUID(), "测试镇", UUID.randomUUID(), "镇长", BlockPos.ZERO);
        helper.assertTrue(Town.hourOf(0L) == 6 && Town.hourOf(6000L) == 12 && Town.hourOf(18000L) == 0 && Town.hourOf(23999L) == 5
                && Town.hourOf(24000L + 1000L) == 7, "世界时间 0 应该是早上 6 点");
        int[] hours = {3, 6, 7, 11, 12, 13, 14, 16, 17, 18, 19, 20, 23};
        Activity[] expected = {Activity.SLEEP, Activity.EAT, Activity.WORK, Activity.WORK, Activity.EAT, Activity.REST, Activity.WORK,
                Activity.WORK, Activity.FREE, Activity.FREE, Activity.EAT, Activity.SLEEP, Activity.SLEEP};
        int work = 0;
        for (int hour = 0; hour < 24; hour++) {
            if (town.activityAt(hour) == Activity.WORK) work++;
        }
        helper.assertTrue(work == 8, "默认时间表应该是 8 小时工作，实际 " + work);
        for (int i = 0; i < hours.length; i++) {
            helper.assertTrue(town.activityAt(hours[i]) == expected[i], "默认时间表 " + hours[i] + " 点应该是 " + expected[i] + "，实际 " + town.activityAt(hours[i]));
        }
        town.setActivity(17, Activity.WORK);
        town.setActivity(3, Activity.FREE);
        Town reloaded = Town.load(town.save());
        helper.assertTrue(reloaded.activityAt(17) == Activity.WORK && reloaded.activityAt(3) == Activity.FREE
                && reloaded.activityAt(12) == Activity.EAT, "改过的时间表应该能存下来");
        helper.assertTrue(Activity.at(Activity.encode(town.schedule), 17) == Activity.WORK, "时间表发给界面后应该读得回来");
        town.resetSchedule();
        helper.assertTrue(town.activityAt(17) == Activity.FREE && town.activityAt(3) == Activity.SLEEP, "恢复默认应该生效");
        helper.succeed();
    }

    /** 货架能装货、同种货合并着摆、存读档不丢；商业区能选商店和餐厅。 */
    @GameTest(template = "empty", batch = "rules")
    public static void shelvesAndCommerce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(4, 2, 4);
        ((com.townfolk.block.TallShelfBlock) com.townfolk.registry.ModRegistry.SHELF.get()).placeWhole(level, helper.absolutePos(rel), Direction.SOUTH);
        helper.assertTrue(level.getBlockState(helper.absolutePos(rel.above())).is(com.townfolk.registry.ModRegistry.SHELF.get())
                && level.getBlockEntity(helper.absolutePos(rel.above())) == null, "货架有两格高，货只记在下半格里");
        helper.setBlock(rel.east(), com.townfolk.registry.ModRegistry.COUNTER.get());
        helper.setBlock(rel.west(), com.townfolk.registry.ModRegistry.MARKET_STALL.get());
        helper.assertTrue(helper.getBlockEntity(rel) instanceof com.townfolk.block.ShelfBlockEntity, "货架应该带着装货的格子");
        com.townfolk.block.ShelfBlockEntity shelf = (com.townfolk.block.ShelfBlockEntity) helper.getBlockEntity(rel);
        shelf.setItem(0, new ItemStack(Items.BREAD, 20));
        shelf.setItem(1, new ItemStack(Items.WHEAT, 5));
        shelf.setItem(4, new ItemStack(Items.BREAD, 10));
        shelf.setItem(8, new ItemStack(Items.POTATO, 1));
        List<ItemStack> shown = shelf.displayStacks();
        helper.assertTrue(shown.size() == 3, "三种货应该摆出三样，实际 " + shown.size());
        // 两格高的货架最多摆 8 种，柜台最多 4 种
        Item[] kinds = {Items.APPLE, Items.CARROT, Items.BEETROOT, Items.OAK_LOG, Items.STICK, Items.BAKED_POTATO, Items.COBBLESTONE};
        com.townfolk.block.ShelfBlockEntity counter = (com.townfolk.block.ShelfBlockEntity) helper.getBlockEntity(rel.east());
        for (int i = 0; i < kinds.length; i++) {
            counter.setItem(i, new ItemStack(kinds[i]));
        }
        helper.assertTrue(counter.displayStacks().size() == 4, "柜台最多摆出 4 种货");
        com.townfolk.block.ShelfBlockEntity full = new com.townfolk.block.ShelfBlockEntity(shelf.getBlockPos(), shelf.getBlockState());
        for (int i = 0; i < 9; i++) {
            full.setItem(i, new ItemStack(i < kinds.length ? kinds[i] : (i == 7 ? Items.BREAD : Items.WHEAT)));
        }
        helper.assertTrue(full.displayStacks().size() == 8, "两格高的货架最多摆出 8 种货，实际 " + full.displayStacks().size());
        helper.assertTrue(shown.get(0).is(Items.BREAD) && shown.get(0).getCount() == 30, "同一种货应该合并成一份，面包共 30 个");
        helper.assertTrue(shelf.getItem(0).getCount() == 20, "摆出来的只是给人看的，不能改动货架里真正的货");

        net.minecraft.nbt.CompoundTag tag = shelf.saveWithoutMetadata(level.registryAccess());
        com.townfolk.block.ShelfBlockEntity copy = new com.townfolk.block.ShelfBlockEntity(shelf.getBlockPos(), shelf.getBlockState());
        copy.loadWithComponents(tag, level.registryAccess());
        helper.assertTrue(copy.getItem(4).is(Items.BREAD) && copy.getItem(4).getCount() == 10 && copy.getItem(8).is(Items.POTATO), "货架存读档后货应该还在");
        shelf.setItem(8, ItemStack.EMPTY);
        copy.loadWithComponents(shelf.getUpdateTag(level.registryAccess()), level.registryAccess());
        helper.assertTrue(copy.getItem(8).isEmpty(), "卖空的那一格同步给客户端后也应该是空的");

        BlockPos center = helper.absolutePos(rel);
        Town town = new Town(UUID.randomUUID(), "测试镇", UUID.randomUUID(), "镇长", center);
        ZoneRect rect = new ZoneRect(center.getX() - 2, center.getZ() - 2, center.getX() + 2, center.getZ() + 2);
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.COMMERCIAL, rect) == null, "商业区应该能建");
        Zone zone = town.zoneAt(center.getX(), center.getZ());
        TownManager.setIndustry(level, town, zone.id, Industry.WHEAT);
        helper.assertTrue(zone.industry == Industry.NONE, "商业区不能种地");
        TownManager.setIndustry(level, town, zone.id, Industry.RESTAURANT);
        TownManager.setIndustry(level, town, zone.id, Industry.SHOP);
        helper.assertTrue(zone.industry == Industry.SHOP, "商业区应该能选商店和餐厅");
        helper.assertTrue(com.townfolk.entity.ai.Terrain.findShelves(level, rect).size() == 3, "商业区里应该数出 3 个货架");
        helper.assertTrue(Town.load(town.save()).zones.get(zone.id).industry == Industry.SHOP, "商业区的店铺类型应该能存下来");

        // 拆掉上半格，整个货架都没了
        level.destroyBlock(helper.absolutePos(rel.above()), false);
        helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(), "拆掉货架的上半格，下半格也应该跟着没了");
        helper.succeed();
    }

    /** 分区规则：太小、太大、出镇、重叠都不允许。 */
    @GameTest(template = "empty", batch = "rules")
    public static void zoneRules(GameTestHelper helper) {
        List<ZoneRect> existing = List.of(new ZoneRect(0, 0, 9, 9));
        helper.assertTrue(new ZoneRect(20, 20, 29, 29).problem(0, 0, existing) == null, "正常的分区应该可以建");
        helper.assertTrue("townfolk.zone.too_small".equals(new ZoneRect(20, 20, 21, 29).problem(0, 0, existing)), "宽度不到 3 应该太小");
        helper.assertTrue("townfolk.zone.too_large".equals(new ZoneRect(-60, 20, 0, 29).problem(0, 0, existing)), "超过 48 格应该太大");
        helper.assertTrue("townfolk.zone.out_of_town".equals(new ZoneRect(60, 60, 70, 70).problem(0, 0, existing)), "超出小镇范围应该不行");
        helper.assertTrue("townfolk.zone.overlap".equals(new ZoneRect(5, 5, 15, 15).problem(0, 0, existing)), "和已有分区重叠应该不行");
        helper.succeed();
    }

    /**
     * 完整的一天：农夫挖水坑、开垦、播种、收割；伐木工砍树、补种；傍晚把收获存进箱子；
     * 晚上所有人回自己的住处睡觉；天亮起床。
     */
    @GameTest(template = "empty_big", batch = "work", timeoutTicks = 6000, skyAccess = true)
    public static void workDay(GameTestHelper helper) {
        Demographics.autoEvents = false;
        ServerLevel level = helper.getLevel();
        TestSupport.layGround(helper, 32);

        // 种植区 9×9，里面预先放三株成熟的小麦和一个箱子
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, GROUND, 2), Blocks.FARMLAND);
            helper.setBlock(new BlockPos(x, GROUND + 1, 2), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        }
        BlockPos farmChest = new BlockPos(10, GROUND + 1, 10);
        helper.setBlock(farmChest, Blocks.CHEST);

        // 林场 11×11，里面一棵橡树和一个箱子
        BlockPos trunk = new BlockPos(25, GROUND + 1, 7);
        for (int dy = 0; dy < 4; dy++) {
            helper.setBlock(trunk.above(dy), Blocks.OAK_LOG);
        }
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.setBlock(trunk.above(3).relative(direction), leaves);
            helper.setBlock(trunk.above(2).relative(direction), leaves);
        }
        helper.setBlock(trunk.above(4), leaves);
        BlockPos lumberChest = new BlockPos(30, GROUND + 1, 12);
        helper.setBlock(lumberChest, Blocks.CHEST);

        // 楼房居民区，六张露天的床：没有墙隔着，每张床各算一户
        List<BlockPos> beds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            beds.add(TestSupport.placeBed(helper, new BlockPos(13 + i, GROUND + 1, 24)));
        }

        level.setDayTime(1000L);
        Town town = TestSupport.foundTown(helper, new BlockPos(16, GROUND + 1, 16));
        BlockPos hall = town.center;

        ZoneRect farmRect = TestSupport.rect(helper, 2, 2, 10, 10);
        ZoneRect lumberRect = TestSupport.rect(helper, 20, 2, 30, 12);
        ZoneRect homeRect = TestSupport.rect(helper, 12, 22, 20, 30);
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.AGRICULTURE, farmRect) == null, "种植区应该能建");
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.AGRICULTURE, lumberRect) == null, "林场应该能建");
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.RESIDENTIAL, homeRect) == null, "居民区应该能建");
        helper.assertTrue("townfolk.zone.overlap".equals(TownManager.createZone(level, town, ZoneType.LIGHT_INDUSTRY, farmRect)), "重叠的分区不应该能建");

        Zone farm = town.zoneAt(farmRect.minX(), farmRect.minZ());
        Zone lumber = town.zoneAt(lumberRect.minX(), lumberRect.minZ());
        Zone home = town.zoneAt(homeRect.minX(), homeRect.minZ());
        TownManager.setIndustry(level, town, farm.id, Industry.WHEAT);
        TownManager.setIndustry(level, town, lumber.id, Industry.OAK);
        TownManager.setIndustry(level, town, home.id, Industry.WHEAT);
        helper.assertTrue(home.industry == Industry.NONE, "居民区不能选农业的产业");

        TownTicker.tickTown(level, town);
        helper.assertTrue(town.bedCount() == 0, "没选房型的居民区里，床不算床位");
        TownManager.setIndustry(level, town, home.id, Industry.APARTMENT);
        TownTicker.tickTown(level, town);
        helper.assertTrue(town.homes.size() == 6 && town.bedCount() == 6, "六张露天的床应该认成 6 户，实际 " + town.homes.size() + " 户");

        List<UUID> ids = new ArrayList<>(town.residents.keySet());
        helper.assertTrue(town.workers(farm.id).size() == 1 && town.workers(lumber.id).size() == 1, "两个分区应该各招到 1 人");
        ResidentEntity farmer = (ResidentEntity) level.getEntity(town.workers(farm.id).get(0).id);
        ResidentEntity lumberjack = (ResidentEntity) level.getEntity(town.workers(lumber.id).get(0).id);
        farmer.syncWithTown(level);
        lumberjack.syncWithTown(level);
        farmer.giveTool(new ItemStack(Items.IRON_HOE));
        lumberjack.giveTool(new ItemStack(Items.IRON_AXE));

        BlockPos pit = helper.absolutePos(new BlockPos(6, GROUND, 6));
        int[] phase = {0};
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                // 白天：两个人各自干活
                helper.assertTrue(level.getFluidState(pit).isSource(), "规划的水坑位置应该已经挖好并有水");
                int farmland = 0;
                int crops = 0;
                for (int x = farmRect.minX(); x <= farmRect.maxX(); x++) {
                    for (int z = farmRect.minZ(); z <= farmRect.maxZ(); z++) {
                        if (level.getBlockState(new BlockPos(x, pit.getY(), z)).getBlock() instanceof FarmBlock) farmland++;
                        if (level.getBlockState(new BlockPos(x, pit.getY() + 1, z)).is(Blocks.WHEAT)) crops++;
                    }
                }
                helper.assertTrue(farmland >= 20, "农夫应该已经开垦出至少 20 格耕地，现在 " + farmland);
                helper.assertTrue(crops >= 12, "农夫应该已经种下至少 12 株小麦，现在 " + crops);
                int wheat = farmer.countItem(Items.WHEAT) + countInChest(level, helper.absolutePos(farmChest), Items.WHEAT);
                helper.assertTrue(wheat >= 3, "农夫应该收割了预先放的三株小麦，现在只有 " + wheat);

                for (int dy = 0; dy < 4; dy++) {
                    helper.assertTrue(!level.getBlockState(helper.absolutePos(trunk.above(dy))).is(BlockTags.LOGS), "树应该被砍光");
                }
                int logs = countInInventory(lumberjack, ItemTags.LOGS) + countInChest(level, helper.absolutePos(lumberChest), Items.OAK_LOG);
                helper.assertTrue(logs >= 4, "伐木工应该砍到 4 块原木，现在只有 " + logs);
                int saplings = 0;
                for (int x = lumberRect.minX(); x <= lumberRect.maxX(); x++) {
                    for (int z = lumberRect.minZ(); z <= lumberRect.maxZ(); z++) {
                        if (level.getBlockState(new BlockPos(x, pit.getY() + 1, z)).is(Blocks.OAK_SAPLING)) saplings++;
                    }
                }
                helper.assertTrue(saplings >= 3, "伐木工应该已经补种了至少 3 棵树苗，现在 " + saplings);
                assertSaplingsSpaced(helper, level, lumberRect, pit.getY() + 1, Industry.OAK.treeGap);

                phase[0] = 1;
                level.setDayTime(10600L);          // 快收工了
            }
            if (phase[0] == 1) {
                helper.assertTrue(countInChest(level, helper.absolutePos(farmChest), Items.WHEAT) >= 3, "收工前农夫应该把小麦存进箱子");
                helper.assertTrue(countInChest(level, helper.absolutePos(lumberChest), Items.OAK_LOG) >= 4, "收工前伐木工应该把原木存进箱子");
                phase[0] = 2;
                level.setDayTime(14500L);          // 晚上八点半，时间表上该睡觉了
            }
            if (phase[0] == 2) {
                int sleeping = 0;
                for (UUID id : ids) {
                    ResidentRecord record = town.residents.get(id);
                    helper.assertTrue(record.bedPos != null && beds.contains(record.bedPos), "每个居民都应该分到了楼房里的一套房");
                    if (level.getEntity(id) instanceof ResidentEntity resident && resident.isSleeping()) sleeping++;
                }
                helper.assertTrue(sleeping == 6, "晚上 6 个人都应该在床上睡觉，现在 " + sleeping);
                phase[0] = 3;
                level.setDayTime(24000L + 500L);   // 天亮了
            }
            if (phase[0] == 3) {
                for (UUID id : ids) {
                    helper.assertTrue(level.getEntity(id) instanceof ResidentEntity resident && !resident.isSleeping(), "天亮后所有人都应该起床");
                }
                level.removeBlock(hall, false);
                for (UUID id : ids) {
                    if (level.getEntity(id) != null) level.getEntity(id).discard();
                }
            }
        });
    }

    /** 几个农夫一起下地：任何时候都不应该有两个人奔着同一格去。 */
    @GameTest(template = "empty_big", batch = "spread", timeoutTicks = 400, skyAccess = true)
    public static void farmersSpreadOut(GameTestHelper helper) {
        Demographics.autoEvents = false;
        ServerLevel level = helper.getLevel();
        TestSupport.layGround(helper, 32);
        level.setDayTime(1000L);
        Town town = TestSupport.foundTown(helper, new BlockPos(16, GROUND + 1, 28));
        ZoneRect farmRect = TestSupport.rect(helper, 4, 2, 24, 22);
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.AGRICULTURE, farmRect) == null, "种植区应该能建");
        Zone farm = town.zoneAt(farmRect.minX(), farmRect.minZ());
        TownManager.setIndustry(level, town, farm.id, Industry.WHEAT);
        TownManager.setWorkers(level, town, farm.id, 6);
        TownTicker.tickTown(level, town);
        helper.assertTrue(town.workers(farm.id).size() == 6, "6 个人都应该被农场招走");

        List<ResidentEntity> farmers = new ArrayList<>();
        for (UUID id : town.residents.keySet()) {
            ResidentEntity farmer = (ResidentEntity) level.getEntity(id);
            farmer.syncWithTown(level);
            farmer.giveTool(new ItemStack(Items.IRON_HOE));
            farmers.add(farmer);
        }
        int[] busyTicks = {0};
        helper.onEachTick(() -> {
            java.util.Set<BlockPos> targets = new java.util.HashSet<>();
            int busy = 0;
            for (ResidentEntity farmer : farmers) {
                BlockPos target = farmer.getWorkTarget();
                if (target == null) continue;
                busy++;
                if (!targets.add(target)) {
                    helper.fail("有两个农夫同时奔着同一格去：" + target.toShortString());
                }
            }
            if (busy >= 5) busyTicks[0]++;
        });
        helper.runAfterDelay(300, () -> {
            helper.assertTrue(busyTicks[0] >= 150, "大部分时间里应该至少有 5 个农夫同时在干活，实际只有 " + busyTicks[0] + " tick");
            int farmland = 0;
            int y = helper.absolutePos(new BlockPos(0, GROUND, 0)).getY();
            for (int x = farmRect.minX(); x <= farmRect.maxX(); x++) {
                for (int z = farmRect.minZ(); z <= farmRect.maxZ(); z++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof FarmBlock) farmland++;
                }
            }
            helper.assertTrue(farmland >= 20, "6 个农夫 15 秒（含走到田里和挖水坑）应该开垦出至少 20 格地，实际 " + farmland);
            level.removeBlock(town.center, false);
            for (ResidentEntity farmer : farmers) {
                farmer.discard();
            }
            helper.succeed();
        });
    }

    /** 按时间表过一天：吃饭的钟点吃饭，休息的钟点坐下，自由活动时有人聊天，上班的钟点去干活。 */
    @GameTest(template = "empty_big", batch = "routine", timeoutTicks = 2400, skyAccess = true)
    public static void dailyRoutine(GameTestHelper helper) {
        Demographics.autoEvents = false;
        ServerLevel level = helper.getLevel();
        TestSupport.layGround(helper, 32);
        for (int i = 0; i < 6; i++) {
            TestSupport.placeBed(helper, new BlockPos(11 + i * 2, GROUND + 1, 24));
        }
        level.setDayTime(6000L);                    // 中午 12 点：吃饭
        Town town = TestSupport.foundTown(helper, new BlockPos(16, GROUND + 1, 16));
        ZoneRect farmRect = TestSupport.rect(helper, 2, 2, 12, 12);
        ZoneRect homeRect = TestSupport.rect(helper, 10, 22, 22, 30);
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.AGRICULTURE, farmRect) == null, "种植区应该能建");
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.RESIDENTIAL, homeRect) == null, "居民区应该能建");
        Zone farm = town.zoneAt(farmRect.minX(), farmRect.minZ());
        TownManager.setIndustry(level, town, farm.id, Industry.WHEAT);
        TownManager.setIndustry(level, town, town.zoneAt(homeRect.minX(), homeRect.minZ()).id, Industry.APARTMENT);
        TownTicker.tickTown(level, town);

        List<ResidentEntity> residents = new ArrayList<>();
        for (UUID id : town.residents.keySet()) {
            residents.add((ResidentEntity) level.getEntity(id));
        }
        ResidentEntity farmer = (ResidentEntity) level.getEntity(town.workers(farm.id).get(0).id);
        for (ResidentRecord record : town.residents.values()) {
            record.pantry = 60;                     // 家里都有存粮
        }

        int[] phase = {0};
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                for (ResidentEntity resident : residents) {
                    helper.assertTrue(resident.getRoutine() == Activity.EAT, "12 点所有人都该吃饭");
                    helper.assertTrue(resident.getStatus() == com.townfolk.entity.ResidentStatus.EATING, "吃饭的钟点，状态应该是吃饭：" + resident.getStatus());
                }
                int biting = 0;
                for (ResidentEntity resident : residents) {
                    if (resident.getWorkAnim() == com.townfolk.entity.WorkAnim.EAT && resident.getMainHandItem().is(Items.BREAD)) biting++;
                }
                helper.assertTrue(biting >= 1, "应该有人正拿着面包在吃");
                phase[0] = 1;
                level.setDayTime(7000L);            // 13 点：休息
            }
            if (phase[0] == 1) {
                for (ResidentEntity resident : residents) {
                    helper.assertTrue(resident.getStatus() == com.townfolk.entity.ResidentStatus.BREAK
                            && resident.getWorkAnim() == com.townfolk.entity.WorkAnim.SIT, "休息的钟点应该坐下歇着：" + resident.getStatus());
                }
                helper.assertTrue(!farmer.getMainHandItem().is(Items.BREAD), "吃完饭手里不应该还拿着面包");
                phase[0] = 2;
                level.setDayTime(11000L);           // 17 点：自由活动
            }
            if (phase[0] == 2) {
                int chatting = 0;
                for (ResidentEntity resident : residents) {
                    helper.assertTrue(resident.getRoutine() == Activity.FREE, "17 点是自由活动");
                    if (resident.getStatus() == com.townfolk.entity.ResidentStatus.CHATTING
                            && resident.getWorkAnim() == com.townfolk.entity.WorkAnim.TALK) chatting++;
                }
                helper.assertTrue(chatting >= 2, "自由活动时应该有两个人凑在一起聊天，现在 " + chatting + " 人");
                phase[0] = 3;
                level.setDayTime(24000L + 1500L);   // 第二天 7 点半：上班
            }
            if (phase[0] == 3) {
                helper.assertTrue(farmer.getRoutine() == Activity.WORK && farmer.getWorkTarget() != null, "上班的钟点农夫应该在干活");
                int idle = 0;
                for (ResidentEntity resident : residents) {
                    if (resident != farmer && resident.getRoutine() == Activity.FREE) idle++;
                }
                helper.assertTrue(idle == 5, "没工作的人在上班的钟点算自由活动");
                level.removeBlock(town.center, false);
                for (ResidentEntity resident : residents) {
                    resident.discard();
                }
            }
        });
    }

    private static int countInInventory(ResidentEntity resident, TagKey<Item> tag) {
        int count = 0;
        for (ItemStack stack : resident.getInventory().getItems()) {
            if (stack.is(tag)) count += stack.getCount();
        }
        return count;
    }

    private static int countInChest(ServerLevel level, BlockPos pos, Item item) {
        if (!(level.getBlockEntity(pos) instanceof Container container)) return 0;
        return container.countItem(item);
    }

    /** 任意两棵树苗之间至少隔 gap 格。 */
    private static void assertSaplingsSpaced(GameTestHelper helper, ServerLevel level, ZoneRect rect, int y, int gap) {
        List<BlockPos> saplings = new ArrayList<>();
        for (int x = rect.minX(); x <= rect.maxX(); x++) {
            for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (level.getBlockState(pos).is(BlockTags.SAPLINGS)) saplings.add(pos);
            }
        }
        for (BlockPos a : saplings) {
            for (BlockPos b : saplings) {
                if (a.equals(b)) continue;
                int apart = Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
                helper.assertTrue(apart > gap, "树苗 " + a.toShortString() + " 和 " + b.toShortString() + " 挨得太近");
            }
        }
    }
}
