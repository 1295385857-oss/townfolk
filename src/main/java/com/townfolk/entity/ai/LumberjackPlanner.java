package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 伐木工在林场里找活干。先后顺序：存放收获 → 捡掉在地上的树苗和木头 → 补种树苗 → 砍树。
 * 只砍带天然树叶的树，玩家用原木搭的东西不会被当成树。
 */
public final class LumberjackPlanner {
    private static final double REACH = 2.5;
    private static final int SAPLING_SUPPLY = 8;
    /** 一棵树最多算多少块原木，防止把一大片连在一起的木头都当成一棵树。 */
    private static final int MAX_LOGS = 160;
    /** 至少挨着这么多片天然树叶才算一棵树。 */
    private static final int MIN_NATURAL_LEAVES = 3;
    /** 每次找种树位置时随机试多少个地方。 */
    private static final int PLANT_ATTEMPTS = 24;
    /** 一次计划里最多仔细检查几棵候选的树。 */
    private static final int MAX_TREE_CHECKS = 3;

    @Nullable
    public static Job plan(ResidentEntity worker, ServerLevel level, Zone zone, WorkGoal goal) {
        Item sapling = zone.industry.plantingItem();
        ZoneRect rect = zone.rect;

        if (Storage.shouldDeposit(worker, sapling)) {
            Job deposit = Storage.depositJob(worker, level, zone, sapling);
            if (deposit != null) return deposit;
        }
        boolean canCarryMore = worker.hasFreeSlot();

        if (canCarryMore) {
            Job collect = collectJob(worker, level, rect, goal);
            if (collect != null) return collect;
        }

        // 手里有树苗就先找空地补种，再去砍下一棵，这样林场不会被砍成一片空地才开始补
        worker.ensureSupply(sapling, SAPLING_SUPPLY);
        if (worker.countItem(sapling) > 0) {
            Job plant = plantJob(worker, level, zone, goal);
            if (plant != null) return plant;
        }

        if (canCarryMore) {
            Job chop = chopJob(worker, level, rect, goal);
            if (chop != null) return chop;
        }

        if (Storage.countDepositable(worker, sapling) > 0) {
            Job deposit = Storage.depositJob(worker, level, zone, sapling);
            if (deposit != null) return deposit;
        }
        worker.setStatus(canCarryMore ? ResidentStatus.RESTING : ResidentStatus.STORAGE_FULL);
        return null;
    }

    // ------------------------------------------------------------ 捡东西

