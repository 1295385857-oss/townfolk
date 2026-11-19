package com.townfolk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.townfolk.block.ShelfBlock;
import com.townfolk.block.ShelfBlockEntity;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** 把货架里的货摆在架上画出来：每种货占一个位置，存货多就多摆几个。 */
public class ShelfRenderer implements BlockEntityRenderer<ShelfBlockEntity> {
    /** 每种货架上四个摆货位置：{x, y, z}，单位是格，正面朝南（+z）。y 是搁板表面的高度。 */
    private static final float[][] SHELF_SLOTS = {
            // 货架有两格高、四层搁板，从下往上每层左右各一个位置
            {4.5F / 16, 1F / 16, 7F / 16}, {11.5F / 16, 1F / 16, 7F / 16},
            {4.5F / 16, 8F / 16, 7F / 16}, {11.5F / 16, 8F / 16, 7F / 16},
            {4.5F / 16, 17F / 16, 7F / 16}, {11.5F / 16, 17F / 16, 7F / 16},
            {4.5F / 16, 24F / 16, 7F / 16}, {11.5F / 16, 24F / 16, 7F / 16},
    };
    private static final float[][] COUNTER_SLOTS = {
            {3.2F / 16, 12F / 16, 8F / 16}, {8F / 16, 12F / 16, 8F / 16}, {12.8F / 16, 12F / 16, 8F / 16},
            {8F / 16, 4F / 16, 8F / 16},
    };
    private static final float[][] STALL_SLOTS = {
            {4.5F / 16, 11F / 16, 5.5F / 16}, {11.5F / 16, 11F / 16, 5.5F / 16},
            {4.5F / 16, 11F / 16, 11.5F / 16}, {11.5F / 16, 11F / 16, 11.5F / 16},
    };
    /** 同一种货摆 1、2、3 个时，每个相对位置中心的偏移 {x, z} 和转过的角度。 */
    private static final float[][][] PILES = {
            {{0.0F, 0.0F, 12.0F}},
            {{-0.07F, 0.03F, -18.0F}, {0.07F, -0.03F, 24.0F}},
            {{-0.08F, 0.05F, -18.0F}, {0.08F, 0.04F, 24.0F}, {0.0F, -0.08F, 70.0F}},
    };
    private static final float MODEL_SCALE = 0.5F;

    private final ItemRenderer itemRenderer;

    public ShelfRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(ShelfBlockEntity shelf, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        List<ItemStack> goods = shelf.displayStacks();
        if (goods.isEmpty()) return;
        BlockState state = shelf.getBlockState();
        if (!(state.getBlock() instanceof ShelfBlock block)) return;
        float[][] slots = switch (block.kind()) {
            case COUNTER -> COUNTER_SLOTS;
            case STALL -> STALL_SLOTS;
            default -> SHELF_SLOTS;
        };
        Direction facing = state.getValue(ShelfBlock.FACING);

        poseStack.pushPose();
        // 模型是按正面朝南做的，转到实际的朝向
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(-0.5F, 0.0F, -0.5F);

        for (int i = 0; i < goods.size() && i < slots.length; i++) {
            ItemStack stack = goods.get(i);
            int count = stack.getCount() >= 24 ? 3 : stack.getCount() >= 8 ? 2 : 1;
            for (float[] offset : PILES[count - 1]) {
                poseStack.pushPose();
                poseStack.translate(slots[i][0] + offset[0], slots[i][1], slots[i][2] + offset[1]);
                poseStack.mulPose(Axis.YP.rotationDegrees(offset[2]));
                renderOne(shelf, stack, poseStack, bufferSource, packedLight, packedOverlay);
                poseStack.popPose();
            }
        }
        poseStack.popPose();
    }

    /** 货架的上半格也要画货，所以画的范围是上下两格，不然只看得到上半格时货会消失。 */
    @Override
    public AABB getRenderBoundingBox(ShelfBlockEntity shelf) {
        BlockPos pos = shelf.getBlockPos();
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 2.0, pos.getZ() + 1.0);
    }

    /** 在当前位置画一件货，底面贴着搁板。 */
    private void renderOne(ShelfBlockEntity shelf, ItemStack stack, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BakedModel goods = GoodsModels.get(stack.getItem());
        if (goods != null) {
            // 专门做的立体模型：模型底面在 y=0，渲染时会以模型中心为原点，所以先抬高半个模型
            poseStack.translate(0.0F, 0.5F * MODEL_SCALE, 0.0F);
            poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
            itemRenderer.render(stack, ItemDisplayContext.NONE, false, poseStack, bufferSource, packedLight, packedOverlay, goods);
        } else if (stack.getItem() instanceof BlockItem) {
            // 方块本来就是立体的：摆成一个小方块
            poseStack.scale(1.3F, 1.3F, 1.3F);
            poseStack.translate(0.0F, -1.0F / 16, 0.0F);
            itemRenderer.renderStatic(stack, ItemDisplayContext.GROUND, packedLight, packedOverlay, poseStack, bufferSource, shelf.getLevel(), 0);
        } else {
            // 其他物品没有立体模型，按原版的样子立着摆
            poseStack.scale(0.8F, 0.8F, 0.8F);
            poseStack.translate(0.0F, 2.0F / 16, 0.0F);
            itemRenderer.renderStatic(stack, ItemDisplayContext.GROUND, packedLight, packedOverlay, poseStack, bufferSource, shelf.getLevel(), 0);
        }
    }
}
