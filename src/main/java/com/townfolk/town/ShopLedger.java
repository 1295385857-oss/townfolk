package com.townfolk.town;

import com.townfolk.block.RegisterBlockEntity;
import com.townfolk.network.ShopDataPayload;
import com.townfolk.registry.ModRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** 收银台右键看到的账本：把一家店的现价、库存、销量整理出来发给玩家，以及取钱。 */
public final class ShopLedger {
    /** 收银台所在的小镇和商店分区。不在任何商店里时 zone 为 null。 */
    private record Location(@Nullable Town town, @Nullable Zone zone) {}

    private static Location locate(ServerLevel level, BlockPos pos) {
        for (Town town : TownSavedData.get(level).towns()) {
            Zone zone = town.zoneAt(pos.getX(), pos.getZ());
            if (zone != null) return new Location(town, zone.industry == Industry.SHOP ? zone : null);
        }
        return new Location(null, null);
    }

    public static void send(ServerLevel level, BlockPos pos, ServerPlayer player, boolean openScreen) {
        if (!(level.getBlockEntity(pos) instanceof RegisterBlockEntity register)) return;
        Location location = locate(level, pos);
        List<ShopDataPayload.Line> lines = new ArrayList<>();
        if (location.zone != null) {
            Zone zone = location.zone;
            Map<Item, Integer> stock = Economy.stockOf(level, zone);
            // 架上有的货，加上今天卖过或者有人问过的货
            Set<Item> items = new LinkedHashSet<>(stock.keySet());
            for (Map.Entry<Item, ShopData.Goods> entry : zone.shop.goods.entrySet()) {
                if (entry.getValue().soldToday > 0 || entry.getValue().missedToday > 0) items.add(entry.getKey());
            }
            for (Item item : items) {
                ShopData.Goods data = zone.shop.get(item);
                lines.add(new ShopDataPayload.Line(BuiltInRegistries.ITEM.getKey(item).toString(), zone.shop.priceOf(item),
                        PriceTable.base(item), stock.getOrDefault(item, 0), data.soldToday, data.missedToday));
            }
        }
        PacketDistributor.sendToPlayer(player, new ShopDataPayload(pos, openScreen, location.zone != null,
                register.isTended(level.getGameTime()), register.getMoney(), lines));
    }

    /** 把钱箱里的金币取给玩家。收银台在小镇里时只有镇长能取。 */
    public static void takeMoney(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!(level.getBlockEntity(pos) instanceof RegisterBlockEntity register)) return;
        Location location = locate(level, pos);
        if (location.town != null && !location.town.mayorId.equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("townfolk.message.not_your_till"), true);
            return;
        }
        giveCoins(player, register.takeAll());
    }

    /** 把若干金币放进玩家背包，放不下的掉在脚边。 */
    public static void giveCoins(ServerPlayer player, int amount) {
        while (amount > 0) {
            int count = Math.min(64, amount);
            player.getInventory().placeItemBackInInventory(new ItemStack(ModRegistry.COIN.get(), count));
            amount -= count;
        }
    }

    /** 从玩家背包里拿走最多 max 个金币，返回实际拿走的数目。 */
    public static int takeCoins(ServerPlayer player, int max) {
        int taken = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && taken < max; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(ModRegistry.COIN.get())) continue;
            int count = Math.min(stack.getCount(), max - taken);
            stack.shrink(count);
            taken += count;
        }
        return taken;
    }

    private ShopLedger() {}
}
