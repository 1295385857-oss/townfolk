package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.entity.WorkAnim;
import com.townfolk.town.Activity;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 自由活动的时候找附近也闲着的居民聊一会儿天。
 * 一个人发起，另一个人被叫住后也运行这个目标，两人走到一起、面对面比划着说话。
 */
public class SocializeGoal extends Goal {
    private static final double WALK_SPEED = 0.8;
    private static final double SEARCH_RANGE = 12.0;
    private static final double TALK_DISTANCE_SQR = 2.4 * 2.4;
    private static final double GIVE_UP_DISTANCE_SQR = 20.0 * 20.0;

    private final ResidentEntity resident;
    private int cooldown;
    private int repathTimer;

    public SocializeGoal(ResidentEntity resident) {
        this.resident = resident;
        this.cooldown = 100 + resident.getRandom().nextInt(300);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (resident.getRoutine() != Activity.FREE || resident.isSleeping()) return false;
        if (resident.getChatPartner() != null) return true;         // 被别人叫住了
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        cooldown = 60;
        ResidentEntity partner = findPartner();
        if (partner == null) return false;
        int duration = 120 + resident.getRandom().nextInt(120);
        resident.startChat(partner, duration);
        partner.startChat(resident, duration);
        return true;
    }

    @Nullable
    private ResidentEntity findPartner() {
        List<ResidentEntity> nearby = resident.level().getEntitiesOfClass(ResidentEntity.class,
                resident.getBoundingBox().inflate(SEARCH_RANGE, 4.0, SEARCH_RANGE),
                other -> other != resident && other.isAlive() && !other.isSleeping()
                        && other.getRoutine() == Activity.FREE && other.getChatPartner() == null);
        ResidentEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ResidentEntity other : nearby) {
            double dist = other.distanceToSqr(resident);
            if (dist < best) {
                nearest = other;
                best = dist;
            }
        }
        return nearest;
    }

    @Override
    public boolean canContinueToUse() {
        ResidentEntity partner = resident.getChatPartner();
        return partner != null && partner.isAlive() && partner.getChatPartner() == resident
                && resident.getRoutine() == Activity.FREE && !resident.isSleeping()
                && resident.distanceToSqr(partner) < GIVE_UP_DISTANCE_SQR;
    }

    @Override
    public void start() {
        repathTimer = 0;
    }

    @Override
    public void stop() {
        resident.endChat();
        resident.setWorkAnim(WorkAnim.NONE);
        resident.getNavigation().stop();
        resident.setStatus(ResidentStatus.IDLE);
        cooldown = 300 + resident.getRandom().nextInt(500);
    }

    @Override
    public void tick() {
        ResidentEntity partner = resident.getChatPartner();
        if (partner == null) return;
        resident.getLookControl().setLookAt(partner, 30.0F, 30.0F);
        if (resident.distanceToSqr(partner) > TALK_DISTANCE_SQR) {
            resident.setWorkAnim(WorkAnim.NONE);
            if (--repathTimer <= 0) {
                repathTimer = 15;
                resident.getNavigation().moveTo(partner, WALK_SPEED);
            }
            return;
        }
        resident.getNavigation().stop();
        resident.setStatus(ResidentStatus.CHATTING);
        resident.setWorkAnim(WorkAnim.TALK);
        if (resident.tickChat()) {
            partner.endChat();
            resident.endChat();
        }
    }
}
