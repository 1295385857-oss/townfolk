package com.townfolk.client;

import com.townfolk.network.TownView;
import com.townfolk.town.Town;
import com.townfolk.town.ZoneRect;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3f;

/**
 * 上帝视角：把镜头从玩家身上拿下来，放到小镇上空斜着往下看。
 * 玩家的身体留在原地不动，这里只管镜头，以及“屏幕上的点对应世界里的哪一格”的换算。
 */
public final class GodView {
    /** 镜头俯角（度）。 */
    private static final float PITCH = 55.0F;
    /** 镜头朝北，屏幕上方就是北。 */
    private static final float YAW = 180.0F;
    /** 上帝视角里使用固定的视野角，这样鼠标位置换算成世界方向时不受玩家设置影响。 */
    private static final double FOV = 60.0;
    private static final double MIN_DISTANCE = 12.0;
    private static final double MAX_DISTANCE = 80.0;
    private static final double START_DISTANCE = 34.0;
    private static final double PICK_RANGE = 300.0;

    private static boolean active;
    @Nullable private static Entity camera;
    private static CameraType previousCameraType = CameraType.FIRST_PERSON;
    private static BlockPos townCenter = BlockPos.ZERO;
    /** 镜头盯着的地面上的点。 */
    private static double focusX;
    private static double focusY;
    private static double focusZ;
    private static double distance = START_DISTANCE;

    // 要画在地面上的东西，由 GodViewScreen 更新
    private static List<TownView.ZoneView> zones = List.of();
    @Nullable private static ZoneRect preview;
    private static int previewColor;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(GodView::onClientTick);
        NeoForge.EVENT_BUS.addListener(GodView::onComputeFov);
        NeoForge.EVENT_BUS.addListener(GodView::onRenderGui);
        NeoForge.EVENT_BUS.addListener(GodView::onRenderHand);
        NeoForge.EVENT_BUS.addListener(GodView::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ZoneRenderer::onRenderStage);
    }

    public static boolean isActive() {
        return active;
    }

    // ------------------------------------------------------------ 进入和退出

    public static void enter(BlockPos center) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (active || level == null || mc.player == null) return;

        townCenter = center;
        focusX = center.getX() + 0.5;
        focusZ = center.getZ() + 0.5;
        focusY = surfaceY(level, focusX, focusZ);
        distance = START_DISTANCE;

        // 一个只存在于客户端、不加入世界的实体，专门用来当镜头
        Entity entity = new Entity(EntityType.MARKER, level) {
            @Override
            protected void defineSynchedData(SynchedEntityData.Builder builder) {}

            @Override
            protected void readAdditionalSaveData(CompoundTag tag) {}

            @Override
            protected void addAdditionalSaveData(CompoundTag tag) {}
        };
        camera = entity;
        placeCamera(entity);
        entity.setOldPosAndRot();

        previousCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.setCameraEntity(entity);
        active = true;
    }

    public static void exit() {
        if (!active) return;
        active = false;
        camera = null;
        zones = List.of();
        preview = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        mc.options.setCameraType(previousCameraType);
    }

    // ------------------------------------------------------------ 镜头控制

    /** 平移镜头盯着的点，不能移出小镇范围。 */
    public static void pan(double dx, double dz) {
        focusX = Mth.clamp(focusX + dx, townCenter.getX() - Town.RADIUS, townCenter.getX() + Town.RADIUS + 1.0);
        focusZ = Mth.clamp(focusZ + dz, townCenter.getZ() - Town.RADIUS, townCenter.getZ() + Town.RADIUS + 1.0);
    }

    public static void zoom(double amount) {
        distance = Mth.clamp(distance + amount, MIN_DISTANCE, MAX_DISTANCE);
    }

    public static double distance() {
        return distance;
    }

    private static void placeCamera(Entity entity) {
        double pitch = Math.toRadians(PITCH);
        entity.setPos(focusX, focusY + distance * Math.sin(pitch), focusZ + distance * Math.cos(pitch));
        entity.setYRot(YAW);
        entity.setXRot(PITCH);
    }

    private static double surfaceY(ClientLevel level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        Entity entity = camera;
        if (mc.level == null || mc.player == null || entity == null || !(mc.screen instanceof GodViewScreen)) {
            exit();
            return;
        }
        // 地面有起伏时，镜头高度慢慢跟上
        focusY += (surfaceY(mc.level, focusX, focusZ) - focusY) * 0.15;
        entity.setOldPosAndRot();
        placeCamera(entity);
    }

    // ------------------------------------------------------------ 屏幕和世界之间的换算

    /** 屏幕上的一点（界面坐标）指着世界里的哪个方块。指着天空时返回 null。 */
    @Nullable
    public static BlockHitResult pick(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.level == null) return null;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vector3f look = cam.getLookVector();
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();

        double tan = Math.tan(Math.toRadians(FOV) / 2.0);
        double aspect = (double) screenWidth / screenHeight;
        double right = (2.0 * mouseX / screenWidth - 1.0) * tan * aspect;
        double above = (1.0 - 2.0 * mouseY / screenHeight) * tan;
        Vec3 direction = new Vec3(
                look.x() - left.x() * right + up.x() * above,
                look.y() - left.y() * right + up.y() * above,
                look.z() - left.z() * right + up.z() * above).normalize();

        Vec3 from = cam.getPosition();
        BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(direction.scale(PICK_RANGE)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? null : hit;
    }

    /** 世界里的一点画在屏幕上的位置（界面坐标）。在镜头背后时返回 null。 */
    @Nullable
    public static float[] project(double x, double y, double z, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.getMainCamera();
        Vector3f look = cam.getLookVector();
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        Vec3 offset = new Vec3(x, y, z).subtract(cam.getPosition());

        double depth = offset.x * look.x() + offset.y * look.y() + offset.z * look.z();
        if (depth < 0.1) return null;
        double tan = Math.tan(Math.toRadians(FOV) / 2.0);
        double aspect = (double) screenWidth / screenHeight;
        double right = -(offset.x * left.x() + offset.y * left.y() + offset.z * left.z()) / (depth * tan * aspect);
        double above = (offset.x * up.x() + offset.y * up.y() + offset.z * up.z()) / (depth * tan);
        return new float[] {(float) ((right + 1.0) / 2.0 * screenWidth), (float) ((1.0 - above) / 2.0 * screenHeight)};
    }

    // ------------------------------------------------------------ 画在地面上的内容

    public static void setZones(List<TownView.ZoneView> newZones) {
        zones = newZones;
    }

    /** 正在拖出来的新分区，rect 为 null 表示没有。 */
    public static void setPreview(@Nullable ZoneRect rect, int color) {
        preview = rect;
        previewColor = color;
    }

    static List<TownView.ZoneView> zones() {
        return zones;
    }

    @Nullable
    static ZoneRect preview() {
        return preview;
    }

    static int previewColor() {
        return previewColor;
    }

    static BlockPos townCenter() {
        return townCenter;
    }

    // ------------------------------------------------------------ 事件

    private static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (active) event.setFOV(FOV);
    }

    /** 上帝视角里不显示快捷栏、准星这些。 */
    private static void onRenderGui(RenderGuiEvent.Pre event) {
        if (active) event.setCanceled(true);
    }

    /** 也不显示玩家自己的手。 */
    private static void onRenderHand(RenderHandEvent event) {
        if (active) event.setCanceled(true);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
        camera = null;
        zones = List.of();
        preview = null;
    }

    private GodView() {}
}
