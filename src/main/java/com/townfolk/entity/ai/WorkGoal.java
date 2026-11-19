package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.WorkAnim;
import com.townfolk.town.Industry;
import com.townfolk.town.Zone;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 上班：在自己的工作分区里一件接一件地找活干。具体找什么活由职业对应的 Planner 决定。 */
public class WorkGoal extends Goal {
    private static final double WALK_SPEED = 1.0;
    private static final int MAX_TRAVEL_TICKS = 400;
    private static final int MAX_PATH_FAILURES = 4;

    private final ResidentEntity resident;
    @Nullable private Job job;
    private int travelTicks;
    private int workTicks;
    private int pathFailures;
    private int cooldown;
    private int slowTicks;
    /** 暂时不去碰的位置 → 到什么时候为止（游戏时间）。 */
    private final Map<BlockPos, Long> blacklist = new HashMap<>();

    public WorkGoal(ResidentEntity resident) {
        this.resident = resident;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (resident.isSleeping() || resident.isSleepTime()) return false;
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        job = resident.isWorkTime() ? plan() : planLeftovers();
        if (job == null) {
            cooldown = 30 + resident.getRandom().nextInt(20);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && !resident.isSleeping();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        beginJob();
    }

    @Override
    public void stop() {
        endJob();
        job = null;
        resident.getNavigation().stop();
    }

    @Override
    public void tick() {
        Job current = job;
        if (current == null || !(resident.level() instanceof ServerLevel level)) return;
        if (!current.isValid()) {
            nextJob();
            return;
        }
        // 活干得久的话（比如砍一棵大树），定期告诉别人这一格还是我的
        if ((travelTicks + workTicks) % 100 == 99) {
            WorkClaims.claim(level, current.target, resident.getUUID());
        }

        BlockPos target = current.target;
        double dx = target.getX() + 0.5 - resident.getX();
        double dz = target.getZ() + 0.5 - resident.getZ();
        double dy = target.getY() - resident.getY();
        boolean inReach = dx * dx + dz * dz <= current.reach * current.reach && Math.abs(dy) <= 3.5;

        if (inReach) {
            resident.getNavigation().stop();
            // 路上花的时间只算连续的：到了就清零。不然看店这种一站一天的活，被人挤开几次就会被当成“走不到”
            travelTicks = 0;
            pathFailures = 0;
            BlockPos look = current.lookAt();
            resident.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 0.5, look.getZ() + 0.5);
            resident.setWorkAnim(current.anim());
            resident.showInHand(current.heldItem());
            if (current.isOpenEnded() && !resident.isWorkTime()) {
                nextJob();                              // 下班了
                return;
            }
            // 没有工具只能徒手干，饿得厉害也干不动：三下里只有一下算数
            boolean slow = (current.needsTool() && !resident.hasTool()) || resident.isStarving();
            if (slow && ++slowTicks % 3 != 0) return;
            workTicks++;
            if (current.anim() == WorkAnim.NONE && !current.isOpenEnded() && workTicks % 6 == 1) {
                resident.swing(InteractionHand.MAIN_HAND);
            }
            if (current.work(resident, level, workTicks)) {
                if (current.needsTool()) resident.useTool(current.toolUses());
                nextJob();
            }
            return;
        }

        resident.setWorkAnim(WorkAnim.NONE);
        travelTicks++;
        if (travelTicks > MAX_TRAVEL_TICKS) {
            giveUp(current);
        } else if (travelTicks % 10 == 0 && resident.getNavigation().isDone()) {
            if (!moveToTarget(current)) {
                pathFailures++;
                if (pathFailures >= MAX_PATH_FAILURES) giveUp(current);
            }
        }
    }

    private void giveUp(Job current) {
        blacklist(current.target, 600);
        nextJob();
    }

    private void nextJob() {
        endJob();
        job = resident.isWorkTime() ? plan() : planLeftovers();
        if (job != null) {
            beginJob();
        }
    }

    private void beginJob() {
        Job current = job;
        if (current == null) return;
        travelTicks = 0;
        workTicks = 0;
        pathFailures = 0;
        resident.setStatus(current.status);
        resident.setWorkTarget(current.target);
        WorkClaims.claim(resident.level(), current.target, resident.getUUID());
        moveToTarget(current);
    }

    /** 放下手头的活：把认领的那一格让出来，收起动作，换回本职工具。 */
    private void endJob() {
        if (job != null) {
            WorkClaims.release(resident.level(), job.target, resident.getUUID());
        }
        resident.setWorkTarget(null);
        resident.setWorkAnim(WorkAnim.NONE);
        resident.showInHand(ItemStack.EMPTY);
    }

    private boolean moveToTarget(Job current) {
        BlockPos target = current.target;
        return resident.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, WALK_SPEED);
    }

    @Nullable
    private Job plan() {
        Zone zone = resident.getWorkZone();
        if (zone == null || !(resident.level() instanceof ServerLevel level)) return null;
        if (zone.industry.isCrop()) return FarmerPlanner.plan(resident, level, zone, this);
        if (zone.industry.isTree()) return LumberjackPlanner.plan(resident, level, zone, this);
        if (zone.industry == Industry.SHOP) return ClerkPlanner.plan(resident, level, zone, this);
        return null;
    }

    /** 下班了但身上还有收获：先送去箱子，再去吃饭或者休息。 */
    @Nullable
    private Job planLeftovers() {
        Zone zone = resident.getWorkZone();
        if (zone == null || !(zone.industry.isCrop() || zone.industry.isTree()) || !(resident.level() instanceof ServerLevel level)) return null;
        Item keep = zone.industry.plantingItem();
        if (Storage.countDepositable(resident, keep) == 0) return null;
        return Storage.depositJob(resident, level, zone, keep);
    }

    /** 这个位置现在不该去：自己刚放弃过，或者已经有别的居民在干了。 */
    public boolean isBlacklisted(BlockPos pos) {
        if (WorkClaims.isClaimedByOther(resident.level(), pos, resident.getUUID())) return true;
        Long until = blacklist.get(pos);
        if (until == null) return false;
        if (resident.level().getGameTime() >= until) {
            blacklist.remove(pos);
            return false;
        }
        return true;
    }

    /** 已经有别的居民在干这一格了。 */
    public boolean isClaimedByOther(BlockPos pos) {
        return WorkClaims.isClaimedByOther(resident.level(), pos, resident.getUUID());
    }

    public void blacklist(BlockPos pos, int ticks) {
        blacklist.put(pos.immutable(), resident.level().getGameTime() + ticks);
    }
}
