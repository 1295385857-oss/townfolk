package com.townfolk.entity.ai;

import com.townfolk.block.RegisterBlockEntity;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.entity.WorkAnim;
import com.townfolk.town.Zone;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** 店员上班：找一个还没人看的收银台，站到它后面看店。 */
public final class ClerkPlanner {

    @Nullable
    public static Job plan(ResidentEntity worker, ServerLevel level, Zone zone, WorkGoal goal) {
        RegisterBlockEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (RegisterBlockEntity register : Terrain.findRegisters(level, zone.rect)) {
            if (goal.isBlacklisted(register.clerkSpot())) continue;        // 已经有别的店员在看了
            double dist = register.getBlockPos().distSqr(worker.blockPosition());
            if (dist < best) {
                nearest = register;
                best = dist;
            }
        }
        if (nearest == null) {
            worker.setStatus(ResidentStatus.NO_REGISTER);
            return null;
        }
        return new TendJob(nearest.clerkSpot(), nearest.getBlockPos());
    }

    /** 站在收银台后面看店，一直站到下班。有客人结账时做出收钱的动作。 */
    private static final class TendJob extends Job {
        private final BlockPos registerPos;
        private boolean serving;

        TendJob(BlockPos spot, BlockPos registerPos) {
            super(ResidentStatus.TENDING, spot, 1.4);
            this.registerPos = registerPos;
        }

        @Override
        public boolean isOpenEnded() {
            return true;
        }

        @Override
        public boolean needsTool() {
            return false;
        }

        @Override
        public WorkAnim anim() {
            return serving ? WorkAnim.STORE : WorkAnim.NONE;
        }

        @Override
        public BlockPos lookAt() {
            return registerPos.above();
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public boolean work(ResidentEntity worker, ServerLevel level, int tick) {
            if (!(level.getBlockEntity(registerPos) instanceof RegisterBlockEntity register)) return true;     // 收银台被拆了
            long now = level.getGameTime();
            // 寻路只保证走到旁边一格，这里再慢慢挪到收银台正后方站好
            if (target.distToCenterSqr(worker.position()) > 0.3 * 0.3 + 1.0) {
                worker.getMoveControl().setWantedPosition(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.5);
            }
            register.tend(worker.getUUID(), now);
            serving = register.isServing(now);
            return false;
        }
    }

    private ClerkPlanner() {}
}
