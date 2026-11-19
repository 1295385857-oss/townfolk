package com.townfolk.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.townfolk.network.TownActionPayload;
import com.townfolk.network.TownView;
import com.townfolk.network.ZoneActionPayload;
import com.townfolk.town.Industry;
import com.townfolk.town.Zone;
import com.townfolk.town.ZoneRect;
import com.townfolk.town.ZoneType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 上帝视角下的规划界面。画面是真实的世界，这个界面只负责底部的工具栏、
 * 鼠标在地面上拖出分区，以及右键分区后弹出的菜单（选产业或房型、定工作人数、看住户）。
 */
public class GodViewScreen extends Screen {
    private static final int BACKDROP = 0xB0140E08;
    private static final int INVALID_COLOR = 0xE03030;
    private static final int DANGER_TEXT = 0xFFA62A22;

    private static final int BAR_HEIGHT = 18;
    private static final int MENU_WIDTH = 176;
    private static final int MENU_PADDING = 7;
    private static final int OPTION_HEIGHT = 14;
    private static final int REFRESH_INTERVAL = 20;
    /** 居民区菜单里最多列出几户。 */
    private static final int MAX_HOME_LINES = 6;
    /** 镜头拉到这么近时，才给楼房里的每一户单独标名字。 */
    private static final double UNIT_LABEL_DISTANCE = 40.0;

    private TownView view;
    private boolean planning;
    @Nullable private ZoneType brush;
    /** 正在拖出的分区的起点和终点，{x, z}。 */
    @Nullable private int[] dragStart;
    @Nullable private int[] dragEnd;

    @Nullable private UUID menuZone;
    /** 玩家在菜单第一步里点的用途（农场还是树场）。只是界面上的选择，选了具体种什么才真正生效。 */
    @Nullable private String menuGroup;
    private int menuAnchorX;
    private int menuAnchorY;
    private final List<MenuItem> menuItems = new ArrayList<>();
    private final List<MenuText> menuTexts = new ArrayList<>();
    private int menuLeft;
    private int menuTop;
    private int menuHeight;

    private Component message = Component.empty();
    private int messageTicks;
    private int refreshTimer;

    private int barLeft;
    private int barTop;
    private int barWidth;
    @Nullable private PlaqueButton planButton;
    private final PlaqueButton[] brushButtons = new PlaqueButton[ZoneType.values().length];

    /** 菜单里可以点的一项，坐标相对菜单左上角。 */
    private record MenuItem(int x, int y, int width, int height, Component label, boolean selected, boolean danger, boolean enabled,
                            Runnable action) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    /** 菜单里的一行字，坐标相对菜单左上角。 */
    private record MenuText(int x, int y, Component text, int color) {}

    public GodViewScreen(TownView view) {
        super(Component.literal(view.name()));
        this.view = view;
    }

    public UUID townId() {
        return view.id();
    }

    public void updateView(TownView newView) {
        view = newView;
        GodView.setZones(newView.zones());
        if (menuZone != null && newView.zone(menuZone) == null) {
            menuZone = null;
        }
        refreshButtons();
    }

    // ------------------------------------------------------------ 工具栏

    @Override
    protected void init() {
        if (!GodView.isActive()) {
            GodView.enter(view.center());
        }
        GodView.setZones(view.zones());

        int[] widths = {40, 46, 54, 54, 46, 46, 40};      // 规划、五类分区、返回
        int gap = 4;
        int groupGap = 8;
        barWidth = gap * (ZoneType.values().length - 1) + groupGap * 2;
        for (int width : widths) barWidth += width;
        barLeft = (width - barWidth) / 2;
        barTop = height - BAR_HEIGHT - 10;

        int x = barLeft;
        planButton = addRenderableWidget(new PlaqueButton(x, barTop, widths[0], BAR_HEIGHT, Component.translatable("townfolk.gui.plan"), b -> {
            planning = !planning;
            if (!planning) cancelDrag();
            refreshButtons();
        }));
        x += widths[0] + groupGap;
        for (ZoneType type : ZoneType.values()) {
            int i = type.ordinal();
            brushButtons[i] = addRenderableWidget(new PlaqueButton(x, barTop, widths[i + 1], BAR_HEIGHT, type.displayName(), b -> {
                brush = type;
                refreshButtons();
            }));
            x += widths[i + 1] + (i == ZoneType.values().length - 1 ? groupGap : gap);
        }
        addRenderableWidget(new PlaqueButton(x, barTop, widths[widths.length - 1], BAR_HEIGHT, Component.translatable("townfolk.gui.back"), b -> onClose()));
        refreshButtons();
    }

