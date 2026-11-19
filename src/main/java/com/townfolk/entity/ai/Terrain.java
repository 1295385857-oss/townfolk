package com.townfolk.entity.ai;

import com.townfolk.block.RegisterBlockEntity;
import com.townfolk.block.ShelfBlockEntity;
import com.townfolk.town.ZoneRect;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** 居民干活时查看地形用的工具方法。分区只有平面范围，高度一律跟着地表走。 */
public final class Terrain {

    /** 这一列的地面高度：最上面一个实心方块的 Y，树干不算地面。 */
    public static int groundY(Level level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        while (y > min && level.getBlockState(pos.setY(y)).is(BlockTags.LOGS)) {
            y--;
        }
        return y;
    }

    public static boolean isLoaded(Level level, int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }

    public static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    /** 地面上方这一格是空的，或者只是可以随手清掉的花草。 */
    public static boolean isClear(BlockState state) {
        if (state.isAir()) return true;
        if (!state.getFluidState().isEmpty()) return false;
        return state.canBeReplaced() || state.is(BlockTags.FLOWERS);
    }

    /** 能用锄头开垦成耕地的方块。 */
    public static boolean isTillable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.COARSE_DIRT);
    }

    /** 和原版耕地判断湿润的规则一样：水平 4 格以内、同一层或上一层有水。 */
    public static boolean hasWaterNearby(Level level, int x, int groundY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (isWater(level.getBlockState(pos.set(x + dx, groundY + dy, z + dz)))) return true;
                }
            }
        }
        return false;
    }

    /** 分区范围内的箱子和木桶。 */
    public static List<BlockPos> findStorage(ServerLevel level, ZoneRect rect) {
        List<BlockPos> result = new ArrayList<>();
        for (int cx = rect.minX() >> 4; cx <= rect.maxX() >> 4; cx++) {
            for (int cz = rect.minZ() >> 4; cz <= rect.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof ChestBlockEntity) && !(blockEntity instanceof BarrelBlockEntity)) continue;
                    BlockPos pos = blockEntity.getBlockPos();
                    if (rect.contains(pos.getX(), pos.getZ())) result.add(pos);
                }
            }
        }
        return result;
    }

    /** 分区范围内的货架、柜台、摊位。 */
    public static List<ShelfBlockEntity> findShelves(ServerLevel level, ZoneRect rect) {
        return findBlockEntities(level, rect, ShelfBlockEntity.class);
    }

    /** 分区范围内的收银台。 */
    public static List<RegisterBlockEntity> findRegisters(ServerLevel level, ZoneRect rect) {
        return findBlockEntities(level, rect, RegisterBlockEntity.class);
    }

    private static <T extends BlockEntity> List<T> findBlockEntities(ServerLevel level, ZoneRect rect, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (int cx = rect.minX() >> 4; cx <= rect.maxX() >> 4; cx++) {
            for (int cz = rect.minZ() >> 4; cz <= rect.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (type.isInstance(blockEntity) && rect.contains(pos.getX(), pos.getZ())) result.add(type.cast(blockEntity));
                }
            }
        }
        return result;
    }

    private Terrain() {}
}
