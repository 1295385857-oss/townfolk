package com.townfolk.block;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 摆货的方块，有三种样子：靠墙货架（两格高，见 TallShelfBlock）、柜台、集市摊位。
 * 里面能放 9 格东西，其中一部分会以立体模型摆在架上给人看：货架最多 8 种，柜台和摊位最多 4 种。正面朝着放置它的人。
 */
public class ShelfBlock extends Block implements EntityBlock {
    public enum Kind { SHELF, COUNTER, STALL }

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape COUNTER_SHAPE = Block.box(0, 0, 0, 16, 12, 16);
    private static final VoxelShape STALL_SHAPE = Block.box(0, 0, 0, 16, 11, 16);
    // 靠墙货架的背板贴着背面，正面缩进去 3 格
    private static final VoxelShape SHELF_SOUTH = Block.box(0, 0, 0, 16, 16, 13);
    private static final VoxelShape SHELF_NORTH = Block.box(0, 0, 3, 16, 16, 16);
    private static final VoxelShape SHELF_WEST = Block.box(3, 0, 0, 16, 16, 16);
    private static final VoxelShape SHELF_EAST = Block.box(0, 0, 0, 13, 16, 16);

    private final Kind kind;

    public ShelfBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (kind == Kind.COUNTER) return COUNTER_SHAPE;
        if (kind == Kind.STALL) return STALL_SHAPE;
        return switch (state.getValue(FACING)) {
            case NORTH -> SHELF_NORTH;
            case WEST -> SHELF_WEST;
            case EAST -> SHELF_EAST;
            default -> SHELF_SOUTH;
        };
    }

    /**
     * 告诉寻路这里走不过去。它不到一格高，游戏默认会把它当成能踩上去的地方，
     * 居民就会想直接穿过去，结果顶在上面不动。
     */
    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ShelfBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(basePos(state, pos)) instanceof ShelfBlockEntity shelf) {
            serverPlayer.openMenu(shelf);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 装着货的那一格在哪。两格高的货架，货记在下半格里。 */
    protected BlockPos basePos(BlockState state, BlockPos pos) {
        return pos;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof ShelfBlockEntity shelf) {
            Containers.dropContents(level, pos, shelf);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
