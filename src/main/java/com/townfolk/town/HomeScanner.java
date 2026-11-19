package com.townfolk.town;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 从世界里认出居民区的住户。
 * 独栋：镇长画的整个分区就是一栋，里面所有的床属于同一户。
 * 楼房：被墙和门围起来、里面有床的房间是一户；没有床的楼道、楼梯间不算。
 */
public final class HomeScanner {
    /** 一套房最多往外找多少格，防止没封好的房间一路找到很远。 */
    private static final int MAX_CELLS = 800;

    /** 认出这个居民区里的住户。分区所在的区块没有全部加载时返回 null，表示这次认不了。 */
    @Nullable
    public static List<Home> scan(ServerLevel level, Zone zone) {
        ZoneRect rect = zone.rect;
        for (int cx = rect.minX() >> 4; cx <= rect.maxX() >> 4; cx++) {
            for (int cz = rect.minZ() >> 4; cz <= rect.maxZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return null;
            }
        }

        List<BlockPos> beds = new ArrayList<>();
        PoiManager poi = level.getPoiManager();
        for (int cx = rect.minX() >> 4; cx <= rect.maxX() >> 4; cx++) {
            for (int cz = rect.minZ() >> 4; cz <= rect.maxZ() >> 4; cz++) {
                poi.getInChunk(holder -> holder.is(PoiTypes.HOME), new ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
                        .map(PoiRecord::getPos)
                        .filter(pos -> rect.contains(pos.getX(), pos.getZ()) && isBedHead(level.getBlockState(pos)))
                        .forEach(pos -> beds.add(pos.immutable()));
            }
        }
        Collections.sort(beds);

        List<Home> homes = new ArrayList<>();
        if (beds.isEmpty()) return homes;
        if (zone.industry == Industry.DETACHED) {
            homes.add(new Home(zone.id, true, beds));
            return homes;
        }

        Set<BlockPos> known = new HashSet<>(beds);
        Set<BlockPos> assigned = new HashSet<>();
        for (BlockPos bed : beds) {
            if (assigned.contains(bed)) continue;
            List<BlockPos> unit = floodUnit(level, rect, bed, known);
            assigned.addAll(unit);
            homes.add(new Home(zone.id, false, unit));
        }
        return homes;
    }

    public static boolean isBedHead(BlockState state) {
        return state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    /** 从一张床出发，把同一个房间里的床都找出来。门、墙和露天的地方是边界。 */
    private static List<BlockPos> floodUnit(ServerLevel level, ZoneRect rect, BlockPos start, Set<BlockPos> knownBeds) {
        List<BlockPos> unit = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        while (!queue.isEmpty() && seen.size() < MAX_CELLS) {
            BlockPos cell = queue.poll();
            if (knownBeds.contains(cell)) unit.add(cell);
            for (Direction direction : Direction.values()) {
                probe.setWithOffset(cell, direction);
                if (!rect.contains(probe.getX(), probe.getZ()) || level.isOutsideBuildHeight(probe)) continue;
                if (seen.contains(probe)) continue;
                if (!isRoomSpace(level, probe) || isOpenToSky(level, probe)) continue;
                BlockPos next = probe.immutable();
                seen.add(next);
                queue.add(next);
            }
        }
        return unit;
    }

    /** 这一格的上方没有任何遮挡，说明已经走到屋外了。露天摆着的床也算屋外。 */
    private static boolean isOpenToSky(ServerLevel level, BlockPos pos) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) <= pos.getY() + 1;
    }

    /** 这一格算不算房间里的空间。门、活板门、栅栏、玻璃板和墙都把房间隔开。 */
    private static boolean isRoomSpace(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof CrossCollisionBlock || block instanceof WallBlock) {
            return false;
        }
        if (block instanceof BedBlock) return true;
        VoxelShape shape = state.getCollisionShape(level, pos);
        // 地毯这类贴地的薄东西不算障碍；台阶、楼梯算，人是从它们上方的空气里走过去的
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.2;
    }

    private HomeScanner() {}
}
