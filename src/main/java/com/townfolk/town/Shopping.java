package com.townfolk.town;

import com.townfolk.block.RegisterBlockEntity;
import com.townfolk.block.ShelfBlockEntity;
import com.townfolk.entity.Profession;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ai.Terrain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 居民按需购物的决定：缺什么、去哪家店、从哪个货架拿几个。
 * 不缺东西就不进店；嫌贵就少买；买不起就先不买。
 */
public final class Shopping {
    /** 现价超过基准价的这么多倍，客人就觉得贵，只买够一天吃的。 */
    private static final double EXPENSIVE = 1.5;
    /** 一次最多拿多少件，免得一个人把店搬空。 */
    private static final int MAX_ITEMS = 32;

    /** 从某个货架上拿某样东西若干个。 */
    public record Pick(BlockPos shelf, Item item, int count) {}

    /** 一次购物的打算：去哪家店、在哪个收银台结账、依次拿什么。 */
    public record Plan(Zone shop, BlockPos register, List<Pick> picks) {}

    /** 这个人干活需要工具而手里没有。 */
    public static boolean wantsTool(ResidentRecord record) {
        return (record.job == Profession.FARMER || record.job == Profession.LUMBERJACK) && !record.hasTool;
    }

    /** 这家人还想买多少饱腹点数的食物。存粮够吃一天就不买，不够就买到够吃两天。 */
    public static int foodWanted(Town town, ResidentRecord record) {
        ResidentRecord owner = Economy.pantryOwner(town, record);
        int daily = Economy.DAILY_FOOD * Economy.householdSize(town, owner);
        return owner.pantry >= daily ? 0 : 2 * daily - owner.pantry;
    }

    /** 买吃的时候能动用的钱：自己的加上配偶的。 */
    public static int foodBudget(Town town, ResidentRecord record) {
        ResidentRecord spouse = record.spouse == null ? null : town.residents.get(record.spouse);
        return record.money + (spouse == null ? 0 : spouse.money);
    }

    /** 付钱。先花自己的；买吃的时自己的不够可以花配偶的。返回实际付了多少。 */
    public static int pay(Town town, ResidentRecord record, int amount, boolean useSpouse) {
        int paid = Math.min(amount, record.money);
        record.money -= paid;
        if (paid < amount && useSpouse && record.spouse != null) {
            ResidentRecord spouse = town.residents.get(record.spouse);
            if (spouse != null) {
                int extra = Math.min(amount - paid, spouse.money);
                spouse.money -= extra;
                paid += extra;
            }
        }
        return paid;
    }

    /**
     * 决定这次去买什么。没有需求、没有开着的店、店里没有合适的货、或者一样都买不起时返回 null。
     * @param foodAllowed 现在是不是可以去买吃的（自由活动时，或者饿着没存粮时）。买工具随时都可以。
     */
    @Nullable
    public static Plan plan(ServerLevel level, Town town, ResidentRecord record, BlockPos from, boolean foodAllowed) {
        boolean needTool = wantsTool(record);
        int needFood = foodAllowed ? foodWanted(town, record) : 0;
        if (!needTool && needFood <= 0) return null;

        // 找一家开着门的店：有店员在收银台后面
        Zone shop = null;
        BlockPos register = null;
        double best = Double.MAX_VALUE;
        long now = level.getGameTime();
        for (Zone zone : town.zones.values()) {
            if (zone.industry != Industry.SHOP) continue;
            for (RegisterBlockEntity candidate : Terrain.findRegisters(level, zone.rect)) {
                if (!candidate.isTended(now)) continue;
                double dist = candidate.getBlockPos().distSqr(from);
                if (dist < best) {
                    best = dist;
                    shop = zone;
                    register = candidate.getBlockPos();
                }
            }
        }
        if (shop == null) return null;

        // 店里每种货在哪些货架上、各有多少
        Map<Item, List<Pick>> stock = new LinkedHashMap<>();
        for (ShelfBlockEntity shelf : Terrain.findShelves(level, shop.rect)) {
            Map<Item, Integer> onShelf = new LinkedHashMap<>();
            for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
                ItemStack stack = shelf.getItem(slot);
                if (!stack.isEmpty()) onShelf.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
            for (Map.Entry<Item, Integer> entry : onShelf.entrySet()) {
                stock.computeIfAbsent(entry.getKey(), i -> new ArrayList<>()).add(new Pick(shelf.getBlockPos(), entry.getKey(), entry.getValue()));
            }
        }

        List<Pick> picks = new ArrayList<>();
        int ownMoney = record.money;
        int spent = 0;

        // 工具优先：钱够就第一时间买
        if (needTool) {
            Item cheapest = null;
            for (Item item : stock.keySet()) {
                if (!PriceTable.isToolFor(record.job, item)) continue;
                if (cheapest == null || shop.shop.priceOf(item) < shop.shop.priceOf(cheapest)) cheapest = item;
            }
            if (cheapest == null) {
                shop.shop.recordMiss(record.job == Profession.FARMER ? Items.WOODEN_HOE : Items.WOODEN_AXE);
            } else if (shop.shop.priceOf(cheapest) <= ownMoney) {
                picks.add(new Pick(stock.get(cheapest).get(0).shelf(), cheapest, 1));
                spent += shop.shop.priceOf(cheapest);
            }
        }

        if (needFood > 0) {
            List<Item> foods = new ArrayList<>();
            for (Item item : stock.keySet()) {
                if (PriceTable.nutrition(item) > 0) foods.add(item);
            }
            if (foods.isEmpty()) {
                shop.shop.recordMiss(Items.BREAD);
            } else {
                // 挑划算的：每一点饱腹花的钱越少越好
                Zone chosen = shop;
                foods.sort(Comparator.comparingDouble(item -> (double) chosen.shop.priceOf(item) / PriceTable.nutrition(item)));
                if (shop.shop.priceRatio(foods.get(0)) > EXPENSIVE) {
                    // 最划算的都嫌贵：只买够今天吃的
                    ResidentRecord owner = Economy.pantryOwner(town, record);
                    needFood = Math.max(0, Economy.DAILY_FOOD * Economy.householdSize(town, owner) - owner.pantry);
                }
                int budget = foodBudget(town, record) - spent;
                int items = 0;
                for (Item food : foods) {
                    int price = shop.shop.priceOf(food);
                    for (Pick source : stock.get(food)) {
                        int take = 0;
                        while (take < source.count() && needFood > 0 && budget >= price && items < MAX_ITEMS) {
                            take++;
                            items++;
                            budget -= price;
                            needFood -= PriceTable.nutrition(food);
                        }
                        if (take > 0) picks.add(new Pick(source.shelf(), food, take));
                    }
                }
            }
        }
        return picks.isEmpty() ? null : new Plan(shop, register, picks);
    }

