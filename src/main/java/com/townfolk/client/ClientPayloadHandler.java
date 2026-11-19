package com.townfolk.client;

import com.townfolk.network.ShopDataPayload;
import com.townfolk.network.TownDataPayload;
import net.minecraft.client.Minecraft;

/** 只在客户端加载的网络处理。 */
public final class ClientPayloadHandler {
    public static void handleTownData(TownDataPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownScreen screen && screen.townId().equals(payload.view().id())) {
            screen.updateView(payload.view());
        } else if (minecraft.screen instanceof GodViewScreen godView && godView.townId().equals(payload.view().id())) {
            godView.updateView(payload.view());
        } else if (payload.openScreen()) {
            minecraft.setScreen(new TownScreen(payload.view()));
        }
    }

    public static void handleShopData(ShopDataPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ShopScreen screen && screen.pos().equals(payload.pos())) {
            screen.update(payload);
        } else if (payload.openScreen()) {
            minecraft.setScreen(new ShopScreen(payload));
        }
    }

    private ClientPayloadHandler() {}
}
