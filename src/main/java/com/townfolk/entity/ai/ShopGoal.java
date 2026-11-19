package com.townfolk.entity.ai;

import com.townfolk.block.RegisterBlockEntity;
import com.townfolk.block.ShelfBlockEntity;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.entity.WorkAnim;
import com.townfolk.town.Activity;
import com.townfolk.town.Economy;
import com.townfolk.town.ResidentRecord;
import com.townfolk.town.ShopData;
import com.townfolk.town.Shopping;
import com.townfolk.town.Town;
import com.townfolk.town.TownSavedData;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * 去商店买东西：到货架前把要买的拿下来，到收银台排队，店员结完账货才算自己的。
 * 买工具随时可以去（所以钱够了会第一时间去买）；买吃的只在自由活动时，或者饿着而家里没存粮时去。
 */
public class ShopGoal extends Goal {
    private enum Phase { TO_SHELF, TO_REGISTER, PAYING }

    private static final double WALK_SPEED = 0.9;
    private static final double SHELF_REACH_SQR = 2.4 * 2.4;
    private static final double SPOT_REACH_SQR = 1.5 * 1.5;
    private static final double NEAR_REGISTER_SQR = 2.6 * 2.6;
    /** 排到最前面之后，这么久还走不到柜台正面，就在收银台旁边直接结账。 */
    private static final int STUCK_TICKS = 60;
    private static final int TAKE_TICKS = 14;
    private static final int PAY_TICKS = 40;
    private static final int MAX_TRIP_TICKS = 1600;
    private static final int RETRY_AFTER_FAILURE = 600;
    private static final int RETRY_AFTER_SUCCESS = 200;
    private static final int RETRY_FOR_TOOL = 100;

    private final ResidentEntity resident;
    @Nullable private Shopping.Plan plan;
    private final List<ItemStack> basket = new ArrayList<>();
    private Phase phase = Phase.TO_SHELF;
    private int pickIndex;
    private int timer;
    private int tripTicks;
    private int repathTimer;
    private int stuckTicks;
    private int cooldown;
    /** 这次购物已经结束（买完了或者放弃了），等 stop() 来收尾。 */
    private boolean done;

    public ShopGoal(ResidentEntity resident) {
        this.resident = resident;
        this.cooldown = 40 + resident.getRandom().nextInt(100);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(resident.level() instanceof ServerLevel level) || resident.isSleeping() || resident.isSleepTime()) return false;
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        cooldown = RETRY_AFTER_FAILURE;
        Town town = TownSavedData.get(level).getTown(resident.getTownId());
        ResidentRecord record = town == null ? null : town.residents.get(resident.getUUID());
        if (record == null || !resident.isAdult()) return false;
        // 缺工具的人隔几秒就看一次：店一开门、钱一够，第一时间去买
        if (Shopping.wantsTool(record)) cooldown = RETRY_FOR_TOOL;

