package com.townfolk.block;

import com.townfolk.registry.ModRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 收银台：钱箱里收了多少金币，现在是哪个店员在看店，以及排队等着结账的客人。
 * 店员和排队的人不存档，他们每 tick 都会重新报到，过一会儿没报到就当作走了。
 */
public class RegisterBlockEntity extends BlockEntity {
    /** 超过这么久没报到（tick），就认为人已经不在了。 */
    private static final int PRESENCE_TIMEOUT = 30;

    private int money;
    private UUID clerk;
    private long clerkSeen = Long.MIN_VALUE;
    private long servingSeen = Long.MIN_VALUE;
    private final List<UUID> queue = new ArrayList<>();
    private final List<Long> queueSeen = new ArrayList<>();

    public RegisterBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.REGISTER_ENTITY.get(), pos, state);
    }

    // ------------------------------------------------------------ 钱

    public int getMoney() {
        return money;
    }

    public void addMoney(int amount) {
        setMoney(money + amount);
    }

    /** 把钱箱里的钱全部取走，返回取出的数目。 */
    public int takeAll() {
        int taken = money;
        setMoney(0);
        return taken;
    }

    /** 收银台被拆的时候把钱倒出来。这时方块已经没了，不能再去改它的样子。 */
    public int drain() {
        int taken = money;
        money = 0;
        return taken;
    }

    private void setMoney(int value) {
        money = Math.max(0, value);
        setChanged();
        if (level != null && !level.isClientSide) {
            // 托盘里摆多少钱币跟着钱箱里的钱变
            int shown = money <= 0 ? 0 : money < 20 ? 1 : money < 60 ? 2 : 3;
            BlockState state = getBlockState();
            if (state.getValue(RegisterBlock.COINS) != shown) {
                level.setBlock(worldPosition, state.setValue(RegisterBlock.COINS, shown), 3);
            }
        }
    }

    // ------------------------------------------------------------ 位置

    public Direction facing() {
        return getBlockState().getValue(RegisterBlock.FACING);
    }

    /** 店员站的位置：收银台背面。 */
    public BlockPos clerkSpot() {
        return worldPosition.relative(facing().getOpposite());
    }

    /** 排在第 index 位的客人站的位置：正面依次往外排。 */
    public BlockPos queueSpot(int index) {
        return worldPosition.relative(facing(), index + 1);
    }

    // ------------------------------------------------------------ 店员

    /** 店员站在台后时每 tick 报到一次。 */
    public void tend(UUID clerkId, long now) {
        clerk = clerkId;
        clerkSeen = now;
    }

    public boolean isTended(long now) {
        return now - clerkSeen <= PRESENCE_TIMEOUT;
    }

    /** 正在给排在最前面的客人结账。 */
    public void markServing(long now) {
        servingSeen = now;
    }

    public boolean isServing(long now) {
        return now - servingSeen <= 5;
    }

    // ------------------------------------------------------------ 排队

    /** 客人每 tick 报到一次，返回他现在排第几（0 是正在柜台前的那一位）。 */
    public int joinQueue(UUID customer, long now) {
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (now - queueSeen.get(i) > PRESENCE_TIMEOUT && !queue.get(i).equals(customer)) {
                queue.remove(i);
                queueSeen.remove(i);
            }
        }
        int index = queue.indexOf(customer);
        if (index < 0) {
            queue.add(customer);
            queueSeen.add(now);
            return queue.size() - 1;
        }
        queueSeen.set(index, now);
        return index;
    }

    public void leaveQueue(UUID customer) {
        int index = queue.indexOf(customer);
        if (index >= 0) {
            queue.remove(index);
            queueSeen.remove(index);
        }
    }

    public int queueLength() {
        return queue.size();
    }

    // ------------------------------------------------------------ 存档

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Money", money);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        money = tag.getInt("Money");
    }
}
