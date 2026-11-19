package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 农夫在种植区里找活干。先后顺序：存放收获 → 挖水坑 → 收割 → 播种 → 开垦。
 * 同一种活里先做离自己最近的。
 */
public final class FarmerPlanner {
    private static final double REACH = 2.0;
    /** 身上一颗种子都没有时，一次补给多少。 */
    private static final int SEED_SUPPLY = 48;

    private static final int PRIORITY_PIT = 0;
    private static final int PRIORITY_HARVEST = 1;
    private static final int PRIORITY_PLANT = 2;
    private static final int PRIORITY_TILL = 3;
    private static final int PRIORITY_COUNT = 4;

    @Nullable
    public static Job plan(ResidentEntity worker, ServerLevel level, Zone zone, WorkGoal goal) {
        CropBlock crop = (CropBlock) zone.industry.cropBlock();
        Item seed = zone.industry.plantingItem();
        ZoneRect rect = zone.rect;

        if (Storage.shouldDeposit(worker, seed)) {
            Job deposit = Storage.depositJob(worker, level, zone, seed);
            if (deposit != null) return deposit;
        }

        worker.ensureSupply(seed, SEED_SUPPLY);
        boolean hasSeed = worker.countItem(seed) > 0;
        boolean canCarryMore = worker.hasFreeSlot();

        BlockPos[] best = new BlockPos[PRIORITY_COUNT];
        double[] bestDist = new double[PRIORITY_COUNT];
        java.util.Arrays.fill(bestDist, Double.MAX_VALUE);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 规划好的水坑在哪几列、哪几行
        boolean[] pitColumn = new boolean[rect.width()];
        boolean[] pitRow = new boolean[rect.depth()];
        for (int dx : ZoneRect.pitOffsets(rect.width())) pitColumn[dx] = true;
        for (int dz : ZoneRect.pitOffsets(rect.depth())) pitRow[dz] = true;

        for (int x = rect.minX(); x <= rect.maxX(); x++) {
            for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
                if (!Terrain.isLoaded(level, x, z)) continue;
                int y = Terrain.groundY(level, x, z);
                BlockState ground = level.getBlockState(pos.set(x, y, z));
                BlockState above = level.getBlockState(pos.set(x, y + 1, z));
                int priority = -1;
                int targetY = y + 1;
                boolean isPit = pitColumn[x - rect.minX()] && pitRow[z - rect.minZ()];
                // 别人正在这里挖水坑：整格都别碰
                if (isPit && goal.isClaimedByOther(pos.set(x, y, z))) continue;

                if (isPit && !Terrain.isWater(ground) && pitWanted(level, rect, x, y, z, ground, above, goal)) {
                    priority = PRIORITY_PIT;
                    targetY = y;
                } else if (ground.getBlock() instanceof FarmBlock) {
                    if (above.getBlock() instanceof CropBlock growing) {
                        if (canCarryMore && growing.isMaxAge(above)) priority = PRIORITY_HARVEST;
                    } else if (above.isAir() && hasSeed) {
                        priority = PRIORITY_PLANT;
                    }
                } else if (Terrain.isTillable(ground) && Terrain.isClear(above)) {
                    priority = PRIORITY_TILL;
                }

                if (priority < 0) continue;
                pos.set(x, targetY, z);
                if (goal.isBlacklisted(pos)) continue;
                double dist = pos.distToCenterSqr(worker.getX(), worker.getY(), worker.getZ());
                if (dist < bestDist[priority]) {
                    bestDist[priority] = dist;
                    best[priority] = pos.immutable();
                }
            }
        }

        if (best[PRIORITY_PIT] != null) return new DigPitJob(best[PRIORITY_PIT], goal);
        if (best[PRIORITY_HARVEST] != null) return new HarvestJob(best[PRIORITY_HARVEST], crop, seed);
        if (best[PRIORITY_PLANT] != null) return new PlantJob(best[PRIORITY_PLANT], crop, seed);
        if (best[PRIORITY_TILL] != null) return new TillJob(best[PRIORITY_TILL]);

