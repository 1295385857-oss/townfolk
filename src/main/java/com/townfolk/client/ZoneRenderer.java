package com.townfolk.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.townfolk.network.TownView;
import com.townfolk.town.Industry;
import com.townfolk.town.Town;
import com.townfolk.town.ZoneRect;
import com.townfolk.town.ZoneType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** 在上帝视角里把分区画在地面上：半透明的颜色贴着地表，边缘一圈更深。 */
public final class ZoneRenderer {
    private static final int FILL_ALPHA = 56;
    private static final int EDGE_ALPHA = 160;
    private static final int PREVIEW_FILL_ALPHA = 96;
    private static final int PREVIEW_EDGE_ALPHA = 210;
    private static final int WATER_PIT_COLOR = 0x2F6FE0;
    private static final int TOWN_BORDER_COLOR = 0xFFFFFF;

    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !GodView.isActive()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 cam = event.getCamera().getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0F, -10.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // 小镇范围的边界：一圈淡淡的白线
        BlockPos center = GodView.townCenter();
        ZoneRect townRect = new ZoneRect(center.getX() - Town.RADIUS, center.getZ() - Town.RADIUS,
                center.getX() + Town.RADIUS, center.getZ() + Town.RADIUS);
        addRect(buffer, level, cam, townRect, TOWN_BORDER_COLOR, 0, 70);

        for (TownView.ZoneView zone : GodView.zones()) {
            int color = ZoneType.byOrdinal(zone.type()).color;
            addRect(buffer, level, cam, zone.rect(), color, FILL_ALPHA, EDGE_ALPHA);
            if (Industry.byOrdinal(zone.industry()).isCrop()) {
                for (int[] pit : zone.rect().waterPits()) {
                    addCell(buffer, level, cam, pit[0], pit[1], 0.2F, 0.06F, WATER_PIT_COLOR, 230);
                }
            }
        }
        ZoneRect preview = GodView.preview();
        if (preview != null) {
            addRect(buffer, level, cam, preview, GodView.previewColor(), PREVIEW_FILL_ALPHA, PREVIEW_EDGE_ALPHA);
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** 给矩形里的每一格在地表上贴一片颜色。fillAlpha 为 0 时只画边缘一圈。 */
    private static void addRect(BufferBuilder buffer, ClientLevel level, Vec3 cam, ZoneRect rect, int color, int fillAlpha, int edgeAlpha) {
        for (int x = rect.minX(); x <= rect.maxX(); x++) {
            for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
                boolean edge = x == rect.minX() || x == rect.maxX() || z == rect.minZ() || z == rect.maxZ();
                int alpha = edge ? edgeAlpha : fillAlpha;
                if (alpha > 0) {
                    addCell(buffer, level, cam, x, z, 0.0F, 0.02F, color, alpha);
                }
            }
        }
    }

    private static void addCell(BufferBuilder buffer, ClientLevel level, Vec3 cam, int x, int z, float inset, float lift, int color, int alpha) {
        if (!level.hasChunk(x >> 4, z >> 4)) return;
        float y = (float) (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + lift - cam.y);
        float x0 = (float) (x + inset - cam.x);
        float x1 = (float) (x + 1 - inset - cam.x);
        float z0 = (float) (z + inset - cam.z);
        float z1 = (float) (z + 1 - inset - cam.z);
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        buffer.addVertex(x0, y, z0).setColor(r, g, b, alpha);
        buffer.addVertex(x0, y, z1).setColor(r, g, b, alpha);
        buffer.addVertex(x1, y, z1).setColor(r, g, b, alpha);
        buffer.addVertex(x1, y, z0).setColor(r, g, b, alpha);
    }

    private ZoneRenderer() {}
}
