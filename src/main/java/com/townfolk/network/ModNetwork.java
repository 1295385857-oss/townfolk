package com.townfolk.network;

import com.townfolk.client.ClientPayloadHandler;
import com.townfolk.town.Activity;
import com.townfolk.town.Economy;
import com.townfolk.town.Industry;
import com.townfolk.town.ShopLedger;
import com.townfolk.town.Town;
import com.townfolk.town.TownManager;
import com.townfolk.town.TownSavedData;
import com.townfolk.town.TownTicker;
import com.townfolk.town.ZoneType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    /** 玩家离镇公所超过这个距离时，不接受管理操作。 */
    private static final double MAX_USE_DISTANCE = 10.0;

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("4");
        // 写成 lambda 而不是方法引用：专用服务器上不会去加载只存在于客户端的类
        registrar.playToClient(TownDataPayload.TYPE, TownDataPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandler.handleTownData(payload));
        registrar.playToServer(TownActionPayload.TYPE, TownActionPayload.STREAM_CODEC, ModNetwork::handleTownAction);
        registrar.playToServer(ZoneActionPayload.TYPE, ZoneActionPayload.STREAM_CODEC, ModNetwork::handleZoneAction);
        registrar.playToClient(ShopDataPayload.TYPE, ShopDataPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandler.handleShopData(payload));
        registrar.playToServer(ShopActionPayload.TYPE, ShopActionPayload.STREAM_CODEC, ModNetwork::handleShopAction);
    }

    /** 玩家在收银台的账本界面里刷新或者取钱。 */
    private static void handleShopAction(ShopActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > MAX_USE_DISTANCE * MAX_USE_DISTANCE) return;
        if (payload.action() == ShopActionPayload.TAKE_MONEY) {
            ShopLedger.takeMoney(level, payload.pos(), player);
        }
        ShopLedger.send(level, payload.pos(), player, false);
    }

    /** 镇长在上帝视角里建分区、删分区、选产业、定工作人数。镜头在天上，但人还站在镇公所旁边，所以照样检查距离。 */
    private static void handleZoneAction(ZoneActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        Town town = TownSavedData.get(level).getTown(payload.townId());
        if (town == null) return;
        if (player.distanceToSqr(Vec3.atCenterOf(town.center)) > MAX_USE_DISTANCE * MAX_USE_DISTANCE) return;

        if (town.mayorId.equals(player.getUUID())) {
            switch (payload.action()) {
                case ZoneActionPayload.CREATE ->
                        TownManager.createZone(level, town, ZoneType.byOrdinal(payload.value()), payload.rect());
                case ZoneActionPayload.DELETE -> TownManager.deleteZone(level, town, payload.zoneId());
                case ZoneActionPayload.SET_INDUSTRY ->
                        TownManager.setIndustry(level, town, payload.zoneId(), Industry.byOrdinal(payload.value()));
                case ZoneActionPayload.SET_WORKERS -> TownManager.setWorkers(level, town, payload.zoneId(), payload.value());
                default -> { }
            }
            // 马上按新的规划重新安排住处和工作，镇长不用等下一次例行检查
            TownTicker.tickTown(level, town);
        }
        TownManager.sendView(level, town, player, false);
    }

    private static void handleTownAction(TownActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        TownSavedData data = TownSavedData.get(level);
        Town town = data.getTown(payload.townId());
        if (town == null) return;
        if (player.distanceToSqr(Vec3.atCenterOf(town.center)) > MAX_USE_DISTANCE * MAX_USE_DISTANCE) return;

        if (town.mayorId.equals(player.getUUID())) {
            switch (payload.action()) {
                case TownActionPayload.SET_POP_CAP -> {
                    town.popCap = Mth.clamp(payload.value(), Town.MIN_POP_CAP, Town.MAX_POP_CAP);
                    data.setDirty();
                }
                case TownActionPayload.SET_SCHEDULE -> {
                    int hour = payload.value() / 16;
                    if (hour >= 0 && hour < 24) {
                        town.setActivity(hour, Activity.byOrdinal(payload.value() % 16));
                        data.setDirty();
                    }
                }
                case TownActionPayload.RESET_SCHEDULE -> {
                    town.resetSchedule();
                    data.setDirty();
                }
                case TownActionPayload.DEPOSIT -> {
                    town.treasury += ShopLedger.takeCoins(player, Integer.MAX_VALUE);
                    data.setDirty();
                }
                case TownActionPayload.WITHDRAW -> {
                    int amount = Mth.clamp(payload.value(), 0, town.treasury);
                    town.treasury -= amount;
                    ShopLedger.giveCoins(player, amount);
                    data.setDirty();
                }
                case TownActionPayload.SET_ALLOWANCE -> {
                    town.allowance = Mth.clamp(payload.value(), 0, Economy.MAX_ALLOWANCE);
                    data.setDirty();
                }
                case TownActionPayload.RENAME -> {
                    String name = payload.text().strip();
                    if (!name.isEmpty() && name.length() <= Town.MAX_NAME_LENGTH) {
                        town.name = name;
                        data.setDirty();
                    }
                }
                default -> { }
            }
        }
        TownManager.sendView(level, town, player, false);
    }

    private ModNetwork() {}
}