        // 田里暂时没活了：把身上的收获存掉
        if (Storage.countDepositable(worker, seed) > 0) {
            Job deposit = Storage.depositJob(worker, level, zone, seed);
            if (deposit != null) return deposit;
        }
        worker.setStatus(canCarryMore ? ResidentStatus.RESTING : ResidentStatus.STORAGE_FULL);
        return null;
    }

    /** 这个规划好的水坑位置现在要不要挖。结论会记一阵子，免得每次都重新算。 */
    private static boolean pitWanted(ServerLevel level, ZoneRect rect, int x, int y, int z, BlockState ground, BlockState above, WorkGoal goal) {
        BlockPos key = new BlockPos(x, y, z);
        if (goal.isBlacklisted(key)) return false;
        boolean diggable = Terrain.isTillable(ground) || ground.getBlock() instanceof FarmBlock
                || ground.is(BlockTags.SAND) || ground.is(Blocks.GRAVEL);
        boolean clearAbove = Terrain.isClear(above) || above.getBlock() instanceof BushBlock;
        if (!diggable || !clearAbove || !pitNeeded(level, rect, x, z)) {
            goal.blacklist(key, 2400);
            return false;
        }
        return true;
    }

    /** 水坑周围能种地的格子里，只要有一格还够不到水，就需要挖。已经有河或别的水坑照顾到的地方不用再挖。 */
    private static boolean pitNeeded(ServerLevel level, ZoneRect rect, int pitX, int pitZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Math.max(rect.minX(), pitX - 4); x <= Math.min(rect.maxX(), pitX + 4); x++) {
            for (int z = Math.max(rect.minZ(), pitZ - 4); z <= Math.min(rect.maxZ(), pitZ + 4); z++) {
                if (!Terrain.isLoaded(level, x, z)) continue;
                int y = Terrain.groundY(level, x, z);
                BlockState ground = level.getBlockState(pos.set(x, y, z));
                boolean farmable = ground.getBlock() instanceof FarmBlock || Terrain.isTillable(ground);
                if (farmable && !Terrain.hasWaterNearby(level, x, y, z)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 具体的活

    /** 挖一格深的水坑并倒上水。先把四周和底下的缺口用泥土补上，免得水流出去。 */
    private static final class DigPitJob extends Job {
        private final WorkGoal goal;

        DigPitJob(BlockPos pit, WorkGoal goal) {
            super(ResidentStatus.DIGGING_PIT, pit, REACH);
            this.goal = goal;
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (tick < 40) return false;
            BlockPos[] around = {target.north(), target.south(), target.west(), target.east(), target.below()};
            for (BlockPos side : around) {
                BlockState state = level.getBlockState(side);
                if (state.blocksMotion() || Terrain.isWater(state)) continue;
                if (!state.isAir() && !Terrain.isClear(state) && !(state.getBlock() instanceof BushBlock)) {
                    goal.blacklist(target, 6000);      // 旁边有不该动的东西，这个坑不挖了
                    return true;
                }
            }
            for (BlockPos side : around) {
                BlockState state = level.getBlockState(side);
                if (!state.blocksMotion() && !Terrain.isWater(state)) {
                    level.setBlockAndUpdate(side, Blocks.DIRT.defaultBlockState());
                }
            }
            if (!level.getBlockState(target.above()).isAir()) {
                level.destroyBlock(target.above(), false, worker);
            }
            level.destroyBlock(target, false, worker);
            level.setBlockAndUpdate(target, Blocks.WATER.defaultBlockState());
            level.playSound(null, target, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            return true;
        }
    }

    /** 把草地或泥土锄成耕地。 */
    private static final class TillJob extends Job {
        TillJob(BlockPos abovePlot) {
            super(ResidentStatus.TILLING, abovePlot, REACH);
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (tick < 14) return false;
            BlockPos plot = target.below();
            if (!Terrain.isTillable(level.getBlockState(plot))) return true;
            BlockState above = level.getBlockState(target);
            if (!Terrain.isClear(above)) return true;
            if (!above.isAir()) level.destroyBlock(target, false, worker);
            level.setBlockAndUpdate(plot, Blocks.FARMLAND.defaultBlockState());
            level.playSound(null, plot, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
            return true;
        }
    }

    /** 在空着的耕地上播种。 */
    private static final class PlantJob extends Job {
        private final CropBlock crop;
        private final Item seed;

        PlantJob(BlockPos abovePlot, CropBlock crop, Item seed) {
            super(ResidentStatus.PLANTING, abovePlot, REACH);
            this.crop = crop;
            this.seed = seed;
        }

        @Override
        public ItemStack heldItem() {
            return new ItemStack(seed);
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (tick < 8) return false;
            if (!level.getBlockState(target).isAir() || !(level.getBlockState(target.below()).getBlock() instanceof FarmBlock)) return true;
            if (!worker.consumeItem(seed)) return true;
            level.setBlockAndUpdate(target, crop.getStateForAge(0));
            level.playSound(null, target, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
            return true;
        }
    }

    /** 收割成熟的作物，并当场补种分区选定的作物。 */
    private static final class HarvestJob extends Job {
        private final CropBlock crop;
        private final Item seed;

        HarvestJob(BlockPos cropPos, CropBlock crop, Item seed) {
            super(ResidentStatus.HARVESTING, cropPos, REACH);
            this.crop = crop;
            this.seed = seed;
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (tick < 10) return false;
            BlockState state = level.getBlockState(target);
            if (!(state.getBlock() instanceof CropBlock growing) || !growing.isMaxAge(state)) return true;

            List<ItemStack> drops = Block.getDrops(state, level, target, null, worker, worker.getMainHandItem());
            level.destroyBlock(target, false, worker);
            for (ItemStack drop : drops) {
                ItemStack left = worker.addToInventory(drop);
                if (!left.isEmpty()) Block.popResource(level, target, left);
            }
            if (worker.consumeItem(seed) && level.getBlockState(target.below()).getBlock() instanceof FarmBlock) {
                level.setBlockAndUpdate(target, crop.getStateForAge(0));
            }
            return true;
        }
    }

    private FarmerPlanner() {}
}
