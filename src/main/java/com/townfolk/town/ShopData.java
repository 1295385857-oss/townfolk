package com.townfolk.town;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 一家商店的账本：每种货现在卖多少钱、卖了多少、有多少人想买没买到。
 * 价格由店员自己定：卖得快或者有人没买到就涨一成，积压着卖不动就降一成，镇长不用管。
 */
public class ShopData {
    /** 价格最低和最高是基准价的多少倍。 */
    public static final double MIN_FACTOR = 0.3;
    public static final double MAX_FACTOR = 5.0;
    /** 每次调价的幅度。 */
    private static final double STEP = 0.1;
    /** 架上有货却连续这么多个钟点一个都没卖出去，才开始降价。 */
    private static final int IDLE_HOURS_BEFORE_CUT = 6;

    public static class Goods {
        public double price;
        public int soldThisHour;
        public int soldToday;
        public int missedThisHour;
        public int missedToday;
        public int idleHours;
    }

    public final Map<Item, Goods> goods = new LinkedHashMap<>();

    public Goods get(Item item) {
        return goods.computeIfAbsent(item, i -> {
            Goods entry = new Goods();
            entry.price = PriceTable.base(i);
            return entry;
        });
    }

    /** 这样东西现在卖多少金币一个。 */
    public int priceOf(Item item) {
        return Math.max(1, (int) Math.round(get(item).price));
    }

    /** 现价是基准价的多少倍，客人用它来判断贵不贵。 */
    public double priceRatio(Item item) {
        return get(item).price / PriceTable.base(item);
    }

    public void recordSale(Item item, int count) {
        Goods entry = get(item);
        entry.soldThisHour += count;
        entry.soldToday += count;
    }

    /** 有人想买这类东西，但是店里没有。 */
    public void recordMiss(Item item) {
        Goods entry = get(item);
        entry.missedThisHour++;
        entry.missedToday++;
    }

    /**
     * 每个营业的钟点结束时店员调一次价。
     * @param stock 现在货架上每种货有多少
     */
    public void adjustPrices(Map<Item, Integer> stock) {
        for (Map.Entry<Item, Goods> entry : goods.entrySet()) {
            Item item = entry.getKey();
            Goods data = entry.getValue();
            int inStock = stock.getOrDefault(item, 0);
            double base = PriceTable.base(item);

            boolean sellingFast = data.soldThisHour >= Math.max(2, inStock / 4);
            if (data.missedThisHour > 0 || (sellingFast && data.soldThisHour > 0)) {
                data.price *= 1.0 + STEP;
                data.idleHours = 0;
            } else if (data.soldThisHour == 0 && inStock > 0) {
                data.idleHours++;
                if (data.idleHours >= IDLE_HOURS_BEFORE_CUT) data.price *= 1.0 - STEP;
            } else {
                data.idleHours = 0;
            }
            data.price = Mth.clamp(data.price, base * MIN_FACTOR, base * MAX_FACTOR);
            data.soldThisHour = 0;
            data.missedThisHour = 0;
        }
    }

    /** 新的一天：今天的销量和“没买到”清零。 */
    public void newDay() {
        for (Goods data : goods.values()) {
            data.soldToday = 0;
            data.missedToday = 0;
        }
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Map.Entry<Item, Goods> entry : goods.entrySet()) {
            Goods data = entry.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putString("Item", BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
            tag.putDouble("Price", data.price);
            tag.putInt("SoldHour", data.soldThisHour);
            tag.putInt("SoldToday", data.soldToday);
            tag.putInt("MissedHour", data.missedThisHour);
            tag.putInt("MissedToday", data.missedToday);
            tag.putInt("Idle", data.idleHours);
            list.add(tag);
        }
        return list;
    }

    public void load(ListTag list) {
        goods.clear();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("Item"));
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) continue;
            Goods data = new Goods();
            data.price = tag.getDouble("Price");
            data.soldThisHour = tag.getInt("SoldHour");
            data.soldToday = tag.getInt("SoldToday");
            data.missedThisHour = tag.getInt("MissedHour");
            data.missedToday = tag.getInt("MissedToday");
            data.idleHours = tag.getInt("Idle");
            goods.put(item, data);
        }
    }

    public static int listTagType() {
        return Tag.TAG_COMPOUND;
    }
}
