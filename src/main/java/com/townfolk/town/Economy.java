package com.townfolk.town;

import com.townfolk.block.ShelfBlockEntity;
import com.townfolk.entity.Profession;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ai.Terrain;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 钱的进出：安家费、工资，以及每个钟点让各家店调一次价。 */
public final class Economy {
    /**
     * 临时办法：居民每干一个钟点的活，凭空得到这么多金币。
     * 等“货和钱归谁”定下来以后，这里要换成真正的收入来源。
     */
    public static final int HOURLY_WAGE = 4;
    /** 一个人一天要吃掉多少饱腹点数。 */
    public static final int DAILY_FOOD = 12;
    public static final int MAX_ALLOWANCE = 100;

    /** 安家费政策：新出生或新来的居民从金库领一笔钱。金库不够时发不出来。 */
    public static void grantAllowance(ServerLevel level, Town town, ResidentRecord record) {
        int amount = town.allowance;
        if (amount <= 0) return;
        if (town.treasury < amount) {
            TownManager.notifyMayor(level, town, Component.translatable("townfolk.message.allowance_failed", record.name, amount));
            return;
        }
        town.treasury -= amount;
        record.money += amount;
    }

    /** 每次小镇例行检查时调用。跨过整点时发工资、调价。有变化时返回 true。 */
    public static boolean update(ServerLevel level, Town town) {
        long hourIndex = Math.floorDiv(level.getDayTime(), 1000L);
        if (town.lastHourIndex < 0) {
            town.lastHourIndex = hourIndex;
            return true;
        }
        if (hourIndex == town.lastHourIndex) return false;
        // 时间往回调了，或者一下跳过很多（睡觉、指令）：只按过了一个钟点算
        endHour(level, town, Town.hourOf(town.lastHourIndex * 1000L));
        town.lastHourIndex = hourIndex;
        if (Town.hourOf(level.getDayTime()) == 6) {
            for (Zone zone : town.zones.values()) {
                zone.shop.newDay();
            }
        }
        return true;
    }

    /** 一个钟点过完了：这个钟点是工作的话发工资，各家店调一次价。 */
    public static void endHour(ServerLevel level, Town town, int hour) {
        Activity activity = town.activityAt(hour);
        boolean workHour = activity == Activity.WORK;
        // 别人自由活动的钟点店员照常看店，所以商店的营业时间是“工作”加“自由”
        boolean shopHour = workHour || activity == Activity.FREE;
        for (ResidentRecord record : town.residents.values()) {
            if (record.zoneId == null || record.ageYears < ResidentEntity.ADULT_AGE) continue;
            if (workHour || (shopHour && record.job == Profession.CLERK)) {
                record.money += HOURLY_WAGE;
            }
        }
        for (Zone zone : town.zones.values()) {
            // 关门的钟点不调价，不然关门的时间会被当成“卖不动”
            if (zone.industry != Industry.SHOP || !shopHour || town.workers(zone.id).isEmpty()) continue;
            zone.shop.adjustPrices(stockOf(level, zone));
            for (ResidentRecord clerk : town.workers(zone.id)) {
                Shopping.selfServe(level, town, clerk, zone);
            }
        }
    }

    /** 这家店的货架上现在每种货有多少。 */
    public static Map<Item, Integer> stockOf(ServerLevel level, Zone zone) {
        Map<Item, Integer> stock = new HashMap<>();
        for (ShelfBlockEntity shelf : Terrain.findShelves(level, zone.rect)) {
            for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
                ItemStack stack = shelf.getItem(slot);
                if (!stack.isEmpty()) stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return stock;
    }

    /**
     * 这个居民吃的是谁家的存粮。夫妻吃丈夫名下那一份，孩子吃父母的，单身的人吃自己的。
     * 存粮记的是饱腹点数，不是具体的物品：买回家的食物在结账时就折算成点数了。
     */
    public static ResidentRecord pantryOwner(Town town, ResidentRecord record) {
        if (record.ageYears < ResidentEntity.ADULT_AGE) {
            ResidentRecord parent = record.mother == null ? null : town.residents.get(record.mother);
            if (parent == null && record.father != null) parent = town.residents.get(record.father);
            if (parent != null) record = parent;
        }
        if (!record.male && record.spouse != null) {
            ResidentRecord husband = town.residents.get(record.spouse);
            if (husband != null) return husband;
        }
        return record;
    }

    /** 吃同一份存粮的有几口人。 */
    public static int householdSize(Town town, ResidentRecord owner) {
        int size = 0;
        for (ResidentRecord record : town.residents.values()) {
            if (pantryOwner(town, record) == owner) size++;
        }
        return Math.max(1, size);
    }

    private Economy() {}
}
