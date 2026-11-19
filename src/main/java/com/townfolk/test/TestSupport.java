package com.townfolk.test;

import com.townfolk.registry.ModRegistry;
import com.townfolk.town.Town;
import com.townfolk.town.TownSavedData;
import com.townfolk.town.ZoneRect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** 自动测试里搭场地用的小工具。坐标都是相对测试场地的。 */
final class TestSupport {
    /** 测试场地里地面草方块所在的高度。 */
    static final int GROUND = 1;

    /** 铺一层泥土加一层草。 */
    static void layGround(GameTestHelper helper, int size) {
        for (int x = 0; x <= size; x++) {
            for (int z = 0; z <= size; z++) {
                helper.setBlock(new BlockPos(x, GROUND - 1, z), Blocks.DIRT);
                helper.setBlock(new BlockPos(x, GROUND, z), Blocks.GRASS_BLOCK);
            }
        }
    }

    /** 放一张床，床头在 head，床尾在它南边一格。返回床头的世界坐标。 */
    static BlockPos placeBed(GameTestHelper helper, BlockPos head) {
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        helper.setBlock(head.south(), bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
        return helper.absolutePos(head);
    }

    /** 砌一圈两格高的墙并封上屋顶，围住 (x1,z1)-(x2,z2) 这块地（含墙）。 */
    static void buildShell(GameTestHelper helper, int x1, int z1, int x2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean wall = x == x1 || x == x2 || z == z1 || z == z2;
                if (wall) {
                    helper.setBlock(new BlockPos(x, GROUND + 1, z), Blocks.OAK_PLANKS);
                    helper.setBlock(new BlockPos(x, GROUND + 2, z), Blocks.OAK_PLANKS);
                }
                helper.setBlock(new BlockPos(x, GROUND + 3, z), Blocks.OAK_PLANKS);
            }
        }
    }

    /** 一道沿 z 方向的两格高的隔墙。 */
    static void buildWallZ(GameTestHelper helper, int x, int z1, int z2) {
        for (int z = z1; z <= z2; z++) {
            helper.setBlock(new BlockPos(x, GROUND + 1, z), Blocks.OAK_PLANKS);
            helper.setBlock(new BlockPos(x, GROUND + 2, z), Blocks.OAK_PLANKS);
        }
    }

    /** 在墙上开一个门洞。withDoor 为 true 时装上门。 */
    static void openDoorway(GameTestHelper helper, int x, int z, boolean withDoor) {
        BlockPos lower = new BlockPos(x, GROUND + 1, z);
        if (withDoor) {
            BlockState door = Blocks.OAK_DOOR.defaultBlockState();
            helper.setBlock(lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            helper.setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        } else {
            helper.setBlock(lower, Blocks.AIR);
            helper.setBlock(lower.above(), Blocks.AIR);
        }
    }

    static ZoneRect rect(GameTestHelper helper, int x1, int z1, int x2, int z2) {
        BlockPos a = helper.absolutePos(new BlockPos(x1, 0, z1));
        BlockPos b = helper.absolutePos(new BlockPos(x2, 0, z2));
        return ZoneRect.of(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    /** 在场地里的 pos 放下镇公所，建成小镇。 */
    @SuppressWarnings("removal")
    static Town foundTown(GameTestHelper helper, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        ServerPlayer mayor = helper.makeMockServerPlayerInLevel();
        BlockPos hall = helper.absolutePos(pos);
        level.setBlockAndUpdate(hall, ModRegistry.TOWN_HALL.get().defaultBlockState());
        ModRegistry.TOWN_HALL.get().setPlacedBy(level, hall, level.getBlockState(hall), mayor, ItemStack.EMPTY);
        Town town = TownSavedData.get(level).getTownAt(hall);
        helper.assertTrue(town != null, "放下镇公所后应该建立小镇");
        helper.assertTrue(town.mayorId.equals(mayor.getUUID()), "放置者应该是镇长");
        return town;
    }

    private TestSupport() {}
}
