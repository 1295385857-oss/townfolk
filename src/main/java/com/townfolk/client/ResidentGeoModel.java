package com.townfolk.client;

import com.townfolk.Townfolk;
import com.townfolk.entity.ResidentEntity;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * 居民的 GeckoLib 模型。模型和动画文件由 tools/gen_animations.py 生成；
 * 皮肤还是玩家皮肤的格式，按职业、性别、外貌变体选择。
 */
public class ResidentGeoModel extends GeoModel<ResidentEntity> {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "geo/resident.geo.json");
    private static final ResourceLocation ANIMATIONS = ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "animations/resident.animation.json");

    private final Map<String, ResourceLocation> textures = new HashMap<>();

    @Override
    public ResourceLocation getModelResource(ResidentEntity resident) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ResidentEntity resident) {
        String outfit = resident.isAdult() ? resident.getProfession().id : "child";
        String key = outfit + (resident.isMale() ? "_m_" : "_f_") + resident.getVariant();
        return textures.computeIfAbsent(key,
                k -> ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "textures/entity/resident/" + k + ".png"));
    }

    @Override
    public ResourceLocation getAnimationResource(ResidentEntity resident) {
        return ANIMATIONS;
    }

    /** 头跟着居民看的方向转。睡着的时候不转。 */
    @Override
    public void setCustomAnimations(ResidentEntity resident, long instanceId, AnimationState<ResidentEntity> state) {
        if (resident.isSleeping()) return;
        GeoBone head = getAnimationProcessor().getBone("head");
        EntityModelData data = state.getData(DataTickets.ENTITY_MODEL_DATA);
        if (head == null || data == null) return;
        // 直接设成绝对角度。动画里不动头，所以不会和动画打架；用“加”的话每一帧都会累加，头会越转越偏
        head.setRotX(Mth.clamp(data.headPitch(), -60.0F, 60.0F) * Mth.DEG_TO_RAD);
        head.setRotY(Mth.clamp(data.netHeadYaw(), -70.0F, 70.0F) * Mth.DEG_TO_RAD);
    }
}
