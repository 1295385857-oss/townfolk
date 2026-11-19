package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.entity.WorkAnim;
import com.townfolk.town.Activity;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;

/** 休息的钟点：就地坐下歇着。 */
public class RestGoal extends Goal {
    private final ResidentEntity resident;
    private int ticks;

    public RestGoal(ResidentEntity resident) {
        this.resident = resident;
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return resident.getRoutine() == Activity.REST && !resident.isSleeping() && resident.onGround();
    }

    @Override
    public boolean canContinueToUse() {
        return resident.getRoutine() == Activity.REST && !resident.isSleeping();
    }

    @Override
    public void start() {
        ticks = 0;
        resident.getNavigation().stop();
    }

    @Override
    public void stop() {
        resident.setWorkAnim(WorkAnim.NONE);
        resident.setStatus(ResidentStatus.IDLE);
    }

    @Override
    public void tick() {
        resident.setStatus(ResidentStatus.BREAK);
        // 站定一小会儿再坐下，免得刚停步就一屁股坐下去
        resident.setWorkAnim(++ticks > 15 ? WorkAnim.SIT : WorkAnim.NONE);
    }
}
