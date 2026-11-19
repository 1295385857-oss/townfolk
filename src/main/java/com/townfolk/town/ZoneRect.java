package com.townfolk.town;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** 分区在水平面上占的矩形（含两端）。服务端和客户端共用这里的规则。 */
public record ZoneRect(int minX, int minZ, int maxX, int maxZ) {
    public static final int MIN_SIZE = 3;
    public static final int MAX_SIZE = 48;
    /** 一格水能润湿水平 4 格以内的耕地，所以一个水坑管 9 格宽。 */
    private static final int WATER_SPAN = 9;

    public static ZoneRect of(int x1, int z1, int x2, int z2) {
        return new ZoneRect(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean intersects(ZoneRect other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /**
     * 检查能不能在这里建分区。可以时返回 null，否则返回原因的翻译键。
     * @param centerX 镇公所的 X
     * @param centerZ 镇公所的 Z
     * @param others 小镇里已有的分区
     */
    @Nullable
    public String problem(int centerX, int centerZ, Iterable<ZoneRect> others) {
        if (width() < MIN_SIZE || depth() < MIN_SIZE) return "townfolk.zone.too_small";
        if (width() > MAX_SIZE || depth() > MAX_SIZE) return "townfolk.zone.too_large";
        if (minX < centerX - Town.RADIUS || maxX > centerX + Town.RADIUS
                || minZ < centerZ - Town.RADIUS || maxZ > centerZ + Town.RADIUS) {
            return "townfolk.zone.out_of_town";
        }
        for (ZoneRect other : others) {
            if (intersects(other)) return "townfolk.zone.overlap";
        }
        return null;
    }

    /**
     * 沿一条边排水坑：把这条边分成若干段，每段不超过 9 格，水坑放在每段正中，
     * 这样边上每一格离最近的水坑都不超过 4 格。返回的是相对起点的偏移。
     */
    public static int[] pitOffsets(int length) {
        int count = (length + WATER_SPAN - 1) / WATER_SPAN;
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = (2 * i + 1) * length / (2 * count);
        }
        return offsets;
    }

    /** 种植区自动规划的水坑位置，每个元素是 {x, z}。 */
    public List<int[]> waterPits() {
        List<int[]> pits = new ArrayList<>();
        for (int dx : pitOffsets(width())) {
            for (int dz : pitOffsets(depth())) {
                pits.add(new int[] {minX + dx, minZ + dz});
            }
        }
        return pits;
    }

    public boolean isWaterPit(int x, int z) {
        if (!contains(x, z)) return false;
        boolean onX = false;
        for (int dx : pitOffsets(width())) {
            if (minX + dx == x) onX = true;
        }
        if (!onX) return false;
        for (int dz : pitOffsets(depth())) {
            if (minZ + dz == z) return true;
        }
        return false;
    }
}
