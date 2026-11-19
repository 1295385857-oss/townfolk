package com.townfolk;

import com.townfolk.client.ClientSetup;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.network.ModNetwork;
import com.townfolk.registry.ModRegistry;
import com.townfolk.town.TownTicker;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@Mod(Townfolk.MODID)
public class Townfolk {
    public static final String MODID = "townfolk";

    public Townfolk(IEventBus modBus) {
        ModRegistry.register(modBus);
        modBus.addListener(ModNetwork::register);
        modBus.addListener(Townfolk::registerAttributes);
        NeoForge.EVENT_BUS.addListener(TownTicker::onLevelTick);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModRegistry.RESIDENT.get(), ResidentEntity.createAttributes().build());
    }
}
