package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.entity.WorkAnim;
import com.townfolk.town.Activity;
import com.townfolk.town.Economy;
import com.townfolk.town.ResidentRecord;
import com.townfolk.town.Town;
import com.townfolk.town.TownSavedData;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 吃饭的钟点：家不太远就回家吃，太远就在原地吃。
 * 吃的是家里的存粮（买回来的食物折成的饱腹点数），一口一口地把饱食度补上去。家里没有存粮就只能饿着。
 */
public class EatGoal extends Goal {
    private static final double WALK_SPEED = 0.9;
    /** 家离得比这更远就不回去了。 */
    private static final double MAX_HOME_DISTANCE_SQR = 48.0 * 48.0;
    private static final double ARRIVE_DISTANCE_SQR = 3.0 * 3.0;
    /** 走了这么久还没到家，就地吃。 */
    private static final int MAX_TRAVEL_TICKS = 400;
    private static final int BITE_TICKS = 70;
    private static final int PAUSE_TICKS = 50;
    /** 一口补多少饱食度。 */
    private static final int BITE_POINTS = 4;

    private final ResidentEntity resident;
    @Nullable private BlockPos table;
    private int travelTicks;
    private int mealTicks;
    private int repathTimer;

    public EatGoal(ResidentEntity resident) {
        this.resident = resident;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return resident.getRoutine() == Activity.EAT && !resident.isSleeping();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        BlockPos home = resident.getHomeBed();
        table = home != null && home.distToCenterSqr(resident.position()) <= MAX_HOME_DISTANCE_SQR ? home : null;
        travelTicks = 0;
        mealTicks = resident.getRandom().nextInt(PAUSE_TICKS);
        repathTimer = 0;
    }

    @Override
    public void stop() {
        resident.setWorkAnim(WorkAnim.NONE);
        resident.showInHand(ItemStack.EMPTY);
        resident.getNavigation().stop();
        resident.setStatus(ResidentStatus.IDLE);
    }

    @Override
    public void tick() {
        resident.setStatus(ResidentStatus.EATING);
        if (table != null && table.distToCenterSqr(resident.position()) > ARRIVE_DISTANCE_SQR && travelTicks < MAX_TRAVEL_TICKS) {
            travelTicks++;
            resident.setWorkAnim(WorkAnim.NONE);
            if (--repathTimer <= 0 || resident.getNavigation().isDone()) {
                repathTimer = 40;
                resident.getNavigation().moveTo(table.getX() + 0.5, table.getY(), table.getZ() + 0.5, WALK_SPEED);
            }
            return;
        }
        resident.getNavigation().stop();

        ResidentRecord pantry = pantryOwner();
        boolean full = resident.getSatiety() >= ResidentEntity.MAX_SATIETY;
        if (pantry == null || pantry.pantry <= 0 || full) {
            // 吃饱了，或者家里没有吃的
            resident.setWorkAnim(WorkAnim.NONE);
            resident.showInHand(ItemStack.EMPTY);
            if (!full && resident.isHungry()) resident.setStatus(ResidentStatus.HUNGRY);
            return;
        }
        mealTicks++;
        int phase = mealTicks % (BITE_TICKS + PAUSE_TICKS);
        if (phase == PAUSE_TICKS) {
            int points = Math.min(BITE_POINTS, Math.min(pantry.pantry, ResidentEntity.MAX_SATIETY - resident.getSatiety()));
            pantry.pantry -= points;
            resident.eat(points);
        }
        resident.showInHand(new ItemStack(Items.BREAD));
        resident.setWorkAnim(phase >= PAUSE_TICKS ? WorkAnim.EAT : WorkAnim.NONE);
    }

    /** 这个居民吃的那份存粮记在谁名下。 */
    @Nullable
    private ResidentRecord pantryOwner() {
        if (!(resident.level() instanceof ServerLevel level)) return null;
        Town town = TownSavedData.get(level).getTown(resident.getTownId());
        ResidentRecord record = town == null ? null : town.residents.get(resident.getUUID());
        return record == null ? null : Economy.pantryOwner(town, record);
    }
}
