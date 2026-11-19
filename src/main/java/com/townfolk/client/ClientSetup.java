package com.townfolk.client;

import com.townfolk.client.dev.SmokeTest;
import com.townfolk.registry.ModRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class ClientSetup {
    public static void init(IEventBus modBus) {
        modBus.addListener(ClientSetup::registerRenderers);
        modBus.addListener(GoodsModels::register);
        GodView.init();
        if (System.getProperty("townfolk.smoke") != null) {
            SmokeTest.init();
        }
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModRegistry.RESIDENT.get(), ResidentRenderer::new);
        event.registerBlockEntityRenderer(ModRegistry.SHELF_ENTITY.get(), ShelfRenderer::new);
    }

    private ClientSetup() {}
}