        Activity routine = resident.getRoutine();
        ResidentRecord owner = Economy.pantryOwner(town, record);
        boolean starving = routine == Activity.EAT && owner.pantry <= 0 && resident.isHungry();
        plan = Shopping.plan(level, town, record, resident.blockPosition(), routine == Activity.FREE || starving);
        return plan != null;
    }

    @Override
    public boolean canContinueToUse() {
        return plan != null && !done && !resident.isSleeping() && !resident.isSleepTime();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        basket.clear();
        done = false;
        stuckTicks = 0;
        phase = Phase.TO_SHELF;
        pickIndex = 0;
        timer = 0;
        tripTicks = 0;
        repathTimer = 0;
        resident.setStatus(ResidentStatus.SHOPPING);
    }

    @Override
    public void stop() {
        // 没结账就走了：把拿在手里的货放回去
        if (!basket.isEmpty() && plan != null && resident.level() instanceof ServerLevel level) {
            returnGoods(level);
        }
        if (plan != null && resident.level().getBlockEntity(plan.register()) instanceof RegisterBlockEntity register) {
            register.leaveQueue(resident.getUUID());
        }
        plan = null;
        resident.setWorkAnim(WorkAnim.NONE);
        resident.showInHand(ItemStack.EMPTY);
        resident.getNavigation().stop();
        resident.setStatus(ResidentStatus.IDLE);
    }

    @Override
    public void tick() {
        Shopping.Plan current = plan;
        if (current == null || !(resident.level() instanceof ServerLevel level)) return;
        if (++tripTicks > MAX_TRIP_TICKS) {
            plan = finish(RETRY_AFTER_FAILURE);
            return;
        }
        switch (phase) {
            case TO_SHELF -> tickShelf(level, current);
            case TO_REGISTER, PAYING -> tickRegister(level, current);
        }
    }

    /**
     * 结束这次购物。这里只做个记号，计划本身先留着：
     * stop() 还要靠它知道去哪家店把没买成的货放回货架、从哪个收银台的队伍里退出来。
     */
    @Nullable
    private Shopping.Plan finish(int retryDelay) {
        cooldown = retryDelay;
        done = true;
        return plan;
    }

    private void tickShelf(ServerLevel level, Shopping.Plan current) {
        if (pickIndex >= current.picks().size()) {
            phase = Phase.TO_REGISTER;
            timer = 0;
            return;
        }
        Shopping.Pick pick = current.picks().get(pickIndex);
        BlockPos shelfPos = pick.shelf();
        resident.setStatus(ResidentStatus.SHOPPING);
        if (shelfPos.distToCenterSqr(resident.position()) > SHELF_REACH_SQR) {
            resident.setWorkAnim(WorkAnim.NONE);
            walkTo(shelfPos);
            timer = 0;
            return;
        }
        resident.getNavigation().stop();
        resident.getLookControl().setLookAt(shelfPos.getX() + 0.5, shelfPos.getY() + 0.6, shelfPos.getZ() + 0.5);
        resident.setWorkAnim(WorkAnim.STORE);
        if (++timer < TAKE_TICKS) return;
        timer = 0;
        pickIndex++;
        if (level.getBlockEntity(shelfPos) instanceof ShelfBlockEntity shelf) {
            ItemStack taken = Shopping.take(shelf, pick);
            if (!taken.isEmpty()) {
                basket.add(taken);
                resident.showInHand(taken);
                level.playSound(null, shelfPos, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.3F, 1.0F);
            }
        }
    }

    private void tickRegister(ServerLevel level, Shopping.Plan current) {
        if (basket.isEmpty() || !(level.getBlockEntity(current.register()) instanceof RegisterBlockEntity register)
                || !register.isTended(level.getGameTime())) {
            plan = finish(RETRY_AFTER_FAILURE);         // 什么都没拿到，或者店员走了
            return;
        }
        long now = level.getGameTime();
        int place = register.joinQueue(resident.getUUID(), now);
        BlockPos spot = register.queueSpot(place);
        BlockPos counter = register.getBlockPos();
        resident.getLookControl().setLookAt(counter.getX() + 0.5, counter.getY() + 1.0, counter.getZ() + 0.5);
        resident.setWorkAnim(WorkAnim.NONE);

        boolean atSpot = spot.distToCenterSqr(resident.position()) <= SPOT_REACH_SQR;
        if (place > 0) {
            stuckTicks = 0;
        } else if (!atSpot && ++stuckTicks > STUCK_TICKS && counter.distToCenterSqr(resident.position()) <= NEAR_REGISTER_SQR) {
            // 排在最前面却被柜台或者别人挡着走不到正面：挨着收银台就算到了，不能让整条队伍卡死
            atSpot = true;
        }
        if (!atSpot) {
            resident.setStatus(place == 0 ? ResidentStatus.SHOPPING : ResidentStatus.QUEUEING);
            walkTo(spot);
            phase = Phase.TO_REGISTER;
            timer = 0;
            return;
        }
        resident.getNavigation().stop();
        resident.setStatus(ResidentStatus.QUEUEING);
        if (place > 0) return;                          // 前面还有人，站着等

        phase = Phase.PAYING;
        register.markServing(now);
        if (++timer < PAY_TICKS) return;
        checkout(level, current, register);
        register.leaveQueue(resident.getUUID());
        plan = finish(RETRY_AFTER_SUCCESS);
    }

    /** 结账：按现在的价格算钱，钱不够的那几样放回去，其余的付钱带走。 */
    private void checkout(ServerLevel level, Shopping.Plan current, RegisterBlockEntity register) {
        Town town = TownSavedData.get(level).getTown(resident.getTownId());
        ResidentRecord record = town == null ? null : town.residents.get(resident.getUUID());
        if (record == null) return;
        ShopData shop = current.shop().shop;
        int total = 0;
        List<ItemStack> bought = new ArrayList<>();
        for (ItemStack stack : basket) {
            int price = shop.priceOf(stack.getItem());
            int affordable = Math.min(stack.getCount(), (Shopping.foodBudget(town, record) - total) / price);
            if (affordable <= 0) continue;
            ItemStack part = stack.split(affordable);
            total += price * part.getCount();
            bought.add(part);
        }
        int paid = Shopping.pay(town, record, total, true);
        register.addMoney(paid);
        for (ItemStack stack : bought) {
            shop.recordSale(stack.getItem(), stack.getCount());
            Shopping.deliver(town, record, resident, stack);
        }
        basket.removeIf(ItemStack::isEmpty);
        TownSavedData.get(level).setDirty();
        level.playSound(null, register.getBlockPos(), SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.6F, 1.6F);
    }

    /** 把没买成的货放回店里的货架，放不下就丢在脚边。 */
    private void returnGoods(ServerLevel level) {
        List<ShelfBlockEntity> shelves = plan == null ? List.of() : Terrain.findShelves(level, plan.shop().rect);
        for (ItemStack stack : basket) {
            ItemStack left = stack;
            for (ShelfBlockEntity shelf : shelves) {
                if (left.isEmpty()) break;
                left = HopperBlockEntity.addItem(null, shelf, left, null);
            }
            if (!left.isEmpty()) Block.popResource(level, resident.blockPosition(), left);
        }
        basket.clear();
    }

    private void walkTo(BlockPos pos) {
        if (--repathTimer > 0 && !resident.getNavigation().isDone()) return;
        repathTimer = 20;
        resident.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED);
    }
}
