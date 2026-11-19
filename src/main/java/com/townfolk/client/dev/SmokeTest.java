package com.townfolk.client.dev;

import com.mojang.logging.LogUtils;
import com.townfolk.client.GodView;
import com.townfolk.client.GodViewScreen;
import com.townfolk.client.PlaqueButton;
import com.townfolk.client.TownScreen;
import com.townfolk.entity.Profession;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.WorkAnim;
import com.townfolk.registry.ModRegistry;
import com.townfolk.town.Activity;
import com.townfolk.town.Demographics;
import com.townfolk.town.Home;
import com.townfolk.town.Industry;
import com.townfolk.town.ResidentRecord;
import com.townfolk.town.Town;
import com.townfolk.town.TownManager;
import com.townfolk.town.TownSavedData;
import com.townfolk.town.TownTicker;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import com.townfolk.town.ZoneType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 开发用的自动截图测试，只有用 gradlew runSmoke 启动（-Dtownfolk.smoke=true）时才生效。
 * 它会自动建一个超平坦世界、建镇、盖一栋楼和一栋独栋、划分区定人数，让居民干一阵活，
 * 再进入上帝视角看菜单、撮合一对夫妻并生一个孩子，最后看夜里睡觉。
 * 每一步的截图存到 run-smoke/screenshots，然后退出游戏。
 */