    private void refreshButtons() {
        boolean mayor = view.isMayor();
        if (planButton != null) {
            planButton.active = mayor;
            planButton.selected = planning;
        }
        for (ZoneType type : ZoneType.values()) {
            PlaqueButton button = brushButtons[type.ordinal()];
            if (button == null) continue;
            button.active = mayor && planning;
            button.selected = planning && brush == type;
        }
    }

    @Override
    public void removed() {
        GodView.exit();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------ 每 tick

    @Override
    public void tick() {
        super.tick();
        Minecraft mc = Minecraft.getInstance();
        int dx = (isDown(mc.options.keyRight, GLFW.GLFW_KEY_RIGHT) ? 1 : 0) - (isDown(mc.options.keyLeft, GLFW.GLFW_KEY_LEFT) ? 1 : 0);
        int dz = (isDown(mc.options.keyDown, GLFW.GLFW_KEY_DOWN) ? 1 : 0) - (isDown(mc.options.keyUp, GLFW.GLFW_KEY_UP) ? 1 : 0);
        if (dx != 0 || dz != 0) {
            double speed = GodView.distance() * 0.03;          // 拉得越远移动越快
            GodView.pan(dx * speed, dz * speed);
        }
        if (messageTicks > 0) messageTicks--;
        if (++refreshTimer >= REFRESH_INTERVAL) {
            refreshTimer = 0;
            PacketDistributor.sendToServer(TownActionPayload.refresh(view.id()));
        }
    }

    private boolean isDown(KeyMapping mapping, int arrowKey) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (InputConstants.isKeyDown(window, arrowKey)) return true;
        InputConstants.Key key = mapping.getKey();
        return key.getType() == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(window, key.getValue());
    }

    private void showMessage(Component text) {
        message = text;
        messageTicks = 80;
    }

    // ------------------------------------------------------------ 鼠标

    /** 鼠标指着的地面格子 {x, z}，指着天空时为 null。 */
    @Nullable
    private int[] pickColumn(double mouseX, double mouseY) {
        BlockHitResult hit = GodView.pick(mouseX, mouseY, width, height);
        return hit == null ? null : new int[] {hit.getBlockPos().getX(), hit.getBlockPos().getZ()};
    }

