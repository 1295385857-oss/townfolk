package com.townfolk.test;

import com.townfolk.Townfolk;
import com.townfolk.block.RegisterBlock;
import com.townfolk.block.RegisterBlockEntity;
import com.townfolk.block.ShelfBlock;
import com.townfolk.block.ShelfBlockEntity;
import com.townfolk.entity.Profession;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.registry.ModRegistry;
import com.townfolk.town.Demographics;
import com.townfolk.town.Economy;
import com.townfolk.town.Industry;
import com.townfolk.town.PriceTable;
import com.townfolk.town.ResidentRecord;
import com.townfolk.town.ShopData;
import com.townfolk.town.Shopping;
import com.townfolk.town.Town;
import com.townfolk.town.TownManager;
import com.townfolk.town.TownTicker;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import com.townfolk.town.ZoneType;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 金币、工具、按需购物、排队结账、店员自动定价的游戏内自动测试。 */
@GameTestHolder(Townfolk.MODID)
@PrefixGameTestTemplate(false)
public class ShopGameTests {
    private static final int GROUND = TestSupport.GROUND;

    /** 账本的定价规则：卖得快涨价，有人没买到涨价，积压降价，涨跌都有上下限。 */
    @GameTest(template = "empty", batch = "rules")
    public static void pricingRules(GameTestHelper helper) {
        helper.assertTrue(PriceTable.base(Items.BREAD) == 6 && PriceTable.base(Items.WOODEN_HOE) == 4 && PriceTable.base(Items.IRON_AXE) == 20, "基准价应该和价格表一致");
        helper.assertTrue(PriceTable.nutrition(Items.BREAD) == 5 && PriceTable.nutrition(Items.WHEAT) == 0 && PriceTable.nutrition(Items.ROTTEN_FLESH) == 0,
                "面包能吃，小麦不能直接吃，吃了有坏处的东西不算吃的");
        helper.assertTrue(PriceTable.isToolFor(Profession.FARMER, Items.STONE_HOE) && !PriceTable.isToolFor(Profession.FARMER, Items.STONE_AXE)
                && PriceTable.isToolFor(Profession.LUMBERJACK, Items.WOODEN_AXE) && !PriceTable.isToolFor(Profession.CLERK, Items.WOODEN_AXE), "农夫用锄头，伐木工用斧头，店员不用工具");

        ShopData shop = new ShopData();
        helper.assertTrue(shop.priceOf(Items.BREAD) == 6, "第一次上架的货按基准价卖");
        shop.recordSale(Items.BREAD, 5);
        shop.adjustPrices(Map.of(Items.BREAD, 10));
        helper.assertTrue(Math.abs(shop.get(Items.BREAD).price - 6.6) < 1e-6, "一个钟点卖掉一半库存，应该涨一成，实际 " + shop.get(Items.BREAD).price);
        shop.adjustPrices(Map.of(Items.BREAD, 5));
        helper.assertTrue(Math.abs(shop.get(Items.BREAD).price - 6.6) < 1e-6, "只歇了一个钟点没卖，价格不动");
        shop.recordMiss(Items.BREAD);
        shop.adjustPrices(Map.of());
        helper.assertTrue(shop.get(Items.BREAD).price > 7.2, "有人想买没买到，应该再涨一成");

        shop.get(Items.APPLE);
        for (int hour = 0; hour < 5; hour++) {
            shop.adjustPrices(Map.of(Items.APPLE, 10));
        }
        helper.assertTrue(shop.priceOf(Items.APPLE) == 4, "积压不到 6 个钟点不降价");
        shop.adjustPrices(Map.of(Items.APPLE, 10));
        helper.assertTrue(Math.abs(shop.get(Items.APPLE).price - 3.6) < 1e-6, "积压满 6 个钟点开始降一成");
        for (int hour = 0; hour < 60; hour++) {
            shop.adjustPrices(Map.of(Items.APPLE, 10));
            shop.recordMiss(Items.BREAD);
        }
        helper.assertTrue(Math.abs(shop.get(Items.APPLE).price - 4 * ShopData.MIN_FACTOR) < 1e-6, "最低降到基准价的三成");
        helper.assertTrue(Math.abs(shop.get(Items.BREAD).price - 6 * ShopData.MAX_FACTOR) < 1e-6, "最高涨到基准价的五倍");
        helper.assertTrue(shop.priceOf(Items.APPLE) == 1, "再便宜也至少卖 1 金币");

        ShopData reloaded = new ShopData();
        reloaded.load(shop.save());
        helper.assertTrue(reloaded.priceOf(Items.BREAD) == shop.priceOf(Items.BREAD) && reloaded.get(Items.BREAD).missedToday == shop.get(Items.BREAD).missedToday,
                "账本存读档后应该不变");
        helper.succeed();
    }