    /** 从货架上拿下 pick 里说的货。不能叠的东西（工具）一次只拿一件。 */
    public static ItemStack take(ShelfBlockEntity shelf, Pick pick) {
        ItemStack taken = ItemStack.EMPTY;
        int wanted = pick.count();
        for (int slot = 0; slot < shelf.getContainerSize() && wanted > 0; slot++) {
            ItemStack stack = shelf.getItem(slot);
            if (!stack.is(pick.item())) continue;
            if (taken.isEmpty()) {
                taken = shelf.removeItem(slot, Math.min(wanted, stack.getCount()));
                wanted -= taken.getCount();
                if (!taken.isStackable()) break;
            } else if (ItemStack.isSameItemSameComponents(taken, stack)) {
                ItemStack more = shelf.removeItem(slot, Math.min(wanted, stack.getCount()));
                taken.grow(more.getCount());
                wanted -= more.getCount();
            }
        }
        return taken;
    }

    /**
     * 店员在自己看的店里买东西：他就站在收银台后面，不用排队，直接从货架拿、把钱放进钱箱。
     * 每个营业的钟点结束时看一次家里缺不缺吃的。
     */
    public static void selfServe(ServerLevel level, Town town, ResidentRecord clerk, Zone zone) {
        Plan plan = plan(level, town, clerk, zone.center(town.center.getY()), true);
        if (plan == null || plan.shop() != zone) return;
        if (!(level.getBlockEntity(plan.register()) instanceof RegisterBlockEntity register)) return;
        for (Pick pick : plan.picks()) {
            int price = zone.shop.priceOf(pick.item());
            int count = Math.min(pick.count(), foodBudget(town, clerk) / price);
            if (count <= 0 || !(level.getBlockEntity(pick.shelf()) instanceof ShelfBlockEntity shelf)) continue;
            ItemStack taken = take(shelf, new Pick(pick.shelf(), pick.item(), count));
            if (taken.isEmpty()) continue;
            register.addMoney(pay(town, clerk, price * taken.getCount(), true));
            zone.shop.recordSale(taken.getItem(), taken.getCount());
            deliver(town, clerk, null, taken);
        }
    }

    /**
     * 结账后把买到的东西交给居民：吃的折成存粮，工具拿在手里，别的放进随身物品。
     * @param resident 居民实体。店员自己买东西时没有实体在手边，传 null，这时只处理吃的。
     */
    public static void deliver(Town town, ResidentRecord record, @Nullable ResidentEntity resident, ItemStack stack) {
        int nutrition = PriceTable.nutrition(stack.getItem());
        if (nutrition > 0) {
            Economy.pantryOwner(town, record).pantry += nutrition * stack.getCount();
        } else if (resident == null) {
            return;
        } else if (PriceTable.isToolFor(record.job, stack.getItem()) && !resident.hasTool()) {
            resident.giveTool(stack);
            record.hasTool = true;
        } else {
            resident.addToInventory(stack);
        }
    }

    private Shopping() {}
}
