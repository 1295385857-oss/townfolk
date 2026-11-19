package com.townfolk.entity.ai;

import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.entity.WorkAnim;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** 居民手头的一件活：走到 target 附近，然后花一点时间把它做完。 */
public abstract class Job {
    public final ResidentStatus status;
    public final BlockPos target;
    /** 离 target 的水平距离在这个范围内就可以动手。 */
    public final double reach;

    protected Job(ResidentStatus status, BlockPos target, double reach) {
        this.status = status;
        this.target = target;
        this.reach = reach;
    }

    /** 这件活还要不要做（比如要捡的东西已经没了）。 */
    public boolean isValid() {
        return true;
    }

    /** 干这件活时摆的动作。 */
    public WorkAnim anim() {
        return switch (status) {
            case TILLING -> WorkAnim.HOE;
            case PLANTING, HARVESTING, COLLECTING, PLANTING_SAPLING -> WorkAnim.BEND;
            case CHOPPING -> WorkAnim.CHOP;
            case DIGGING_PIT -> WorkAnim.DIG;
            case DEPOSITING -> WorkAnim.STORE;
            default -> WorkAnim.NONE;
        };
    }

    /** 这件活要不要工具。要工具而手里没有时只能徒手干，慢很多。 */
    public boolean needsTool() {
        return switch (status) {
            case TILLING, HARVESTING, DIGGING_PIT, CHOPPING -> true;
            default -> false;
        };
    }

    /** 干完这件活，工具磨损多少次。 */
    public int toolUses() {
        return 1;
    }

    /** 没有“做完”这一说的活，比如看店：一直干到下班。 */
    public boolean isOpenEnded() {
        return false;
    }

    /** 干活时眼睛看着哪里。 */
    public BlockPos lookAt() {
        return target;
    }

    /** 干这件活时手里拿的东西。空表示拿着自己的工具，没有工具就空着手。 */
    public ItemStack heldItem() {
        return ItemStack.EMPTY;
    }

    /**
     * 到位之后每 tick 调用一次。
     * @param tick 已经在这件活上花了多少 tick，从 1 开始
     * @return 做完了返回 true
     */
    public abstract boolean work(ResidentEntity worker, ServerLevel level, int tick);
}
