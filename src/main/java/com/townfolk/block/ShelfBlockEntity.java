package com.townfolk.block;

import com.townfolk.registry.ModRegistry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 货架里装的货。内容会同步给客户端，好把货摆出来给人看。 */
public class ShelfBlockEntity extends BaseContainerBlockEntity {
    public static final int SIZE = 9;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    public ShelfBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.SHELF_ENTITY.get(), pos, state);
    }

    /**
     * 要摆在架上的货：同一种东西合并成一份，数量是总数，按在货架里出现的先后排。
     * 两格高的货架有四层搁板，最多摆 8 种；柜台和摊位最多摆 4 种。
     */
    public List<ItemStack> displayStacks() {
        int limit = getBlockState().getBlock() instanceof TallShelfBlock ? 8 : 4;
        List<ItemStack> shown = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            ItemStack same = null;
            for (ItemStack existing : shown) {
                if (ItemStack.isSameItem(existing, stack)) same = existing;
            }
            if (same != null) {
                same.grow(stack.getCount());
            } else if (shown.size() < limit) {
                shown.add(stack.copy());
            }
        }
        return shown;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new DispenserMenu(containerId, inventory, this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 空格子也要写出去：客户端靠这份数据刷新，不写的话卖空了的货还会留在架上
        ContainerHelper.saveAllItems(tag, items, true, registries);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** 货变了就告诉附近的客户端，架上摆的东西跟着变。 */
    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
