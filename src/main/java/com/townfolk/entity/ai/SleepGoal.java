package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** 晚上回自己的床睡觉，天亮起床。没有床的人晚上聚在镇公所附近。 */
public class SleepGoal extends Goal {
    private static final double WALK_SPEED = 0.9;
    private static final double BED_REACH_SQR = 2.5 * 2.5;
    private static final double GATHER_RANGE_SQR = 6.0 * 6.0;

    private final ResidentEntity resident;
    private int repathTimer;

    public SleepGoal(ResidentEntity resident) {
        this.resident = resident;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return resident.isSleepTime() && resident.getTownId() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathTimer = 0;
    }

    @Override
    public void stop() {
        if (resident.isSleeping()) {
            resident.stopSleeping();
        }
        resident.getNavigation().stop();
        resident.setStatus(ResidentStatus.IDLE);
    }

    @Override
    public void tick() {
        BlockPos bed = resident.getHomeBed();
        if (resident.isSleeping()) {
            boolean inOwnBed = bed != null && resident.getSleepingPos().filter(bed::equals).isPresent();
            if (inOwnBed) {
                resident.setStatus(ResidentStatus.SLEEPING);
                return;
            }
            resident.stopSleeping();            // 夜里搬了家：起来去新床
        }
        if (bed == null) {
            resident.setStatus(ResidentStatus.NO_BED);
            BlockPos center = resident.getTownCenter();
            if (center != null && center.distToCenterSqr(resident.position()) > GATHER_RANGE_SQR) {
                walkTo(center);
            }
            return;
        }

        resident.setStatus(ResidentStatus.GOING_HOME);
        if (bed.distToCenterSqr(resident.position()) > BED_REACH_SQR) {
            walkTo(bed);
            return;
        }
        BlockState state = resident.level().getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART) != BedPart.HEAD) {
            resident.forgetHomeBed();           // 床被拆了
        } else if (!state.getValue(BedBlock.OCCUPIED)) {
            resident.getNavigation().stop();
            resident.startSleeping(bed);
            resident.setStatus(ResidentStatus.SLEEPING);
        }
        // 床被别人占着就在旁边等
    }

    private void walkTo(BlockPos pos) {
        if (--repathTimer > 0 && !resident.getNavigation().isDone()) return;
        repathTimer = 40;
        resident.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }
}
