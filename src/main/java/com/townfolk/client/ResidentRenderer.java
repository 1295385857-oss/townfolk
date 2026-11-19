package com.townfolk.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.townfolk.entity.ResidentEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;

/** 居民的渲染器：GeckoLib 模型，右手里画出正拿着的东西，未成年人按年龄缩小。 */
public class ResidentRenderer extends GeoEntityRenderer<ResidentEntity> {
    private static final String ITEM_BONE = "rightItem";

    public ResidentRenderer(EntityRendererProvider.Context context) {
        super(context, new ResidentGeoModel());
        shadowRadius = 0.5F;
        addRenderLayer(new BlockAndItemGeoLayer<>(this) {
            @Override
            protected ItemStack getStackForBone(GeoBone bone, ResidentEntity resident) {
                if (!ITEM_BONE.equals(bone.getName()) || resident.isSleeping()) return null;
                ItemStack stack = resident.getMainHandItem();
                return stack.isEmpty() ? null : stack;
            }

            @Override
            protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, ResidentEntity resident) {
                return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            }

            @Override
            protected void renderStackForBone(PoseStack poseStack, GeoBone bone, ItemStack stack, ResidentEntity resident,
                                              MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
                // 和原版把东西放进手里时一样：先让物品顺着手臂的方向
                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
                super.renderStackForBone(poseStack, bone, stack, resident, bufferSource, partialTick, packedLight, packedOverlay);
            }
        });
    }

    /** 未成年人按年龄缩小。 */
    @Override
    public void scaleModelForRender(float widthScale, float heightScale, PoseStack poseStack, ResidentEntity resident, BakedGeoModel model,
                                    boolean isReRender, float partialTick, int packedLight, int packedOverlay) {
        float scale = resident.getAgeScale();
        super.scaleModelForRender(widthScale * scale, heightScale * scale, poseStack, resident, model, isReRender, partialTick, packedLight, packedOverlay);
    }
}