public final class SmokeTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD_NAME = "townfolk_smoke";
    private static final int UNIT_COUNT = 6;
    /** gradlew runSmokePoses：跳过小镇流程，只摆动作截图。 */
    private static final boolean POSES_ONLY = "poses".equals(System.getProperty("townfolk.smoke"));

    private static int step;
    private static int wait;
    private static int totalTicks;
    private static volatile BlockPos hallPos;
    private static volatile BlockPos registerPos;
    private static volatile ZoneRect flatsRect;
    private static volatile ZoneRect houseRect;
    /** 在上帝视角里用鼠标拖出来的分区，应该正好是这个范围。 */
    private static ZoneRect draggedRect;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(SmokeTest::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (step < 0) return;
        if (++totalTicks > 12000) {
            fail(mc, "超时，停在第 " + step + " 步", null);
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        try {
            runStep(mc);
        } catch (Throwable t) {
            fail(mc, "第 " + step + " 步出错", t);
        }
    }

    private static void runStep(Minecraft mc) {
        switch (step) {
            case 0 -> {
                if (mc.screen instanceof TitleScreen) {
                    createWorld(mc);
                    step++;
                }
            }
            case 1 -> {
                if (mc.player != null && mc.level != null && mc.screen == null) next(100);
            }
            case 2 -> {
                if (POSES_ONLY) {
                    setTime(mc, 6000L);
                    spawnPoseLine(mc);
                    wait = 40;
                    step = 31;
                    return;
                }
                setupTown(mc);
                next(60);
            }
            case 3 -> {
                sprint(mc, 1500);                         // 快进，让居民干一阵活
                next(40);
            }
            case 4 -> {
                if (!isSprinting(mc)) next(40);
            }
            case 5 -> {
                logProgress(mc);
                shot(mc, "1_first_person.png");
                wait = 10;
                step = 50;
            }
            case 50 -> {                                  // 右键收银台，看商店账本
                IntegratedServer server = server(mc);
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                    com.townfolk.town.ShopLedger.send(player.serverLevel(), registerPos, player, true);
                });
                wait = 30;
                step = 51;
            }
            case 51 -> {
                if (!(mc.screen instanceof com.townfolk.client.ShopScreen)) throw new IllegalStateException("商店账本没有打开");
                shot(mc, "1b_shop_ledger.png");
                mc.setScreen(null);
                wait = 10;
                step = 6;
            }
            case 6 -> {
                openTownHall(mc);
                next(30);
            }
            case 7 -> {
                shot(mc, "2_overview.png");
                press(mc, "townfolk.gui.tab.residents");
                next(3);
            }
            case 8 -> {                                   // 点名单第一行，看状态和住处
                TownScreen screen = (TownScreen) mc.screen;
                double x = (screen.width - TownScreen.PANEL_WIDTH) / 2.0 + 60;
                double y = (screen.height - TownScreen.PANEL_HEIGHT) / 2.0 + TownScreen.LIST_TOP + 6;
                screen.mouseClicked(x, y, 0);
                next(30);
            }
            case 9 -> {
                shot(mc, "3_residents.png");
                wait = 5;
                step = 90;
            }
            case 90 -> {
                press(mc, "townfolk.gui.tab.schedule");
                wait = 5;
                step = 91;
            }
            case 91 -> {
                ((TownScreen) mc.screen).paintHour(17, Activity.WORK);
                wait = 30;
                step = 92;
            }
            case 92 -> {
                if (town(mc).activityAt(17) != Activity.WORK) throw new IllegalStateException("在界面上改了时间表，服务端应该跟着变");
                shot(mc, "3a_schedule.png");
                ((TownScreen) mc.screen).paintHour(17, Activity.FREE);
                wait = 10;
                step = 10;
            }
            case 10 -> {
                press(mc, "townfolk.gui.tab.map");
                next(40);
            }
            case 11 -> {
                if (!(mc.screen instanceof GodViewScreen)) throw new IllegalStateException("没有进入上帝视角");
                shot(mc, "4_god_view.png");
                wait = 5;
                step = 110;
            }
            case 110 -> {                                 // 把镜头拉到农场上空最近处，看干活的动作
                GodView.pan(-16, 0);
                GodView.zoom(-30);
                wait = 40;
                step = 111;
            }
            case 111 -> {
                shot(mc, "4a_farm_close.png");
                wait = 7;
                step = 112;
            }
            case 112 -> {
                shot(mc, "4b_farm_close.png");
                GodView.pan(32, 0);
                wait = 40;
                step = 113;
            }
            case 113 -> {
                shot(mc, "4c_forest_close.png");
                GodView.pan(-34, 13);                     // 再去看商店
                wait = 40;
                step = 114;
            }
            case 114 -> {
                shot(mc, "4d_shop_close.png");
                GodView.pan(18, -13);
                GodView.zoom(22);
                wait = 30;
                step = 12;
            }
            case 12 -> {
                press(mc, "townfolk.gui.plan");
                press(mc, "zone.townfolk.agriculture");
                next(5);
            }
            case 13 -> {                                  // 在镇公所北边的空地上拖一个 13×9 的农业区
                BlockPos hall = hallPos;
                draggedRect = new ZoneRect(hall.getX() - 6, hall.getZ() - 22, hall.getX() + 6, hall.getZ() - 14);
                double[] from = screenPoint(mc, draggedRect.minX(), draggedRect.minZ());
                double[] to = screenPoint(mc, draggedRect.maxX(), draggedRect.maxZ());
                mc.screen.mouseClicked(from[0], from[1], 0);
                mc.screen.mouseDragged(to[0], to[1], 0, to[0] - from[0], to[1] - from[1]);
                mc.screen.mouseReleased(to[0], to[1], 0);
                next(40);
            }
            case 14 -> {                                  // 右键刚拖出来的分区：第一步只让选农场还是树场
                checkDraggedZone(mc);
                rightClickGround(mc, hallPos.getX(), hallPos.getZ() - 18);
                next(10);
            }
            case 15 -> {
                if (menu(mc).pressMenuItem(label("industry.townfolk.oak"))) {
                    throw new IllegalStateException("还没选用途时，菜单里不应该有具体的树种");
                }
                shot(mc, "5_step1_purpose.png");
                pressItem(mc, "townfolk.gui.group.tree");  // 选“树场”
                next(10);
            }
            case 16 -> {                                  // 第二步：列出树种，选白桦
                shot(mc, "6_step2_species.png");
                pressItem(mc, "industry.townfolk.birch");
                next(40);
            }
            case 17 -> {                                  // 新树场默认招 1 人，已经自动招到；再去种植区把人数从 2 加到 3
                Zone birch = firstZone(mc, Industry.BIRCH);
                if (!birch.rect.equals(draggedRect) || birch.workerTarget != 1) {
                    throw new IllegalStateException("选了白桦之后，新分区应该变成招 1 人的白桦树场");
                }
                // 把人数调回 0，放走刚招来的人，好让下一步的农场有人可招
                if (!menu(mc).pressMenuItem("-")) throw new IllegalStateException("减号点不了");
                closeMenu(mc);
                Zone farm = firstZone(mc, Industry.WHEAT);
                rightClickGround(mc, (farm.rect.minX() + farm.rect.maxX()) / 2, (farm.rect.minZ() + farm.rect.maxZ()) / 2);
                wait = 10;
                step = 170;
            }
            case 170 -> {
                if (!menu(mc).pressMenuItem("+")) throw new IllegalStateException("加号点不了");
                wait = 40;
                step = 171;
            }
            case 171 -> {
                Zone farm = firstZone(mc, Industry.WHEAT);
                if (farm.workerTarget != 3 || town(mc).workers(farm.id).size() != 3) {
                    throw new IllegalStateException("点加号后种植区应该是 3 人，实际目标 " + farm.workerTarget + "，到岗 " + town(mc).workers(farm.id).size());
                }
                shot(mc, "7_farm_menu.png");
                wait = 5;
                step = 18;
            }
            case 18 -> {                                  // 撮合一对夫妻并生一个孩子，然后看楼房和独栋的菜单
                closeMenu(mc);
                makeFamily(mc);
                next(60);
            }
            case 19 -> {
                GodView.pan(0, 14);
                GodView.zoom(-10);
                next(30);
            }
            case 20 -> {
                rightClickGround(mc, flatsRect.minX() + 1, flatsRect.maxZ());
                next(10);
            }
            case 21 -> {
                shot(mc, "8_apartment_menu.png");
                next(5);
            }
            case 22 -> {
                closeMenu(mc);
                rightClickGround(mc, houseRect.minX(), houseRect.maxZ());
                next(10);
            }
            case 23 -> {
                shot(mc, "9_house_menu.png");
                next(5);
            }
            case 24 -> {                                  // 天黑，快进到大家都睡下
                closeMenu(mc);
                mc.setScreen(null);
                setTime(mc, 14500L);
                sprint(mc, 800);
                next(40);
            }
            case 25 -> {
                if (!isSprinting(mc)) next(40);
            }
            case 26 -> {
                checkEveryoneAsleep(mc);
                openTownHall(mc);
                next(30);
            }
            case 27 -> {
                press(mc, "townfolk.gui.tab.map");
                next(30);
            }
            case 28 -> {                                  // 把镜头移到居民区上空并拉近
                GodView.pan(0, 15);
                GodView.zoom(-14);
                next(30);
            }
            case 29 -> {
                shot(mc, "10_night_homes.png");
                next(10);
            }
            case 30 -> {                                  // 动作展示：六个人在镜头前各摆一个干活的动作
                mc.setScreen(null);
                setTime(mc, 6000L);
                spawnPoseLine(mc);
                next(40);
            }
            case 31 -> {
                shot(mc, "11_poses_a.png");
                next(4);
            }
            case 32 -> {
                shot(mc, "12_poses_b.png");
                next(5);
            }
            case 33 -> {
                shot(mc, "13_poses_c.png");
                next(5);
            }
            case 34 -> {                                  // 换个地方，从侧面看一个人走过去
                spawnWalker(mc);
                wait = 8;
                step = 340;
            }
            case 340 -> {                                 // 等他站稳了再让他走，不然寻路会失败
                startWalker(mc);
                wait = 24;
                step = 35;
            }
            case 35 -> {
                shot(mc, "14_walk_a.png");
                next(4);
            }
            case 36 -> {
                shot(mc, "15_walk_b.png");
                next(4);
            }
            case 37 -> {
                shot(mc, "16_walk_c.png");
                next(5);
            }
            case 38 -> {                                  // 三种货架，摆上货
                spawnShelves(mc);
                next(30);
            }
            case 39 -> {
                shot(mc, "17_shelves.png");
                next(3);
            }
            case 40 -> {                                  // 再从高处往下看一张，看布棚的顶和柜台上的货
                IntegratedServer server = server(mc);
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.connection.teleport(player.getX(), player.getY() + 2.6, player.getZ() + 0.6, 0.0F, 48.0F);
                });
                next(25);
            }
            case 41 -> {
                shot(mc, "18_shelves_top.png");
                next(10);
            }
            default -> {
                LOGGER.info("SMOKE TEST DONE");
                step = -1;
                mc.stop();
            }
        }
    }

    private static void next(int waitTicks) {
        step++;
        wait = waitTicks;
    }

    private static void fail(Minecraft mc, String message, Throwable error) {
        LOGGER.error("SMOKE TEST FAILED: {}", message, error);
        step = -1;
        mc.stop();
    }

    // ------------------------------------------------------------ 准备世界

    private static void createWorld(Minecraft mc) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, new WorldOptions(42L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                mc.screen);
    }

    private static IntegratedServer server(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) throw new IllegalStateException("没有单人游戏服务器");
        return server;
    }

    /**
     * 建镇：西边一块麦田（要 2 人），东边一片林场（要 2 人）；
     * 南边一栋六套房的楼和一栋三张床的独栋，屋顶用玻璃，方便从天上看到里面。
     */
    private static void setupTown(Minecraft mc) {
        IntegratedServer server = server(mc);
        server.execute(() -> {
            Demographics.autoEvents = false;        // 婚事和出生由下面的脚本决定，保证每次截图一样
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            ServerLevel level = player.serverLevel();
            level.setDayTime(1000L);
            BlockPos base = player.blockPosition();
            BlockPos hall = base.offset(0, 0, 7);
            int y = base.getY();
            player.connection.teleport(base.getX() + 0.5, y, base.getZ() + 0.5, 0.0F, 8.0F);

            level.setBlockAndUpdate(hall, ModRegistry.TOWN_HALL.get().defaultBlockState());
            ModRegistry.TOWN_HALL.get().setPlacedBy(level, hall, level.getBlockState(hall), player, ItemStack.EMPTY);
            Town town = TownSavedData.get(level).getTownAt(hall);
            if (town == null) throw new IllegalStateException("没有建成小镇");

            ZoneRect farm = new ZoneRect(hall.getX() - 24, hall.getZ() - 9, hall.getX() - 8, hall.getZ() + 7);
            ZoneRect forest = new ZoneRect(hall.getX() + 8, hall.getZ() - 9, hall.getX() + 24, hall.getZ() + 7);
            ZoneRect flats = new ZoneRect(hall.getX() - 12, hall.getZ() + 10, hall.getX() + 8, hall.getZ() + 16);
            ZoneRect house = new ZoneRect(hall.getX() + 11, hall.getZ() + 10, hall.getX() + 17, hall.getZ() + 16);
            require(TownManager.createZone(level, town, ZoneType.AGRICULTURE, farm));
            require(TownManager.createZone(level, town, ZoneType.AGRICULTURE, forest));
            require(TownManager.createZone(level, town, ZoneType.RESIDENTIAL, flats));
            require(TownManager.createZone(level, town, ZoneType.RESIDENTIAL, house));
            Zone farmZone = town.zoneAt(farm.minX(), farm.minZ());
            Zone forestZone = town.zoneAt(forest.minX(), forest.minZ());
            TownManager.setIndustry(level, town, farmZone.id, Industry.WHEAT);
            TownManager.setIndustry(level, town, forestZone.id, Industry.OAK);
            TownManager.setWorkers(level, town, farmZone.id, 2);
            TownManager.setWorkers(level, town, forestZone.id, 2);
            TownManager.setIndustry(level, town, town.zoneAt(flats.minX(), flats.minZ()).id, Industry.APARTMENT);
            TownManager.setIndustry(level, town, town.zoneAt(house.minX(), house.minZ()).id, Industry.DETACHED);

            level.setBlockAndUpdate(new BlockPos(farm.maxX(), y, farm.maxZ()), Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(forest.minX(), y, forest.maxZ()), Blocks.CHEST.defaultBlockState());
            var oak = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getHolderOrThrow(TreeFeatures.OAK).value();
            int[][] trees = {{3, 3}, {9, 2}, {13, 7}, {5, 10}, {11, 13}, {2, 14}};
            for (int[] tree : trees) {
                oak.place(level, level.getChunkSource().getGenerator(), level.random, new BlockPos(forest.minX() + tree[0], y, forest.minZ() + tree[1]));
            }

            // 楼：六套房一字排开，共用隔墙，每套朝北开一扇门。前两套各两张床，其余各一张
            for (int i = 0; i < UNIT_COUNT; i++) {
                int x0 = flats.minX() + 1 + i * 3;
                buildRoom(level, x0, flats.minZ() + 1, 4, 5, y, x0 + 1);
                placeBed(level, new BlockPos(x0 + 2, y, flats.minZ() + 4));
                if (i < 2) placeBed(level, new BlockPos(x0 + 1, y, flats.minZ() + 4));
            }
            // 独栋：一间屋，三张床
            buildRoom(level, house.minX() + 1, house.minZ() + 1, 5, 5, y, house.minX() + 3);
            for (int i = 0; i < 3; i++) {
                placeBed(level, new BlockPos(house.minX() + 2 + i, y, house.minZ() + 4));
            }

            // 商店：在楼的西边。北边一排摆货的（货架、柜台、摊位），南边是收银台，客人在收银台南面排队
            ZoneRect shop = new ZoneRect(hall.getX() - 22, hall.getZ() + 10, hall.getX() - 14, hall.getZ() + 16);
            require(TownManager.createZone(level, town, ZoneType.COMMERCIAL, shop));
            TownManager.setIndustry(level, town, town.zoneAt(shop.minX(), shop.minZ()).id, Industry.SHOP);
            int sx = shop.minX();
            int sz = shop.minZ();
            placeShelf(level, new BlockPos(sx + 1, y, sz + 1), ModRegistry.SHELF.get(), Direction.SOUTH,
                    new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.STONE_AXE));
            placeShelf(level, new BlockPos(sx + 3, y, sz + 1), ModRegistry.SHELF.get(), Direction.SOUTH,
                    new ItemStack(Items.BREAD, 40), new ItemStack(Items.APPLE, 12));
            placeShelf(level, new BlockPos(sx + 5, y, sz + 1), ModRegistry.COUNTER.get(), Direction.SOUTH,
                    new ItemStack(Items.CARROT, 40), new ItemStack(Items.POTATO, 30));
            placeShelf(level, new BlockPos(sx + 7, y, sz + 1), ModRegistry.MARKET_STALL.get(), Direction.SOUTH,
                    new ItemStack(Items.BAKED_POTATO, 20), new ItemStack(Items.BEETROOT, 16));
            BlockPos register = new BlockPos(sx + 4, y, sz + 4);
            level.setBlockAndUpdate(register, ModRegistry.CASH_REGISTER.get().defaultBlockState()
                    .setValue(com.townfolk.block.RegisterBlock.FACING, Direction.SOUTH));
            registerPos = register;

            TownTicker.tickTown(level, town);
            hallPos = hall;
            flatsRect = flats;
            houseRect = house;
        });
    }

    /** 盖一间屋：木板墙两格高，玻璃屋顶，北墙上在 doorX 处开一扇门。 */
    private static void buildRoom(ServerLevel level, int x0, int z0, int width, int depth, int y, int doorX) {
        for (int x = x0; x < x0 + width; x++) {
            for (int z = z0; z < z0 + depth; z++) {
                boolean wall = x == x0 || x == x0 + width - 1 || z == z0 || z == z0 + depth - 1;
                if (wall) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.OAK_PLANKS.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, y + 1, z), Blocks.OAK_PLANKS.defaultBlockState());
                }
                level.setBlockAndUpdate(new BlockPos(x, y + 2, z), Blocks.GLASS.defaultBlockState());
            }
        }
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(new BlockPos(doorX, y, z0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        level.setBlockAndUpdate(new BlockPos(doorX, y + 1, z0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** 放一张床，床头在 head，床尾在它北边一格。 */
    private static void placeBed(ServerLevel level, BlockPos head) {
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(head.north(), bed.setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
    }

    private static void require(String problem) {
        if (problem != null) throw new IllegalStateException("建分区失败：" + problem);
    }

    /** 让一对开局居民结婚并生一个孩子，检查他们搬进了独栋。 */
    private static void makeFamily(Minecraft mc) {
        IntegratedServer server = server(mc);
        server.execute(() -> {
            ServerLevel level = server.getPlayerList().getPlayers().get(0).serverLevel();
            Town town = TownSavedData.get(level).getTownAt(hallPos);
            ResidentRecord man = null;
            ResidentRecord woman = null;
            for (ResidentRecord record : town.residents.values()) {
                if (record.male && man == null) man = record;
                if (!record.male && woman == null) woman = record;
            }
            Demographics.marry(level, town, man, woman);
            TownTicker.tickTown(level, town);
            Home home = town.homeOf(man);
            if (home == null || !home.detached || town.homeOf(woman) != home) {
                LOGGER.error("SMOKE CHECK FAILED: 夫妻没有一起搬进独栋");
                return;
            }
            ResidentEntity baby = Demographics.birth(level, town, woman);
            if (baby == null) {
                LOGGER.error("SMOKE CHECK FAILED: 没有生出孩子");
                return;
            }
            baby.setAgeYears(6);
            TownTicker.tickTown(level, town);
            LOGGER.info("家庭：{} 和 {} 住进独栋，孩子 {}", man.name, woman.name, baby.getFullName());
        });
    }

    /** 左起：锄地、砍树、蹲下播种、挖坑、往箱子里放东西、吃东西、坐着休息、聊天。最左边还有一个睡觉的。 */
    private static void spawnPoseLine(Minecraft mc) {
        IntegratedServer server = server(mc);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            ServerLevel level = player.serverLevel();
            WorkAnim[] anims = {WorkAnim.HOE, WorkAnim.CHOP, WorkAnim.BEND, WorkAnim.DIG, WorkAnim.STORE, WorkAnim.EAT, WorkAnim.SIT, WorkAnim.TALK};
            Profession[] jobs = {Profession.FARMER, Profession.LUMBERJACK, Profession.FARMER, Profession.FARMER, Profession.LUMBERJACK,
                    Profession.UNEMPLOYED, Profession.FARMER, Profession.UNEMPLOYED};
            ItemStack[] items = {ItemStack.EMPTY, ItemStack.EMPTY, new ItemStack(Items.WHEAT_SEEDS), new ItemStack(Items.IRON_SHOVEL),
                    new ItemStack(Items.OAK_LOG), new ItemStack(Items.BREAD), ItemStack.EMPTY, ItemStack.EMPTY};
            // 站到一块空地上，面朝南
            double px = player.getX() - 40.0;
            double pz = player.getZ() - 30.0;
            player.connection.teleport(px, player.getY(), pz, 0.0F, 12.0F);
            for (int i = 0; i < anims.length; i++) {
                ResidentEntity resident = ModRegistry.RESIDENT.get().create(level);
                resident.initResident("动作" + (i + 1), i % 2 == 0, 25, i % ResidentEntity.VARIANT_COUNT);
                resident.setProfession(jobs[i]);
                resident.setNoAi(true);
                resident.moveTo(px + (3.5 - i) * 1.35, player.getY(), pz + 5.0, 180.0F, 0.0F);
                resident.setYHeadRot(180.0F);
                resident.setYBodyRot(180.0F);
                level.addFreshEntity(resident);
                resident.showInHand(items[i]);
                resident.setWorkAnim(anims[i]);
            }
            // 最左边再放一张床，躺一个睡着的人
            BlockPos bedHead = BlockPos.containing(px + 7.0, player.getY(), pz + 6.0);
            placeBed(level, bedHead);
            ResidentEntity sleeper = ModRegistry.RESIDENT.get().create(level);
            sleeper.initResident("睡觉", true, 25, 0);
            sleeper.setNoAi(true);
            sleeper.moveTo(bedHead.getX() + 0.5, player.getY(), bedHead.getZ() + 0.5, 0.0F, 0.0F);
            level.addFreshEntity(sleeper);
            sleeper.startSleeping(bedHead);
        });
    }

    /** 面前摆一排：集市摊位、柜台、货架，各装几种货；后面再放一个转过 90 度的货架检查朝向。 */
    private static void spawnShelves(Minecraft mc) {
        IntegratedServer server = server(mc);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            ServerLevel level = player.serverLevel();
            double px = player.getX();
            double pz = player.getZ() - 20.0;
            player.connection.teleport(px, player.getY(), pz, 0.0F, 22.0F);
            BlockPos base = BlockPos.containing(px, player.getY(), pz + 3.0);

            placeShelf(level, base.offset(2, 0, 0), ModRegistry.MARKET_STALL.get(), Direction.NORTH,
                    new ItemStack(Items.BREAD, 30), new ItemStack(Items.APPLE, 10), new ItemStack(Items.WHEAT_SEEDS, 5), new ItemStack(Items.OAK_SAPLING, 2));
            placeShelf(level, base, ModRegistry.COUNTER.get(), Direction.NORTH,
                    new ItemStack(Items.CARROT, 30), new ItemStack(Items.BEETROOT, 9), new ItemStack(Items.OAK_LOG, 30), new ItemStack(Items.STICK, 10));
            placeShelf(level, base.offset(-2, 0, 0), ModRegistry.SHELF.get(), Direction.NORTH,
                    new ItemStack(Items.POTATO, 30), new ItemStack(Items.BREAD, 12), new ItemStack(Items.WHEAT, 40), new ItemStack(Items.APPLE, 3),
                    new ItemStack(Items.CARROT, 20), new ItemStack(Items.BEETROOT, 10), new ItemStack(Items.OAK_LOG, 30), new ItemStack(Items.STICK, 9));
            placeShelf(level, base.offset(-4, 0, 1), ModRegistry.SHELF.get(), Direction.EAST,
                    new ItemStack(Items.IRON_HOE, 1), new ItemStack(Items.POTATO, 2), new ItemStack(Items.COBBLESTONE, 30));
        });
    }

    private static void placeShelf(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block, Direction facing, ItemStack... goods) {
        if (block instanceof com.townfolk.block.TallShelfBlock tall) {
            tall.placeWhole(level, pos, facing);
        } else {
            level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(com.townfolk.block.ShelfBlock.FACING, facing));
        }
        if (level.getBlockEntity(pos) instanceof com.townfolk.block.ShelfBlockEntity shelf) {
            for (int i = 0; i < goods.length; i++) {
                shelf.setItem(i, goods[i]);
            }
        }
    }

    private static volatile ResidentEntity walkerRef;

    private static void startWalker(Minecraft mc) {
        IntegratedServer server = server(mc);
        server.execute(() -> {
            ResidentEntity walker = walkerRef;
            if (walker == null || !walker.getNavigation().moveTo(walker.getX() - 18.0, walker.getY(), walker.getZ(), 1.0)) {
                LOGGER.error("SMOKE CHECK FAILED: 走路的人没有走起来");
            }
        });
    }

    /** 让一个人从镜头前横着走过，另一个人站着不动作对照。 */
    private static void spawnWalker(Minecraft mc) {
        IntegratedServer server = server(mc);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            ServerLevel level = player.serverLevel();
            double px = player.getX();
            double pz = player.getZ() - 20.0;
            player.connection.teleport(px, player.getY(), pz, 0.0F, 10.0F);

            ResidentEntity walker = ModRegistry.RESIDENT.get().create(level);
            walker.initResident("走路", true, 25, 1);
            walker.setProfession(Profession.LUMBERJACK);
            walker.moveTo(px + 8.0, player.getY(), pz + 4.5, 90.0F, 0.0F);
            level.addFreshEntity(walker);
            walkerRef = walker;

            ResidentEntity idle = ModRegistry.RESIDENT.get().create(level);
            idle.initResident("站着", false, 25, 2);
            idle.setNoAi(true);
            idle.moveTo(px - 2.5, player.getY(), pz + 6.0, 180.0F, 0.0F);
            idle.setYHeadRot(180.0F);
            idle.setYBodyRot(180.0F);
            level.addFreshEntity(idle);
        });
    }

    private static void checkEveryoneAsleep(Minecraft mc) {
        IntegratedServer server = server(mc);
        server.execute(() -> {
            ServerLevel level = server.getPlayerList().getPlayers().get(0).serverLevel();
            Town town = TownSavedData.get(level).getTownAt(hallPos);
            int asleep = 0;
            for (ResidentRecord record : town.residents.values()) {
                if (level.getEntity(record.id) instanceof ResidentEntity resident && resident.isSleeping()) asleep++;
                else LOGGER.info("没睡着：{} 状态={} 床={}", record.name, record.status, record.bedPos);
            }
            LOGGER.info("夜里睡着的人数：{} / {}", asleep, town.residents.size());
        });
    }

    private static void sprint(Minecraft mc, int ticks) {
        IntegratedServer server = server(mc);
        server.execute(() -> server.tickRateManager().requestGameToSprint(ticks));
    }

    private static boolean isSprinting(Minecraft mc) {
        return server(mc).tickRateManager().isSprinting();
    }

    private static void setTime(Minecraft mc, long time) {
        IntegratedServer server = server(mc);
        server.execute(() -> server.getPlayerList().getPlayers().get(0).serverLevel().setDayTime(time));
    }

    private static void openTownHall(Minecraft mc) {
        IntegratedServer server = server(mc);
        BlockPos pos = hallPos;
        if (pos == null) throw new IllegalStateException("镇公所还没放好");
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            ServerLevel level = player.serverLevel();
            level.getBlockState(pos).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
        });
    }

    // ------------------------------------------------------------ 操作界面

    /** 按下当前界面里文字是这个翻译键的按钮。 */
    private static void press(Minecraft mc, String translationKey) {
        Screen screen = mc.screen;
        if (screen == null) throw new IllegalStateException("没有打开的界面");
        String label = Component.translatable(translationKey).getString();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof PlaqueButton button && button.getMessage().getString().equals(label)) {
                if (!button.active) throw new IllegalStateException("按钮不可用：" + label);
                button.onPress();
                return;
            }
        }
        throw new IllegalStateException("找不到按钮：" + label);
    }

    /** 世界里某一格地面的中心在屏幕上的位置。 */
    private static double[] screenPoint(Minecraft mc, int x, int z) {
        Screen screen = mc.screen;
        if (!(screen instanceof GodViewScreen)) throw new IllegalStateException("不在上帝视角里");
        double y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        float[] point = GodView.project(x + 0.5, y, z + 0.5, screen.width, screen.height);
        if (point == null) throw new IllegalStateException("这一格不在画面里：" + x + ", " + z);
        return new double[] {point[0], point[1]};
    }

    private static void rightClickGround(Minecraft mc, int x, int z) {
        double[] at = screenPoint(mc, x, z);
        mc.screen.mouseClicked(at[0], at[1], 1);
    }

    /** 点菜单外面的空白处，把菜单关掉。 */
    private static void closeMenu(Minecraft mc) {
        if (mc.screen instanceof GodViewScreen) mc.screen.mouseClicked(3, 40, 0);
    }

    private static GodViewScreen menu(Minecraft mc) {
        if (!(mc.screen instanceof GodViewScreen screen)) throw new IllegalStateException("不在上帝视角里");
        return screen;
    }

    private static String label(String translationKey) {
        return Component.translatable(translationKey).getString();
    }

    /** 点右键菜单里文字是这个翻译键的那一项。 */
    private static void pressItem(Minecraft mc, String translationKey) {
        if (!menu(mc).pressMenuItem(label(translationKey))) {
            throw new IllegalStateException("菜单里点不了：" + label(translationKey));
        }
    }

    private static Town town(Minecraft mc) {
        ServerLevel level = server(mc).getPlayerList().getPlayers().get(0).serverLevel();
        Town town = TownSavedData.get(level).getTownAt(hallPos);
        if (town == null) throw new IllegalStateException("小镇不见了");
        return town;
    }

    private static Zone firstZone(Minecraft mc, Industry industry) {
        for (Zone zone : town(mc).zones.values()) {
            if (zone.industry == industry) return zone;
        }
        throw new IllegalStateException("没有这个产业的分区：" + industry);
    }

    /** 用鼠标拖出来的分区，范围应该和预想的一格不差。这同时检验了“屏幕坐标 ↔ 地面格子”的换算。 */
    private static void checkDraggedZone(Minecraft mc) {
        Zone zone = town(mc).zoneAt(hallPos.getX(), hallPos.getZ() - 18);
        if (zone == null || zone.type != ZoneType.AGRICULTURE) throw new IllegalStateException("拖动之后没有建出农业区");
        if (!zone.rect.equals(draggedRect)) {
            throw new IllegalStateException("拖出的分区是 " + zone.rect + "，应该是 " + draggedRect);
        }
        LOGGER.info("拖出的分区范围正确：{}", zone.rect);
    }

    /** 把居民干活和住房的情况写进日志，方便核对。 */
    private static void logProgress(Minecraft mc) {
        IntegratedServer server = server(mc);
        server.execute(() -> {
            ServerLevel level = server.getPlayerList().getPlayers().get(0).serverLevel();
            Town town = TownSavedData.get(level).getTownAt(hallPos);
            int y = hallPos.getY();
            for (Zone zone : town.zones.values()) {
                if (!zone.industry.hasWorkers()) continue;
                int farmland = 0, crops = 0, logs = 0, saplings = 0, water = 0;
                for (int x = zone.rect.minX(); x <= zone.rect.maxX(); x++) {
                    for (int z = zone.rect.minZ(); z <= zone.rect.maxZ(); z++) {
                        if (level.getBlockState(new BlockPos(x, y - 1, z)).is(Blocks.FARMLAND)) farmland++;
                        if (level.getFluidState(new BlockPos(x, y - 1, z)).isSource()) water++;
                        if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.WHEAT)) crops++;
                        if (level.getBlockState(new BlockPos(x, y, z)).is(net.minecraft.tags.BlockTags.SAPLINGS)) saplings++;
                        for (int dy = 0; dy < 12; dy++) {
                            if (level.getBlockState(new BlockPos(x, y + dy, z)).is(net.minecraft.tags.BlockTags.LOGS)) logs++;
                        }
                    }
                }
                LOGGER.info("进度 {} 目标{}人 到岗{}人: 耕地={} 水坑={} 小麦={} 剩余原木={} 树苗={}", zone.industry, zone.workerTarget,
                        town.workers(zone.id).size(), farmland, water, crops, logs, saplings);
            }
            int flats = 0;
            int houses = 0;
            for (Home home : town.homes) {
                if (home.detached) houses++;
                else flats++;
            }
            LOGGER.info("住房：楼房 {} 户，独栋 {} 户，床位 {}", flats, houses, town.bedCount());
            for (ResidentRecord record : town.residents.values()) {
                Home home = town.homeOf(record);
                LOGGER.info("居民 {} {} 状态={} 住处={} 金币={} 存粮={} 饱食={} 有工具={}", record.name, record.job, record.status,
                        home == null ? "无" : home.detached ? "独栋" : "楼房", record.money,
                        com.townfolk.town.Economy.pantryOwner(town, record).pantry, record.satiety, record.hasTool);
            }
            if (level.getBlockEntity(registerPos) instanceof com.townfolk.block.RegisterBlockEntity till) {
                LOGGER.info("商店：钱箱 {} 金币，金库 {} 金币", till.getMoney(), town.treasury);
            }
        });
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> LOGGER.info("截图：{}", message.getString()));
    }

    private SmokeTest() {}
}