    private boolean overToolbar(double mouseX, double mouseY) {
        return mouseX >= barLeft - 5 && mouseX < barLeft + barWidth + 5 && mouseY >= barTop - 5;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menuZone != null) {
            double localX = mouseX - menuLeft;
            double localY = mouseY - menuTop;
            for (MenuItem item : menuItems) {
                if (item.contains(localX, localY)) {
                    if (item.enabled && view.isMayor()) item.action.run();
                    return true;
                }
            }
            boolean inside = localX >= 0 && localX < MENU_WIDTH && localY >= 0 && localY < menuHeight;
            if (!inside) menuZone = null;
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button) || overToolbar(mouseX, mouseY)) return true;

        int[] column = pickColumn(mouseX, mouseY);
        if (column == null) return true;
        if (button == 0 && planning && brush != null && view.isMayor()) {
            dragStart = column;
            dragEnd = column;
        } else if (button == 1) {
            TownView.ZoneView zone = view.zoneAt(column[0], column[1]);
            if (zone != null) {
                menuZone = zone.id();
                menuGroup = null;
                menuAnchorX = (int) mouseX;
                menuAnchorY = (int) mouseY;
                cancelDrag();
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragStart != null && button == 0) {
            int[] column = pickColumn(mouseX, mouseY);
            if (column != null) dragEnd = column;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragStart != null && button == 0) {
            ZoneRect rect = dragRect();
            ZoneType type = brush;
            cancelDrag();
            if (rect != null && type != null) {
                String problem = problemWith(rect);
                if (problem != null) {
                    showMessage(Component.translatable(problem, ZoneRect.MIN_SIZE, ZoneRect.MAX_SIZE));
                } else {
                    PacketDistributor.sendToServer(ZoneActionPayload.create(view.id(), type.ordinal(), rect));
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        GodView.zoom(-scrollY * 4.0);
        return true;
    }

    private void cancelDrag() {
        dragStart = null;
        dragEnd = null;
        GodView.setPreview(null, 0);
    }

    @Nullable
    private ZoneRect dragRect() {
        if (dragStart == null || dragEnd == null) return null;
        return ZoneRect.of(dragStart[0], dragStart[1], dragEnd[0], dragEnd[1]);
    }

    @Nullable
    private String problemWith(ZoneRect rect) {
        List<ZoneRect> others = new ArrayList<>();
        for (TownView.ZoneView zone : view.zones()) {
            others.add(zone.rect());
        }
        return rect.problem(view.center().getX(), view.center().getZ(), others);
    }

    // ------------------------------------------------------------ 绘制

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不要原版的模糊和压暗：背后就是要看的世界。这里只画工具栏的木头底板。
        graphics.fill(barLeft - 6, barTop - 6, barLeft + barWidth + 6, barTop + BAR_HEIGHT + 6, TownScreen.OUTLINE);
        graphics.fill(barLeft - 5, barTop - 5, barLeft + barWidth + 5, barTop + BAR_HEIGHT + 5, TownScreen.FRAME);
        graphics.fill(barLeft - 5, barTop - 5, barLeft + barWidth + 5, barTop - 4, TownScreen.FRAME_LIGHT);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderZoneLabels(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        // 每个分区按钮底下一条它的颜色
        for (ZoneType type : ZoneType.values()) {
            PlaqueButton button = brushButtons[type.ordinal()];
            if (button == null) continue;
            int alpha = button.active ? 0xFF000000 : 0x60000000;
            graphics.fill(button.getX() + 3, button.getY() + BAR_HEIGHT - 4, button.getX() + button.getWidth() - 3, button.getY() + BAR_HEIGHT - 2, alpha | type.color);
        }

        renderHints(graphics);
        renderDrag(graphics, mouseX, mouseY);
        if (menuZone != null) renderMenu(graphics, mouseX, mouseY);
    }

    private void renderHints(GuiGraphics graphics) {
        Component hint = planning && brush != null
                ? Component.translatable("townfolk.gui.hint.drag", brush.displayName())
                : Component.translatable(planning ? "townfolk.gui.hint.pick_type" : "townfolk.gui.hint.controls");
        drawTag(graphics, hint, 6, 6, 0xFFFFFFFF);
        if (!view.isMayor()) {
            drawTag(graphics, Component.translatable("townfolk.gui.hint.not_mayor"), 6, 20, 0xFFFFD080);
        } else if (messageTicks > 0) {
            drawTag(graphics, message, 6, 20, 0xFFFF8080);
        }
    }

    private void renderDrag(GuiGraphics graphics, int mouseX, int mouseY) {
        ZoneRect rect = dragRect();
        if (rect == null || brush == null) return;
        boolean valid = problemWith(rect) == null;
        GodView.setPreview(rect, valid ? brush.color : INVALID_COLOR);
        Component size = Component.literal(rect.width() + " × " + rect.depth());
        drawTag(graphics, size, mouseX + 10, mouseY + 10, valid ? 0xFFFFFFFF : 0xFFFF8080);
    }

    /** 在每个分区正中标出它是什么；镜头够近时，再给楼房里的每一户标上住的是谁。 */
    private void renderZoneLabels(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        boolean showUnits = GodView.distance() <= UNIT_LABEL_DISTANCE;
        for (TownView.ZoneView zone : view.zones()) {
            ZoneRect rect = zone.rect();
            int cx = (rect.minX() + rect.maxX()) / 2;
            int cz = (rect.minZ() + rect.maxZ()) / 2;
            double y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz) + 0.5;
            drawWorldTag(graphics, zoneLabel(zone), cx + 0.5, y, cz + 0.5, 0xFFFFFFFF);

            if (showUnits && Industry.byOrdinal(zone.industry()) == Industry.APARTMENT) {
                for (TownView.HomeView home : zone.homes()) {
                    Component label = home.names().isEmpty()
                            ? Component.translatable("townfolk.gui.label.unit_empty")
                            : Component.literal(home.names().size() == 1 ? home.names().get(0) : home.names().get(0) + " +" + (home.names().size() - 1));
                    drawWorldTag(graphics, label, home.pos().getX() + 0.5, home.pos().getY() + 1.6, home.pos().getZ() + 0.5,
                            home.names().isEmpty() ? 0xFFB8D8FF : 0xFFFFE9A8);
                }
            }
        }
    }

    private void drawWorldTag(GuiGraphics graphics, Component label, double x, double y, double z, int color) {
        float[] screen = GodView.project(x, y, z, width, height);
        if (screen == null) return;
        drawTag(graphics, label, (int) screen[0] - font.width(label) / 2, (int) screen[1] - 4, color);
    }

    private static Component zoneLabel(TownView.ZoneView zone) {
        ZoneType type = ZoneType.byOrdinal(zone.type());
        Industry industry = Industry.byOrdinal(zone.industry());
        if (type == ZoneType.RESIDENTIAL) {
            if (industry == Industry.APARTMENT) {
                return Component.translatable("townfolk.gui.label.apartment", zone.homes().size(), zone.occupantCount());
            }
            if (industry == Industry.DETACHED) {
                if (zone.occupantCount() == 0) return Component.translatable("townfolk.gui.label.detached_empty", zone.bedCount());
                return Component.translatable("townfolk.gui.label.detached", zone.homes().get(0).names().get(0), zone.occupantCount(), zone.bedCount());
            }
            return Component.translatable("townfolk.gui.label.no_housing");
        }
        if (industry == Industry.NONE) return type.displayName();
        // 农业区写“农场 · 小麦”“树场 · 橡树”；其他的写“商业区 · 商店”
        Component kind = type == ZoneType.AGRICULTURE ? Component.translatable("townfolk.gui.group." + industry.group) : type.displayName();
        return Component.translatable("townfolk.gui.zone_label", kind, industry.displayName());
    }

    private void drawTag(GuiGraphics graphics, Component text, int x, int y, int color) {
        graphics.fill(x - 3, y - 2, x + font.width(text) + 3, y + 10, BACKDROP);
        graphics.drawString(font, text, x, y, color, false);
    }

    // ------------------------------------------------------------ 右键菜单

    private void send(ZoneActionPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** 文字太长时截断并加省略号。 */
    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }

    /** 把一组选项排成一行（放不下就换行），返回最后一行底边的 y。 */
    private int addOptions(TownView.ZoneView zone, List<Industry> options, Industry current, int y) {
        int innerWidth = MENU_WIDTH - MENU_PADDING * 2;
        int x = MENU_PADDING;
        for (Industry industry : options) {
            Component label = industry.displayName();
            int optionWidth = font.width(label) + 10;
            if (x + optionWidth > MENU_PADDING + innerWidth) {
                x = MENU_PADDING;
                y += OPTION_HEIGHT + 2;
            }
            boolean selected = industry == current;
            // 产业再点一次是停掉；房型不能点掉，免得一下把住户全清空
            Industry target = selected ? Industry.NONE : industry;
            boolean enabled = !(selected && industry.isHousing());
            menuItems.add(new MenuItem(x, y, optionWidth, OPTION_HEIGHT, label, selected, false, enabled,
                    () -> send(ZoneActionPayload.setIndustry(view.id(), zone.id(), target.ordinal()))));
            x += optionWidth + 3;
        }
        return y + OPTION_HEIGHT;
    }

    /**
     * 点一下菜单里文字是 label 的那一项，和玩家用鼠标点它的效果一样。
     * 没有这一项或者它现在不能点时返回 false。给自动截图测试用。
     */
    public boolean pressMenuItem(String label) {
        for (MenuItem item : menuItems) {
            if (item.label.getString().equals(label)) {
                if (!item.enabled || !view.isMayor()) return false;
                item.action.run();
                return true;
            }
        }
        return false;
    }

    /** 按当前数据排好菜单里的内容，返回菜单的总高度。 */
    private int layoutMenu(TownView.ZoneView zone) {
        menuItems.clear();
        menuTexts.clear();
        ZoneType type = ZoneType.byOrdinal(zone.type());
        Industry current = Industry.byOrdinal(zone.industry());
        int innerWidth = MENU_WIDTH - MENU_PADDING * 2;
        int y = MENU_PADDING;

        ZoneRect rect = zone.rect();
        menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.zone_title", type.displayName(), rect.width(), rect.depth()), TownScreen.INK));
        y += 14;

        List<Industry> options = Industry.optionsFor(type);
        List<String> groups = new ArrayList<>();
        for (Industry industry : options) {
            if (!groups.contains(industry.group)) groups.add(industry.group);
        }
        if (groups.size() > 1) {
            // 分两步：先选用途（农场还是树场），选了之后才列出具体种什么
            String active = menuGroup != null ? menuGroup : current == Industry.NONE ? null : current.group;
            menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.purpose"), TownScreen.INK_FADED));
            y += 11;
            int x = MENU_PADDING;
            for (String group : groups) {
                Component label = Component.translatable("townfolk.gui.group." + group);
                int optionWidth = font.width(label) + 14;
                menuItems.add(new MenuItem(x, y, optionWidth, OPTION_HEIGHT, label, group.equals(active), false, true, () -> menuGroup = group));
                x += optionWidth + 3;
            }
            y += OPTION_HEIGHT + 4;
            if (active != null) {
                List<Industry> inGroup = new ArrayList<>();
                for (Industry industry : options) {
                    if (industry.group.equals(active)) inGroup.add(industry);
                }
                menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.pick." + active), TownScreen.INK_FADED));
                y += 11;
                y = addOptions(zone, inGroup, current, y) + 6;
            } else {
                y += 2;
            }
        } else if (!options.isEmpty()) {
            menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.group." + groups.get(0)), TownScreen.INK_FADED));
            y += 11;
            y = addOptions(zone, options, current, y) + 6;
        }

        if (current.hasWorkers()) {
            menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.workers"), TownScreen.INK_FADED));
            y += 11;
            int target = zone.workerTarget();
            menuItems.add(new MenuItem(MENU_PADDING, y, 14, OPTION_HEIGHT, Component.literal("-"), true, false, target > 0,
                    () -> send(ZoneActionPayload.setWorkers(view.id(), zone.id(), target - 1))));
            String number = String.valueOf(target);
            menuTexts.add(new MenuText(MENU_PADDING + 24 - font.width(number) / 2, y + 3, Component.literal(number), TownScreen.INK));
            menuItems.add(new MenuItem(MENU_PADDING + 34, y, 14, OPTION_HEIGHT, Component.literal("+"), true, false, target < Zone.MAX_WORKERS,
                    () -> send(ZoneActionPayload.setWorkers(view.id(), zone.id(), target + 1))));
            int filled = zone.workers().size();
            Component state = filled >= target
                    ? Component.translatable("townfolk.gui.workers_filled", filled)
                    : Component.translatable("townfolk.gui.workers_short", filled, target - filled);
            menuTexts.add(new MenuText(MENU_PADDING + 56, y + 3, state, TownScreen.INK));
            y += OPTION_HEIGHT + 3;
            if (!zone.workers().isEmpty()) {
                menuTexts.add(new MenuText(MENU_PADDING, y, Component.literal(fit(String.join("、", zone.workers()), innerWidth)), TownScreen.INK_FADED));
                y += 11;
            }
            // 收获要存进分区里的箱子，没有箱子就没处放
            if (type == ZoneType.COMMERCIAL) {
                // 商店要有收银台，店员才有地方站，才开得了门
                menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.shelves", zone.chests()), TownScreen.INK_FADED));
                y += 11;
                Component registers = zone.registers() > 0
                        ? Component.translatable("townfolk.gui.registers", zone.registers())
                        : Component.translatable("townfolk.gui.no_register");
                menuTexts.add(new MenuText(MENU_PADDING, y, registers, zone.registers() > 0 ? TownScreen.INK_FADED : DANGER_TEXT));
            } else {
                Component chests = zone.chests() > 0
                        ? Component.translatable("townfolk.gui.chests", zone.chests())
                        : Component.translatable("townfolk.gui.no_chest");
                menuTexts.add(new MenuText(MENU_PADDING, y, chests, zone.chests() > 0 ? TownScreen.INK_FADED : DANGER_TEXT));
            }
            y += 11;
            y += 3;
        } else if (type == ZoneType.RESIDENTIAL) {
            if (!current.isHousing()) {
                menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.zone_info.pick_housing"), TownScreen.INK));
                y += 11;
            } else {
                menuTexts.add(new MenuText(MENU_PADDING, y,
                        Component.translatable("townfolk.gui.homes_summary", zone.homes().size(), zone.bedCount(), zone.occupantCount()), TownScreen.INK));
                y += 12;
                int shown = Math.min(zone.homes().size(), MAX_HOME_LINES);
                for (int i = 0; i < shown; i++) {
                    TownView.HomeView home = zone.homes().get(i);
                    Component line = home.names().isEmpty()
                            ? Component.translatable("townfolk.gui.home.empty", home.beds())
                            : Component.translatable("townfolk.gui.home.line", fit(String.join("、", home.names()), innerWidth - 50), home.names().size(), home.beds());
                    menuTexts.add(new MenuText(MENU_PADDING, y, line, home.names().isEmpty() ? TownScreen.INK_FADED : TownScreen.INK));
                    y += 10;
                }
                if (zone.homes().size() > shown) {
                    menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.home.more", zone.homes().size() - shown), TownScreen.INK_FADED));
                    y += 10;
                }
            }
            y += 3;
        } else if (type == ZoneType.COMMERCIAL) {
            if (current == Industry.RESTAURANT) {
                menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.zone_info.commerce_soon"), TownScreen.INK_FADED));
                y += 11;
            }
            y += 3;
        } else if (options.isEmpty()) {
            menuTexts.add(new MenuText(MENU_PADDING, y, Component.translatable("townfolk.gui.zone_info.later"), TownScreen.INK));
            y += 14;
        }

        Component delete = Component.translatable("townfolk.gui.delete_zone");
        int deleteWidth = font.width(delete) + 10;
        menuItems.add(new MenuItem(MENU_WIDTH - MENU_PADDING - deleteWidth, y, deleteWidth, OPTION_HEIGHT, delete, false, true, true, () -> {
            send(ZoneActionPayload.delete(view.id(), zone.id()));
            menuZone = null;
        }));
        return y + OPTION_HEIGHT + MENU_PADDING;
    }

    private void renderMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        TownView.ZoneView zone = menuZone == null ? null : view.zone(menuZone);
        if (zone == null) {
            menuZone = null;
            return;
        }
        menuHeight = layoutMenu(zone);
        menuLeft = Mth.clamp(menuAnchorX + 8, 4, Math.max(4, width - MENU_WIDTH - 4));
        menuTop = Mth.clamp(menuAnchorY - 10, 4, Math.max(4, barTop - 10 - menuHeight));

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 200.0F);
        int right = menuLeft + MENU_WIDTH;
        int bottom = menuTop + menuHeight;
        graphics.fill(menuLeft - 3, menuTop - 3, right + 3, bottom + 3, TownScreen.OUTLINE);
        graphics.fill(menuLeft - 2, menuTop - 2, right + 2, bottom + 2, TownScreen.FRAME);
        graphics.fill(menuLeft, menuTop, right, bottom, TownScreen.PARCHMENT);
        graphics.fill(menuLeft + MENU_PADDING, menuTop + MENU_PADDING + 10, right - MENU_PADDING, menuTop + MENU_PADDING + 11, TownScreen.INK);

        for (MenuText text : menuTexts) {
            graphics.drawString(font, text.text, menuLeft + text.x, menuTop + text.y, text.color, false);
        }
        boolean mayor = view.isMayor();
        for (MenuItem item : menuItems) {
            int x0 = menuLeft + item.x;
            int y0 = menuTop + item.y;
            boolean usable = mayor && item.enabled;
            boolean hovered = usable && item.contains(mouseX - menuLeft, mouseY - menuTop);
            int fill;
            int text;
            if (item.selected && (usable || item.height == OPTION_HEIGHT && item.width > 14)) {
                fill = hovered ? TownScreen.WOOD_HOVER : TownScreen.WOOD;
                text = TownScreen.TEXT_GOLD;
            } else if (item.selected) {
                fill = TownScreen.WOOD_DISABLED;        // 加减号到头了
                text = TownScreen.TEXT_DISABLED;
            } else {
                fill = hovered ? TownScreen.ROW_SELECTED : TownScreen.PARCHMENT_DARK;
                text = item.danger ? DANGER_TEXT : TownScreen.INK;
            }
            graphics.fill(x0, y0, x0 + item.width, y0 + item.height, TownScreen.INK_FADED);
            graphics.fill(x0 + 1, y0 + 1, x0 + item.width - 1, y0 + item.height - 1, fill);
            graphics.drawString(font, item.label, x0 + (item.width - font.width(item.label)) / 2, y0 + 3, text, false);
        }
        graphics.pose().popPose();
    }
}