    private static String describe(GameTestHelper helper, ServerLevel level, Town town) {
        StringBuilder out = new StringBuilder();
        for (ResidentRecord record : town.residents.values()) {
            ResidentEntity resident = (ResidentEntity) level.getEntity(record.id);
            out.append('[').append(record.job).append(' ').append(resident.getStatus()).append(' ')
                    .append(resident.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)).toShortString())
                    .append(" 钱").append(record.money).append(" 粮").append(record.pantry)
                    .append(" 手").append(resident.getMainHandItem().getItem()).append("] ");
        }
        return out.toString();
    }

    /** 一家店的完整流程：农夫钱够了第一时间买锄头；大家有空时按需买吃的；排队结账；钱进钱箱；店员调价。 */
    @GameTest(template = "empty_big", batch = "shop", timeoutTicks = 7000, skyAccess = true)
    public static void shopping(GameTestHelper helper) {
        Demographics.autoEvents = false;
        ServerLevel level = helper.getLevel();
        TestSupport.layGround(helper, 32);
        level.setDayTime(1000L);                    // 早上 7 点，上班
        for (int i = 0; i < 6; i++) {
            TestSupport.placeBed(helper, new BlockPos(11 + i * 2, GROUND + 1, 26));
        }

        // 商店：三个货架朝南一字排开，收银台在它们南边也朝南（客人在南面排队，店员站北面）
        BlockPos hoeShelf = new BlockPos(22, GROUND + 1, 4);
        BlockPos carrotShelf = new BlockPos(24, GROUND + 1, 4);
        BlockPos breadShelf = new BlockPos(26, GROUND + 1, 4);
        for (BlockPos pos : new BlockPos[] {hoeShelf, carrotShelf, breadShelf}) {
            ((com.townfolk.block.TallShelfBlock) ModRegistry.SHELF.get()).placeWhole(level, helper.absolutePos(pos), Direction.SOUTH);
        }
        BlockPos registerPos = new BlockPos(24, GROUND + 1, 8);
        helper.setBlock(registerPos, ModRegistry.CASH_REGISTER.get().defaultBlockState().setValue(RegisterBlock.FACING, Direction.SOUTH));
        ShelfBlockEntity hoes = (ShelfBlockEntity) helper.getBlockEntity(hoeShelf);
        hoes.setItem(0, new ItemStack(Items.WOODEN_HOE));
        hoes.setItem(1, new ItemStack(Items.WOODEN_HOE));
        ((ShelfBlockEntity) helper.getBlockEntity(carrotShelf)).setItem(0, new ItemStack(Items.CARROT, 20));
        ((ShelfBlockEntity) helper.getBlockEntity(breadShelf)).setItem(0, new ItemStack(Items.BREAD, 40));
        RegisterBlockEntity register = (RegisterBlockEntity) helper.getBlockEntity(registerPos);

        Town town = TestSupport.foundTown(helper, new BlockPos(16, GROUND + 1, 16));
        ZoneRect farmRect = TestSupport.rect(helper, 2, 2, 12, 12);
        ZoneRect shopRect = TestSupport.rect(helper, 20, 2, 30, 12);
        ZoneRect homeRect = TestSupport.rect(helper, 10, 24, 22, 30);
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.AGRICULTURE, farmRect) == null, "农场应该能建");
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.COMMERCIAL, shopRect) == null, "商业区应该能建");
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.RESIDENTIAL, homeRect) == null, "居民区应该能建");
        Zone farm = town.zoneAt(farmRect.minX(), farmRect.minZ());
        Zone shop = town.zoneAt(shopRect.minX(), shopRect.minZ());
        TownManager.setIndustry(level, town, farm.id, Industry.WHEAT);
        TownManager.setIndustry(level, town, shop.id, Industry.SHOP);
        TownManager.setIndustry(level, town, town.zoneAt(homeRect.minX(), homeRect.minZ()).id, Industry.APARTMENT);
        helper.assertTrue(shop.workerTarget == 1, "商店默认招 1 个店员");
        TownTicker.tickTown(level, town);

        helper.assertTrue(town.treasury == 40, "6 个人各领 10 金币安家费，金库应该剩 40，实际 " + town.treasury);
        ResidentRecord farmerRecord = town.workers(farm.id).get(0);
        ResidentRecord clerkRecord = town.workers(shop.id).get(0);
        helper.assertTrue(clerkRecord.job == Profession.CLERK, "被商店招走的人应该是店员");
        ResidentEntity farmer = (ResidentEntity) level.getEntity(farmerRecord.id);
        helper.assertTrue(!farmer.hasTool() && Shopping.wantsTool(farmerRecord) && !Shopping.wantsTool(clerkRecord), "农夫一开始没有工具，需要买；店员不需要");

        int[] phase = {0};
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                ResidentEntity clerk = (ResidentEntity) level.getEntity(clerkRecord.id);
                helper.assertTrue(farmer.hasTool(), "农夫有 10 金币，木锄 4 金币，应该第一时间去买。店员状态=" + clerk.getStatus()
                        + " 店员位置=" + clerk.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)).toShortString()
                        + " 有人看店=" + register.isTended(level.getGameTime()) + " 农夫状态=" + farmer.getStatus() + " 农夫钱=" + farmerRecord.money
                        + " 农夫位置=" + farmer.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)).toShortString()
                        + " 排队人数=" + register.queueLength() + " 所有人=" + describe(helper, level, town));
                helper.assertTrue(farmer.getMainHandItem().is(Items.WOODEN_HOE), "买到的锄头应该拿在手里");
                helper.assertTrue(shop.shop.get(Items.WOODEN_HOE).soldToday == 1, "账本上应该记着卖出 1 把木锄");
                helper.assertTrue(register.getMoney() >= 4, "钱箱里至少应该收了卖锄头的 4 金币，实际 " + register.getMoney());
                helper.assertTrue(farmerRecord.money >= 6 && (farmerRecord.money - 6) % Economy.HOURLY_WAGE == 0,
                        "农夫应该正好付了 4 金币（之后可能领过工资），现在有 " + farmerRecord.money);
                helper.assertTrue(level.getBlockState(helper.absolutePos(registerPos)).getValue(RegisterBlock.COINS) >= 1, "钱箱里有钱，托盘上应该摆出钱币");
                helper.assertTrue(helper.getTick() < 700, "农夫应该在店开门后很快就买到锄头，实际用了 " + helper.getTick() + " tick");
                phase[0] = 1;
                level.setDayTime(11000L);           // 下午 5 点，自由活动
            }
            if (phase[0] == 1) {
                int fed = 0;
                for (ResidentRecord record : town.residents.values()) {
                    if (Economy.pantryOwner(town, record).pantry > 0) fed++;
                }
                helper.assertTrue(fed == 6, "6 个人都应该买到了吃的，现在 " + fed + " 人。时间=" + level.getDayTime() + " 面包价=" + shop.shop.priceOf(Items.BREAD)
                        + " 胡萝卜价=" + shop.shop.priceOf(Items.CARROT) + " 所有人=" + describe(helper, level, town));
                int carrots = shop.shop.get(Items.CARROT).soldToday;
                helper.assertTrue(carrots == 20, "胡萝卜每点饱腹更便宜，应该先被买光，实际卖出 " + carrots);
                helper.assertTrue(shop.shop.get(Items.BREAD).soldToday >= 1, "胡萝卜卖光后应该有人改买面包");
                int wallets = 0;
                for (ResidentRecord record : town.residents.values()) {
                    helper.assertTrue(record.money >= 0, "谁都不应该欠钱");
                    wallets += record.money;
                }
                // 居民花掉的钱都进了钱箱：一把锄头 4 金币，20 根胡萝卜至少各 2 金币
                helper.assertTrue(register.getMoney() >= 4 + 2 * carrots, "钱箱里至少有卖锄头和胡萝卜的钱，实际 " + register.getMoney());
                helper.assertTrue(register.isTended(level.getGameTime()), "自由活动的钟点店员应该还在看店");
                helper.assertTrue(register.queueLength() == 0, "都结完账了，不应该还有人排队");

                // 调价：这个钟点胡萝卜卖得飞快，店员应该涨价
                int farmerMoney = farmerRecord.money;
                Economy.endHour(level, town, 17);
                helper.assertTrue(shop.shop.get(Items.CARROT).price > 2.0, "胡萝卜卖得快，应该涨价");
                helper.assertTrue(farmerRecord.money == farmerMoney, "自由活动的钟点农夫没有工资");
                Economy.endHour(level, town, 8);
                helper.assertTrue(farmerRecord.money == farmerMoney + Economy.HOURLY_WAGE, "工作的钟点农夫应该得到工资");

                // 想买没买到：把吃的全撤掉，再有人来就记到账本上
                ((ShelfBlockEntity) helper.getBlockEntity(carrotShelf)).clearContent();
                ((ShelfBlockEntity) helper.getBlockEntity(breadShelf)).clearContent();
                ResidentRecord hungry = Economy.pantryOwner(town, farmerRecord);
                hungry.pantry = 0;
                int missedBefore = shop.shop.get(Items.BREAD).missedToday;
                helper.assertTrue(Shopping.plan(level, town, farmerRecord, farmer.blockPosition(), true) == null, "店里没有吃的，就不会进店白跑");
                helper.assertTrue(shop.shop.get(Items.BREAD).missedToday == missedBefore + 1, "想买吃的没买到，应该记到账本上");

                level.removeBlock(town.center, false);
                for (ResidentRecord record : town.residents.values()) {
                    if (level.getEntity(record.id) != null) level.getEntity(record.id).discard();
                }
            }
        });
    }
}
