package com.townfolk.test;

import com.townfolk.Townfolk;
import com.townfolk.entity.Profession;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.network.TownView;
import com.townfolk.registry.ModRegistry;
import com.townfolk.town.Demographics;
import com.townfolk.town.Industry;
import com.townfolk.town.ResidentRecord;
import com.townfolk.town.Town;
import com.townfolk.town.TownManager;
import com.townfolk.town.TownSavedData;
import com.townfolk.town.TownTicker;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import com.townfolk.town.ZoneType;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 游戏内自动测试，用 gradlew runGameTestServer 运行。
 * 只有运行配置里启用了本模组的测试命名空间时才会注册，正常游玩时不起作用。
 */
@GameTestHolder(Townfolk.MODID)
@PrefixGameTestTemplate(false)
public class TownGameTests {

    /** 建镇 → 开局居民 → 按人数自动招工 → 存读档 → 拆除镇公所。 */
    @GameTest(template = "empty", batch = "lifecycle", timeoutTicks = 200)
    public static void townLifecycle(GameTestHelper helper) {
        Demographics.autoEvents = false;
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 1, 4));
        Town town = TestSupport.foundTown(helper, new BlockPos(4, 1, 4));
        helper.assertTrue(town.residents.size() == TownManager.FOUNDER_COUNT, "名册里应该有 6 位开局居民，实际 " + town.residents.size());

        // 开局居民：3 男 3 女，20 到 30 岁，姓氏互不相同
        List<ResidentEntity> residents = new ArrayList<>();
        Set<String> surnames = new HashSet<>();
        int males = 0;
        for (UUID id : town.residents.keySet()) {
            helper.assertTrue(level.getEntity(id) instanceof ResidentEntity, "名册里的居民应该在世界里");
            ResidentEntity resident = (ResidentEntity) level.getEntity(id);
            residents.add(resident);
            if (resident.isMale()) males++;
            helper.assertTrue(resident.getAgeYears() >= 20 && resident.getAgeYears() <= 30, "开局居民年龄应在 20 到 30 岁，实际 " + resident.getAgeYears());
            helper.assertTrue(resident.getLifespanYears() >= ResidentEntity.MIN_LIFESPAN && resident.getLifespanYears() <= ResidentEntity.MAX_LIFESPAN, "寿命应在 70 到 85 岁之间");
            helper.assertTrue(resident.getFullName().length() >= 2, "居民应该有姓名");
            helper.assertTrue(town.id.equals(resident.getTownId()), "居民应该归属这个小镇");
            surnames.add(resident.getFullName().substring(0, 1));
            ResidentRecord record = town.residents.get(id);
            helper.assertTrue(record.name.equals(resident.getFullName()) && record.male == resident.isMale() && record.ageYears == resident.getAgeYears(),
                    "名册记录应该和居民一致");
        }
        helper.assertTrue(males == 3, "应该有 3 位男性，实际 " + males);
        helper.assertTrue(surnames.size() == TownManager.FOUNDER_COUNT, "开局居民的姓氏应该互不相同");

        // 离得太近不能再建一个镇
        helper.assertTrue(TownManager.foundTown(level, pos.offset(10, 0, 0), helper.makeMockServerPlayerInLevel()) == null, "离已有小镇太近时不应该能建镇");

        // 镇长只定人数，谁去干由小镇安排
        ZoneRect farmRect = TestSupport.rect(helper, 0, 0, 2, 2);
        ZoneRect lumberRect = TestSupport.rect(helper, 6, 0, 8, 2);
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.AGRICULTURE, farmRect) == null, "种植区应该能建");
        helper.assertTrue(TownManager.createZone(level, town, ZoneType.AGRICULTURE, lumberRect) == null, "林场应该能建");
        Zone farm = town.zoneAt(farmRect.minX(), farmRect.minZ());
        Zone lumber = town.zoneAt(lumberRect.minX(), lumberRect.minZ());
        TownManager.setIndustry(level, town, farm.id, Industry.WHEAT);
        TownManager.setIndustry(level, town, lumber.id, Industry.OAK);
        helper.assertTrue(farm.workerTarget == 1, "刚选好产业的分区默认招 1 人");
        TownManager.setWorkers(level, town, farm.id, 2);
        TownManager.setWorkers(level, town, lumber.id, 99);
        helper.assertTrue(lumber.workerTarget == Zone.MAX_WORKERS, "工作人数最多 8 人");
        TownTicker.tickTown(level, town);
        helper.assertTrue(town.workers(farm.id).size() == 2, "种植区应该招到 2 人，实际 " + town.workers(farm.id).size());
        helper.assertTrue(town.workers(lumber.id).size() == 4, "剩下的 4 个人都应该被林场招走，实际 " + town.workers(lumber.id).size());

        TownManager.setWorkers(level, town, lumber.id, 1);
        TownTicker.tickTown(level, town);
        helper.assertTrue(town.workers(lumber.id).size() == 1, "人数调低后多出来的人应该离岗");
        int unemployed = 0;
        for (ResidentRecord record : town.residents.values()) {
            if (record.job == Profession.UNEMPLOYED) unemployed++;
            Zone zone = record.zoneId == null ? null : town.zones.get(record.zoneId);
            helper.assertTrue(record.job == (zone == null ? Profession.UNEMPLOYED : zone.industry.profession), "职业应该跟着分区的产业走");
        }
        helper.assertTrue(unemployed == 3, "应该有 3 个人无业，实际 " + unemployed);

        ResidentEntity farmer = (ResidentEntity) level.getEntity(town.workers(farm.id).get(0).id);
        ResidentEntity lumberjack = (ResidentEntity) level.getEntity(town.workers(lumber.id).get(0).id);
        farmer.syncWithTown(level);
        helper.assertTrue(farmer.getProfession() == Profession.FARMER, "被种植区招走的居民应该成为农夫");
        helper.assertTrue(farmer.getMainHandItem().isEmpty() && !farmer.hasTool(), "农夫一开始没有工具，要自己去买");
        farmer.giveTool(new net.minecraft.world.item.ItemStack(Items.STONE_HOE));
        helper.assertTrue(farmer.hasTool() && farmer.getMainHandItem().is(Items.STONE_HOE), "拿到锄头后应该握在手里");
        farmer.useTool(Items.STONE_HOE.getDefaultInstance().getMaxDamage());
        helper.assertTrue(!farmer.hasTool() && farmer.getMainHandItem().isEmpty(), "工具用到头应该坏掉");
        helper.assertTrue(town.treasury == 100 - 6 * town.allowance, "6 位开局居民各从金库领了安家费，金库应该剩 40，实际 " + town.treasury);
        for (ResidentRecord record : town.residents.values()) {
            helper.assertTrue(record.money == town.allowance, "每位开局居民应该有 10 金币");
        }

        // 界面数据的网络编解码
        TownView view = TownManager.buildView(level, town, helper.makeMockServerPlayerInLevel());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        view.write(buf);
        helper.assertTrue(TownView.read(buf).equals(view), "界面数据编解码后应该不变");
        helper.assertTrue(view.residents().size() == TownManager.FOUNDER_COUNT && view.zone(farm.id).workers().size() == 2, "界面数据应该包含 6 位居民和分区的工人");

        // 小镇存读档
        Town reloaded = Town.load(town.save());
        helper.assertTrue(reloaded.id.equals(town.id) && reloaded.name.equals(town.name) && reloaded.center.equals(town.center)
                && reloaded.popCap == town.popCap && reloaded.residents.size() == town.residents.size(), "小镇存读档后应该不变");
        helper.assertTrue(reloaded.residents.get(farmer.getUUID()).job == Profession.FARMER
                && farm.id.equals(reloaded.residents.get(farmer.getUUID()).zoneId), "名册里的职业和工作分区应该能存下来");
        helper.assertTrue(reloaded.zones.get(farm.id).workerTarget == 2 && reloaded.zones.get(farm.id).industry == Industry.WHEAT, "分区的产业和工作人数应该能存下来");

        // 居民存读档
        CompoundTag tag = new CompoundTag();
        farmer.saveWithoutId(tag);
        ResidentEntity copy = ModRegistry.RESIDENT.get().create(level);
        copy.load(tag);
        helper.assertTrue(copy.getFullName().equals(farmer.getFullName()) && copy.isMale() == farmer.isMale()
                && copy.getAgeYears() == farmer.getAgeYears() && copy.getProfession() == Profession.FARMER
                && copy.getVariant() == farmer.getVariant() && copy.getLifespanYears() == farmer.getLifespanYears()
                && town.id.equals(copy.getTownId()), "居民存读档后应该不变");
        copy.discard();

        // 让居民活动几秒，再检查同步和解散
        helper.runAfterDelay(100, () -> {
            for (ResidentEntity resident : residents) {
                helper.assertTrue(resident.isAlive(), "居民应该还活着");
            }
            helper.assertTrue(lumberjack.getProfession() == Profession.LUMBERJACK, "居民应该从名册读到新职业");

            level.removeBlock(pos, false);
            helper.assertTrue(TownSavedData.get(level).getTownAt(pos) == null, "拆除镇公所后小镇应该解散");
            for (ResidentEntity resident : residents) {
                helper.assertTrue(resident.getTownId() == null, "小镇解散后居民不应再有归属");
                helper.assertTrue(resident.getProfession() == Profession.UNEMPLOYED, "小镇解散后居民应该变回无业");
                resident.discard();
            }
            helper.succeed();
        });
    }
}
