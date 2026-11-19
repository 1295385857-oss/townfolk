package com.townfolk.town;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;

/**
 * 基准价（单位：金币）。它只是店员定价的起点和上下限的参照，实际卖多少由店员按销路自己调。
 * 这里还放着“什么算吃的、什么算工具”这类和买卖有关的常识。
 */
public final class PriceTable {
    private static final Map<Item, Integer> BASE = new HashMap<>();

    static {
        put(2, Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT);
        put(1, Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.STICK);
        put(2, Items.OAK_SAPLING, Items.BIRCH_SAPLING, Items.SPRUCE_SAPLING);
        put(3, Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG);
        put(4, Items.APPLE);
        put(5, Items.BAKED_POTATO);
        put(6, Items.BREAD);
    }

    private static void put(int price, Item... items) {
        for (Item item : items) {
            BASE.put(item, price);
        }
    }

    public static int base(Item item) {
        Integer listed = BASE.get(item);
        if (listed != null) return listed;
        if (item instanceof TieredItem tiered) {
            if (tiered.getTier() == Tiers.WOOD) return 4;
            if (tiered.getTier() == Tiers.STONE) return 8;
            if (tiered.getTier() == Tiers.IRON) return 20;
            if (tiered.getTier() == Tiers.GOLD) return 25;
            if (tiered.getTier() == Tiers.DIAMOND) return 80;
            return 200;
        }
        int nutrition = nutrition(item);
        return nutrition > 0 ? nutrition + 1 : 2;
    }

    /** 这样东西能顶多少饱（原版的饥饿值）。不能吃、或者吃了有坏处的东西返回 0。 */
    public static int nutrition(Item item) {
        FoodProperties food = new ItemStack(item).get(DataComponents.FOOD);
        if (food == null || !food.effects().isEmpty()) return 0;
        return food.nutrition();
    }

    /** 这样东西是不是这个职业干活用的工具。 */
    public static boolean isToolFor(com.townfolk.entity.Profession profession, Item item) {
        return switch (profession) {
            case FARMER -> item instanceof HoeItem;
            case LUMBERJACK -> item instanceof AxeItem;
            default -> false;
        };
    }

    private PriceTable() {}
}
