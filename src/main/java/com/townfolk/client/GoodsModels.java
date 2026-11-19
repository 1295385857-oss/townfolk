package com.townfolk.client;

import com.townfolk.Townfolk;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * 货物摆在货架上时用的立体模型。
 * 原版里马铃薯、面包这些物品只是一张薄片贴图，所以这里给它们各配一个用长方体拼的模型
 * （模型文件由 tools/gen_blocks.py 生成，在 models/goods/ 下）。没有配模型的东西按原版的样子画。
 */
public final class GoodsModels {
    private static final Map<Item, ModelResourceLocation> MODELS = new HashMap<>();

    static {
        put("potato", Items.POTATO, Items.BAKED_POTATO, Items.POISONOUS_POTATO);
        put("carrot", Items.CARROT, Items.GOLDEN_CARROT);
        put("beetroot", Items.BEETROOT);
        put("wheat", Items.WHEAT);
        put("bread", Items.BREAD);
        put("apple", Items.APPLE);
        put("seeds", Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS);
        put("sapling", Items.OAK_SAPLING, Items.BIRCH_SAPLING, Items.SPRUCE_SAPLING, Items.JUNGLE_SAPLING,
                Items.ACACIA_SAPLING, Items.DARK_OAK_SAPLING, Items.CHERRY_SAPLING);
        put("sticks", Items.STICK);
    }

    private static void put(String model, Item... items) {
        ModelResourceLocation location = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "goods/" + model));
        for (Item item : items) {
            MODELS.put(item, location);
        }
    }

    /** 让游戏在加载资源时把这些模型也一起加载。 */
    static void register(ModelEvent.RegisterAdditional event) {
        for (ModelResourceLocation location : MODELS.values()) {
            event.register(location);
        }
    }

    /** 这样东西的立体模型，没有专门配的话返回 null。 */
    @Nullable
    public static BakedModel get(Item item) {
        ModelResourceLocation location = MODELS.get(item);
        if (location == null) return null;
        ModelManager manager = Minecraft.getInstance().getModelManager();
        BakedModel model = manager.getModel(location);
        return model == manager.getMissingModel() ? null : model;
    }

    private GoodsModels() {}
}
