package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.town.Zone;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** 把收获存进分区里的箱子。 */
public final class Storage {
    /** 身上给自己留多少播种用的种子或树苗，多出来的才存箱子。 */
    public static final int RESERVE = 64;
    /** 箱子都满了之后，过多久再去看一眼（tick）。 */
    private static final int RETRY_DELAY = 600;

    /** 身上有多少东西可以存进箱子（不算留着自己用的那部分）。 */
    public static int countDepositable(ResidentEntity worker, Item reserve) {
        int total = 0;
        int reserved = 0;
        for (ItemStack stack : worker.getInventory().getItems()) {
            if (stack.isEmpty()) continue;
            if (stack.is(reserve)) reserved += stack.getCount();
            else total += stack.getCount();
        }
        return total + Math.max(0, reserved - RESERVE);
    }

    /**
     * 该不该放下手里的活去存一趟：只有快收工了，或者身上快装满、再不存就没法接着干的时候。
     * 平时不打断干活；田里没活可干时，各职业的 Planner 会另外安排去存。
     */
    public static boolean shouldDeposit(ResidentEntity worker, Item reserve) {
        int depositable = countDepositable(worker, reserve);
        if (depositable == 0) return false;
        return worker.usedSlots() >= ResidentEntity.INVENTORY_SIZE - 2 || worker.isWorkDayEnding();
    }

    /** 找一个还有空位的箱子去存东西。没有箱子或箱子都满了返回 null。 */
    @Nullable
    public static Job depositJob(ResidentEntity worker, ServerLevel level, Zone zone, Item reserve) {
        if (worker.isStorageFull()) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : Terrain.findStorage(level, zone.rect)) {
            if (!(level.getBlockEntity(pos) instanceof Container container) || !hasEmptySlot(container)) continue;
            double dist = pos.distSqr(worker.blockPosition());
            if (dist < bestDist) {
                best = pos;
                bestDist = dist;
            }
        }
        if (best == null) {
            worker.markStorageFull(RETRY_DELAY);
            return null;
        }
        return new DepositJob(best, reserve);
    }

    private static boolean hasEmptySlot(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    private static final class DepositJob extends Job {
        private final Item reserve;

        DepositJob(BlockPos chest, Item reserve) {
            super(ResidentStatus.DEPOSITING, chest, 2.5);
            this.reserve = reserve;
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (tick < 10) return false;
            if (!(level.getBlockEntity(target) instanceof Container container)) return true;

            SimpleContainer inventory = worker.getInventory();
            int kept = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) continue;
                if (stack.is(reserve) && kept < RESERVE) {
                    int keep = Math.min(stack.getCount(), RESERVE - kept);
                    kept += keep;
                    if (keep == stack.getCount()) continue;
                    ItemStack extra = stack.split(stack.getCount() - keep);
                    ItemStack left = HopperBlockEntity.addItem(null, container, extra, null);
                    stack.grow(left.getCount());
                } else {
                    inventory.setItem(slot, HopperBlockEntity.addItem(null, container, stack, null));
                }
            }
            level.playSound(null, target, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.4F, 1.0F);
            if (countDepositable(worker, reserve) > 0) {
                // 这个箱子装不下了；下次会去找别的箱子，都满了就先歇着
                worker.markStorageFull(40);
            }
            return true;
        }
    }

    private Storage() {}
}
