package com.townfolk.block;

import com.townfolk.town.Town;
import com.townfolk.town.TownManager;
import com.townfolk.town.TownSavedData;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 镇公所：放下就建立小镇，右键打开管理界面，拆除则小镇解散。 */
public class TownHallBlock extends Block {
    public TownHallBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof ServerPlayer player) {
            if (TownManager.foundTown(serverLevel, pos, player) == null) {
                // 离别的小镇太近：把方块收回去
                player.sendSystemMessage(Component.translatable("townfolk.message.too_close", Town.RADIUS * 2));
                level.removeBlock(pos, false);
                if (!player.getAbilities().instabuild) {
                    Block.popResource(level, pos, new ItemStack(this));
                }
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            Town town = TownSavedData.get(serverLevel).getTownAt(pos);
            if (town == null) {
                // 不是玩家亲手放的镇公所（比如用指令放的）：第一个右键它的人成为镇长
                town = TownManager.foundTown(serverLevel, pos, serverPlayer);
                if (town == null) {
                    serverPlayer.sendSystemMessage(Component.translatable("townfolk.message.too_close", Town.RADIUS * 2));
                    return InteractionResult.CONSUME;
                }
            }
            TownManager.sendView(serverLevel, town, serverPlayer, true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            Town town = TownSavedData.get(serverLevel).getTownAt(pos);
            if (town != null) {
                TownManager.dissolveTown(serverLevel, town);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