    private static boolean isForestryItem(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK) || stack.is(Items.APPLE);
    }

    @Nullable
    private static Job collectJob(ResidentEntity worker, ServerLevel level, ZoneRect rect, WorkGoal goal) {
        AABB box = new AABB(rect.minX(), worker.getY() - 6, rect.minZ(), rect.maxX() + 1, worker.getY() + 6, rect.maxZ() + 1);
        ItemEntity nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.onGround() && isForestryItem(e.getItem()))) {
            if (goal.isBlacklisted(item.blockPosition())) continue;     // 别人已经去捡了
            double dist = item.distanceToSqr(worker);
            if (dist < bestDist) {
                nearest = item;
                bestDist = dist;
            }
        }
        return nearest == null ? null : new CollectJob(nearest);
    }

    private static final class CollectJob extends Job {
        private final ItemEntity item;

        CollectJob(ItemEntity item) {
            super(ResidentStatus.COLLECTING, item.blockPosition(), 1.8);
            this.item = item;
        }

        @Override
        public boolean isValid() {
            return item.isAlive();
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (tick < 4) return false;
            ItemStack left = worker.addToInventory(item.getItem().copy());
            if (left.isEmpty()) {
                item.discard();
            } else {
                item.setItem(left);
            }
            level.playSound(null, worker.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.3F, 1.4F);
            return true;
        }
    }

    // ------------------------------------------------------------ 砍树

    @Nullable
    private static Job chopJob(ResidentEntity worker, ServerLevel level, ZoneRect rect, WorkGoal goal) {
        // 先把分区里所有“泥土上立着原木”的位置按远近排好，再从近到远检查是不是真的树
        List<BlockPos> bases = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = rect.minX(); x <= rect.maxX(); x++) {
            for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
                if (!Terrain.isLoaded(level, x, z)) continue;
                int y = Terrain.groundY(level, x, z);
                if (!level.getBlockState(pos.set(x, y, z)).is(BlockTags.DIRT)) continue;
                if (!level.getBlockState(pos.set(x, y + 1, z)).is(BlockTags.LOGS)) continue;
                if (goal.isBlacklisted(pos)) continue;
                bases.add(pos.immutable());
            }
        }
        BlockPos here = worker.blockPosition();
        bases.sort(Comparator.comparingDouble(base -> base.distSqr(here)));

        int checks = 0;
        for (BlockPos base : bases) {
            if (checks++ >= MAX_TREE_CHECKS) break;
            List<BlockPos> logs = scanTree(level, rect, base);
            if (logs == null) {
                goal.blacklist(base, 6000);        // 不是天然的树，过很久再看
                continue;
            }
            return new ChopJob(base, logs);
        }
        return null;
    }

    /**
     * 从树根往上找出整棵树的原木，由低到高排列。
     * 没挨着足够多的天然树叶（说明不是树）时返回 null。
     */
    @Nullable
    private static List<BlockPos> scanTree(ServerLevel level, ZoneRect rect, BlockPos base) {
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(base);
        seen.add(base);
        int leaves = 0;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
            BlockPos log = queue.poll();
            logs.add(log);
            for (Direction direction : Direction.values()) {
                BlockState neighbor = level.getBlockState(probe.setWithOffset(log, direction));
                if (neighbor.getBlock() instanceof LeavesBlock && !neighbor.getValue(LeavesBlock.PERSISTENT)) leaves++;
            }
            // 树枝可能斜着长，所以连对角方向一起找；只往同层和上层找
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        probe.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                        // 树冠可以伸出分区一点，但不追到太远的地方
                        if (probe.getX() < rect.minX() - 3 || probe.getX() > rect.maxX() + 3
                                || probe.getZ() < rect.minZ() - 3 || probe.getZ() > rect.maxZ() + 3) continue;
                        if (seen.contains(probe) || !level.getBlockState(probe).is(BlockTags.LOGS)) continue;
                        BlockPos next = probe.immutable();
                        seen.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        if (leaves < MIN_NATURAL_LEAVES) return null;
        logs.sort(Comparator.comparingInt(BlockPos::getY));
        return logs;
    }

    /** 从下往上一块一块砍掉整棵树，木头收进身上。树叶会自己慢慢枯掉。 */
    private static final class ChopJob extends Job {
        private static final int TICKS_PER_LOG = 8;
        private final List<BlockPos> logs;
        private int index;

        ChopJob(BlockPos base, List<BlockPos> logs) {
            super(ResidentStatus.CHOPPING, base, REACH);
            this.logs = logs;
        }

        /** 砍一棵树，每块原木磨损斧头一次。 */
        @Override
        public int toolUses() {
            return logs.size();
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (tick % TICKS_PER_LOG != 0) return false;
            while (index < logs.size()) {
                BlockPos pos = logs.get(index++);
                BlockState state = level.getBlockState(pos);
                if (!state.is(BlockTags.LOGS)) continue;
                level.destroyBlock(pos, false, worker);
                ItemStack left = worker.addToInventory(new ItemStack(state.getBlock().asItem()));
                if (!left.isEmpty()) Block.popResource(level, worker.blockPosition(), left);
                return index >= logs.size();
            }
            return true;
        }
    }

    // ------------------------------------------------------------ 种树

    /**
     * 在林场里随机找一块空地种树苗。位置不排成网格，只要求和周围的树干、树苗隔开足够的距离，
     * 上方有生长空间，并且不挨着箱子。试了若干次都找不到就说明暂时种满了。
     */
    @Nullable
    private static Job plantJob(ResidentEntity worker, ServerLevel level, Zone zone, WorkGoal goal) {
        ZoneRect rect = zone.rect;
        RandomSource random = worker.getRandom();
        BlockState sapling = zone.industry.saplingBlock().defaultBlockState();
        int gap = zone.industry.treeGap;
        List<BlockPos> storage = Terrain.findStorage(level, rect);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int attempt = 0; attempt < PLANT_ATTEMPTS; attempt++) {
            int x = rect.minX() + random.nextInt(rect.width());
            int z = rect.minZ() + random.nextInt(rect.depth());
            if (!Terrain.isLoaded(level, x, z)) continue;
            int y = Terrain.groundY(level, x, z) + 1;
            pos.set(x, y, z);
            if (goal.isBlacklisted(pos)) continue;
            if (!Terrain.isClear(level.getBlockState(pos)) || !sapling.canSurvive(level, pos)) continue;
            if (!hasRoomToGrow(level, x, y, z) || hasTreeWithin(level, x, y, z, gap) || isNextToStorage(storage, x, z)) continue;
            return new PlantSaplingJob(pos.immutable(), sapling, zone.industry.plantingItem());
        }
        return null;
    }

    private static boolean hasRoomToGrow(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= 6; dy++) {
            BlockState state = level.getBlockState(pos.set(x, y + dy, z));
            if (!state.isAir() && !(state.getBlock() instanceof LeavesBlock)) return false;
        }
        return true;
    }

    private static boolean hasTreeWithin(ServerLevel level, int x, int y, int z, int gap) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -gap; dx <= gap; dx++) {
            for (int dz = -gap; dz <= gap; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockState state = level.getBlockState(pos.set(x + dx, y + dy, z + dz));
                    if (state.is(BlockTags.LOGS) || state.is(BlockTags.SAPLINGS)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isNextToStorage(List<BlockPos> storage, int x, int z) {
        for (BlockPos chest : storage) {
            if (Math.abs(chest.getX() - x) <= 1 && Math.abs(chest.getZ() - z) <= 1) return true;
        }
        return false;
    }

    private static final class PlantSaplingJob extends Job {
        private final BlockState sapling;
        private final Item item;

        PlantSaplingJob(BlockPos pos, BlockState sapling, Item item) {
            super(ResidentStatus.PLANTING_SAPLING, pos, 2.0);
            this.sapling = sapling;
            this.item = item;
        }

        @Override
        public ItemStack heldItem() {
            return new ItemStack(item);
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (tick < 10) return false;
            BlockState current = level.getBlockState(target);
            if (!Terrain.isClear(current) || !sapling.canSurvive(level, target)) return true;
            if (!worker.consumeItem(item)) return true;
            if (!current.isAir()) level.destroyBlock(target, false, worker);
            level.setBlockAndUpdate(target, sapling);
            level.playSound(null, target, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            return true;
        }
    }

    private LumberjackPlanner() {}
}
