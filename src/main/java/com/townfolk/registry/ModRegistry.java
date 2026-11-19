package com.townfolk.registry;

import com.townfolk.Townfolk;
import com.townfolk.block.RegisterBlock;
import com.townfolk.block.RegisterBlockEntity;
import com.townfolk.block.ShelfBlock;
import com.townfolk.block.ShelfBlockEntity;
import com.townfolk.block.TallShelfBlock;
import com.townfolk.block.TownHallBlock;
import com.townfolk.entity.ResidentEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Townfolk.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Townfolk.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Townfolk.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Townfolk.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Townfolk.MODID);

    /** 镇公所：小镇的管理方块。 */
    public static final DeferredBlock<TownHallBlock> TOWN_HALL = BLOCKS.register("town_hall",
            () -> new TownHallBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.0F, 1200.0F)
                    .sound(SoundType.STONE)
                    .pushReaction(PushReaction.BLOCK)));

    public static final DeferredItem<BlockItem> TOWN_HALL_ITEM = ITEMS.registerSimpleBlockItem("town_hall", TOWN_HALL);

    /** 三种摆货的方块：靠墙货架、柜台、集市摊位。 */
    public static final DeferredBlock<ShelfBlock> SHELF = BLOCKS.register("shelf", () -> new TallShelfBlock(shelfProperties()));
    public static final DeferredBlock<ShelfBlock> COUNTER = BLOCKS.register("counter", () -> new ShelfBlock(ShelfBlock.Kind.COUNTER, shelfProperties()));
    public static final DeferredBlock<ShelfBlock> MARKET_STALL = BLOCKS.register("market_stall", () -> new ShelfBlock(ShelfBlock.Kind.STALL, shelfProperties()));
    public static final DeferredItem<BlockItem> SHELF_ITEM = ITEMS.registerSimpleBlockItem("shelf", SHELF);
    public static final DeferredItem<BlockItem> COUNTER_ITEM = ITEMS.registerSimpleBlockItem("counter", COUNTER);
    public static final DeferredItem<BlockItem> MARKET_STALL_ITEM = ITEMS.registerSimpleBlockItem("market_stall", MARKET_STALL);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShelfBlockEntity>> SHELF_ENTITY = BLOCK_ENTITIES.register("shelf",
            () -> BlockEntityType.Builder.of(ShelfBlockEntity::new, SHELF.get(), COUNTER.get(), MARKET_STALL.get()).build(null));

    /** 收银台和金币。 */
    public static final DeferredBlock<RegisterBlock> CASH_REGISTER = BLOCKS.register("cash_register", () -> new RegisterBlock(shelfProperties()));
    public static final DeferredItem<BlockItem> CASH_REGISTER_ITEM = ITEMS.registerSimpleBlockItem("cash_register", CASH_REGISTER);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RegisterBlockEntity>> REGISTER_ENTITY = BLOCK_ENTITIES.register("cash_register",
            () -> BlockEntityType.Builder.of(RegisterBlockEntity::new, CASH_REGISTER.get()).build(null));
    public static final DeferredItem<Item> COIN = ITEMS.registerSimpleItem("coin");

    private static BlockBehaviour.Properties shelfProperties() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F).sound(SoundType.WOOD).ignitedByLava().noOcclusion().pushReaction(PushReaction.BLOCK);
    }

    public static final DeferredHolder<EntityType<?>, EntityType<ResidentEntity>> RESIDENT = ENTITIES.register("resident",
            () -> EntityType.Builder.<ResidentEntity>of(ResidentEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .clientTrackingRange(10)
                    .build("resident"));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.townfolk"))
                    .icon(() -> new ItemStack(TOWN_HALL_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(TOWN_HALL_ITEM.get());
                        output.accept(SHELF_ITEM.get());
                        output.accept(COUNTER_ITEM.get());
                        output.accept(MARKET_STALL_ITEM.get());
                        output.accept(CASH_REGISTER_ITEM.get());
                        output.accept(COIN.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }

    private ModRegistry() {}
}
