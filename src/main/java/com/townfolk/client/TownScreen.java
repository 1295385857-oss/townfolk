package com.townfolk.client;

import com.townfolk.entity.Profession;
import com.townfolk.entity.ResidentEntity;
import com.townfolk.entity.ResidentStatus;
import com.townfolk.network.TownActionPayload;
import com.townfolk.network.TownView;
import com.townfolk.town.Activity;
import com.townfolk.town.Town;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** 镇公所的管理界面：木框里的一张羊皮纸，有总览、居民、时间表三页；点地图会进入上帝视角。 */
public class TownScreen extends Screen {
    // ---- 配色（欧式中世纪：深色木框 + 羊皮纸 + 墨迹）
    static final int OUTLINE = 0xFF2E1F12;
    static final int FRAME = 0xFF4A3320;
    static final int FRAME_LIGHT = 0xFF6E4F33;
    static final int PARCHMENT = 0xFFE9D9B0;
    static final int PARCHMENT_DARK = 0xFFD8C69A;
    static final int ROW_ALT = 0x1A5A3C1E;
    static final int ROW_SELECTED = 0xFFD2B97F;
    static final int INK = 0xFF3B2A1A;
    static final int INK_FADED = 0xFF7A6548;
    static final int WOOD = 0xFF6B4A2B;
    static final int WOOD_HOVER = 0xFF7F5A36;
    static final int WOOD_SELECTED = 0xFF8F6A3E;
    static final int WOOD_DISABLED = 0xFF8E7C66;
    static final int WOOD_HIGHLIGHT = 0xFF9A7448;
    static final int TEXT_LIGHT = 0xFFF3E3BD;
    static final int TEXT_GOLD = 0xFFFFE9A8;
    static final int TEXT_DISABLED = 0xFFC9B99A;

    public static final int PANEL_WIDTH = 264;
    public static final int PANEL_HEIGHT = 210;
    private static final int MARGIN = 14;
    private static final int CONTENT_TOP = 54;

    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_RESIDENTS = 1;
    private static final int TAB_SCHEDULE = 2;
    private static final int TAB_MAP = 3;
    private static final String[] TAB_KEYS = {"townfolk.gui.tab.overview", "townfolk.gui.tab.residents", "townfolk.gui.tab.schedule", "townfolk.gui.tab.map"};

    // ---- 时间表的排版：两排格子，每排 12 个钟点
    private static final int CELL_WIDTH = 19;
    private static final int CELL_HEIGHT = 26;
    private static final int SCHEDULE_ROW_0 = CONTENT_TOP + 12;
    private static final int SCHEDULE_ROW_1 = CONTENT_TOP + 42;
    /** 时间表下面一行小结里各种活动的先后顺序。 */
    private static final Activity[] SUMMARY_ORDER = {Activity.WORK, Activity.EAT, Activity.REST, Activity.FREE, Activity.SLEEP};

    // ---- 居民名单的排版
    public static final int LIST_TOP = CONTENT_TOP + 13;
    public static final int ROW_HEIGHT = 12;
    private static final int VISIBLE_ROWS = 8;
    private static final int COL_NAME = 4;
    private static final int COL_GENDER = 96;
    private static final int COL_AGE = 132;
    private static final int COL_JOB = 170;

    private static final int REFRESH_INTERVAL = 20;
    private static final String[] HOME_KEYS = {"townfolk.home.none", "townfolk.home.apartment", "townfolk.home.detached"};

    private TownView view;
    private int tab = TAB_OVERVIEW;
    private int left;
    private int top;
    private int scroll;
    @Nullable private UUID selectedId;
    private int refreshTimer;

    @Nullable private PlaqueButton capMinus;
    @Nullable private PlaqueButton capPlus;
    @Nullable private PlaqueButton renameButton;
    @Nullable private EditBox nameBox;

    /** 时间表：界面上先改这一份，同时发给服务端，这样拖着涂的时候不用等服务端回话。 */
    private String schedule;
    private Activity brush = Activity.WORK;
    private final PlaqueButton[] brushButtons = new PlaqueButton[Activity.values().length];
    @Nullable private PlaqueButton resetButton;
    @Nullable private PlaqueButton depositButton;
    @Nullable private PlaqueButton withdrawButton;
    @Nullable private PlaqueButton allowanceMinus;
    @Nullable private PlaqueButton allowancePlus;

    public TownScreen(TownView view) {
        super(Component.literal(view.name()));
        this.view = view;
        this.schedule = view.schedule();
    }

    public UUID townId() {
        return view.id();
    }

    /** 服务端发来了新数据。保留当前页签、选中项和正在输入的镇名。 */
    public void updateView(TownView newView) {
        boolean nameUntouched = nameBox != null && nameBox.getValue().equals(view.name());
        view = newView;
        schedule = newView.schedule();
        if (nameUntouched && nameBox != null && !nameBox.isFocused()) {
            nameBox.setValue(newView.name());
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
        refreshWidgetStates();
    }

    // ------------------------------------------------------------ 控件

    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        int x0 = left + MARGIN;

        capMinus = capPlus = renameButton = resetButton = null;
        depositButton = withdrawButton = allowanceMinus = allowancePlus = null;
        nameBox = null;
        java.util.Arrays.fill(brushButtons, null);

        for (int i = 0; i < TAB_KEYS.length; i++) {
            final int index = i;
            PlaqueButton button = new PlaqueButton(x0 + i * 54, top + 30, 50, 16, Component.translatable(TAB_KEYS[i]), b -> {
                if (index == TAB_MAP) {
                    // 地图不是一页纸：关掉这个界面，镜头升到小镇上空
                    minecraft.setScreen(new GodViewScreen(view));
                    return;
                }
                tab = index;
                rebuildWidgets();
            });
            button.selected = tab == i;
            addRenderableWidget(button);
        }

        if (tab == TAB_OVERVIEW) {
            int capY = top + CONTENT_TOP + 50;
            capMinus = addRenderableWidget(new PlaqueButton(x0 + 70, capY, 18, 16, Component.literal("-"),
                    b -> send(TownActionPayload.setPopCap(view.id(), view.popCap() - 1))));
            capPlus = addRenderableWidget(new PlaqueButton(x0 + 122, capY, 18, 16, Component.literal("+"),
                    b -> send(TownActionPayload.setPopCap(view.id(), view.popCap() + 1))));

            int nameY = top + CONTENT_TOP + 78;
            nameBox = new EditBox(font, x0 + 44, nameY + 4, 104, 10, Component.translatable("townfolk.gui.town_name"));
            nameBox.setBordered(false);
            nameBox.setMaxLength(Town.MAX_NAME_LENGTH);
            nameBox.setTextColor(INK & 0xFFFFFF);
            nameBox.setValue(view.name());
            nameBox.setEditable(view.isMayor());
            addRenderableWidget(nameBox);
            renameButton = addRenderableWidget(new PlaqueButton(x0 + 158, nameY, 44, 16,
                    Component.translatable("townfolk.gui.rename"), b -> submitName()));

            // 金库：把背包里的金币存进去，或者一次取 10 个
            int treasuryY = top + CONTENT_TOP + 102;
            depositButton = addRenderableWidget(new PlaqueButton(x0 + 124, treasuryY, 36, 16, Component.translatable("townfolk.gui.deposit"),
                    b -> send(TownActionPayload.simple(view.id(), TownActionPayload.DEPOSIT, 0))));
            withdrawButton = addRenderableWidget(new PlaqueButton(x0 + 164, treasuryY, 46, 16, Component.translatable("townfolk.gui.withdraw"),
                    b -> send(TownActionPayload.simple(view.id(), TownActionPayload.WITHDRAW, 10))));
            // 政策：安家费
            int allowanceY = top + CONTENT_TOP + 124;
            allowanceMinus = addRenderableWidget(new PlaqueButton(x0 + 70, allowanceY, 18, 16, Component.literal("-"),
                    b -> send(TownActionPayload.simple(view.id(), TownActionPayload.SET_ALLOWANCE, view.allowance() - 5))));
            allowancePlus = addRenderableWidget(new PlaqueButton(x0 + 122, allowanceY, 18, 16, Component.literal("+"),
                    b -> send(TownActionPayload.simple(view.id(), TownActionPayload.SET_ALLOWANCE, view.allowance() + 5))));
        } else if (tab == TAB_SCHEDULE) {
            int brushY = top + CONTENT_TOP + 90;
            for (Activity activity : Activity.values()) {
                int i = activity.ordinal();
                brushButtons[i] = addRenderableWidget(new PlaqueButton(x0 + i * 47, brushY, 43, 18, activity.displayName(), b -> {
                    brush = activity;
                    refreshWidgetStates();
                }));
            }
            resetButton = addRenderableWidget(new PlaqueButton(x0, top + CONTENT_TOP + 114, 64, 16,
                    Component.translatable("townfolk.gui.schedule.reset"), b -> send(TownActionPayload.resetSchedule(view.id()))));
        }
        refreshWidgetStates();
    }

    private void refreshWidgetStates() {
        boolean mayor = view.isMayor();
        if (capMinus != null) capMinus.active = mayor && view.popCap() > Town.MIN_POP_CAP;
        if (capPlus != null) capPlus.active = mayor && view.popCap() < Town.MAX_POP_CAP;
        if (renameButton != null) renameButton.active = mayor;
        if (resetButton != null) resetButton.active = mayor;
        if (depositButton != null) depositButton.active = mayor;
        if (withdrawButton != null) withdrawButton.active = mayor && view.treasury() > 0;
        if (allowanceMinus != null) allowanceMinus.active = mayor && view.allowance() > 0;
        if (allowancePlus != null) allowancePlus.active = mayor && view.allowance() < 100;
        for (Activity activity : Activity.values()) {
            PlaqueButton button = brushButtons[activity.ordinal()];
            if (button == null) continue;
            button.active = mayor;
            button.selected = mayor && brush == activity;
        }
    }

    // ------------------------------------------------------------ 时间表

    /** 鼠标指着时间表里的哪个钟点，没指着格子时返回 -1。 */
    private int hourAt(double mouseX, double mouseY) {
        double x = mouseX - (left + MARGIN);
        if (x < 0 || x >= CELL_WIDTH * 12) return -1;
        int column = (int) (x / CELL_WIDTH);
        double y = mouseY - top;
        if (y >= SCHEDULE_ROW_0 && y < SCHEDULE_ROW_0 + CELL_HEIGHT) return column;
        if (y >= SCHEDULE_ROW_1 && y < SCHEDULE_ROW_1 + CELL_HEIGHT) return 12 + column;
        return -1;
    }

    /** 把一个钟点改成指定的活动。只有镇长能改。 */
    public void paintHour(int hour, Activity activity) {
        if (!view.isMayor() || hour < 0 || hour >= 24 || Activity.at(schedule, hour) == activity) return;
        StringBuilder builder = new StringBuilder(schedule);
        builder.setCharAt(hour, (char) ('0' + activity.ordinal()));
        schedule = builder.toString();
        send(TownActionPayload.setSchedule(view.id(), hour, activity.ordinal()));
    }

    /** 两排格子的底色和边框。 */
    private void renderScheduleCells(GuiGraphics graphics) {
        int currentHour = minecraft.level == null ? -1 : Town.hourOf(minecraft.level.getDayTime());
        for (int hour = 0; hour < 24; hour++) {
            int x = left + MARGIN + (hour % 12) * CELL_WIDTH;
            int y = top + (hour < 12 ? SCHEDULE_ROW_0 : SCHEDULE_ROW_1);
            boolean now = hour == currentHour;
            graphics.fill(x, y, x + CELL_WIDTH - 1, y + CELL_HEIGHT, now ? TEXT_GOLD : OUTLINE);
            int inset = now ? 2 : 1;
            graphics.fill(x + inset, y + inset, x + CELL_WIDTH - 1 - inset, y + CELL_HEIGHT - inset, 0xFF000000 | Activity.at(schedule, hour).color);
        }
    }

    private void renderScheduleText(GuiGraphics graphics, int x0) {
        int y = top + CONTENT_TOP;
        graphics.drawString(font, Component.translatable(view.isMayor() ? "townfolk.gui.schedule.hint" : "townfolk.gui.not_mayor"), x0, y, INK_FADED, false);

        int[] counts = new int[Activity.values().length];
        for (int hour = 0; hour < 24; hour++) {
            Activity activity = Activity.at(schedule, hour);
            counts[activity.ordinal()]++;
            int x = left + MARGIN + (hour % 12) * CELL_WIDTH;
            int cellY = top + (hour < 12 ? SCHEDULE_ROW_0 : SCHEDULE_ROW_1);
            String number = String.valueOf(hour);
            graphics.drawString(font, number, x + (CELL_WIDTH - 1 - font.width(number)) / 2, cellY + 3, TEXT_LIGHT, false);
            Component name = activity.shortName();
            graphics.drawString(font, name, x + (CELL_WIDTH - 1 - font.width(name)) / 2, cellY + 14, 0xFFFFFFFF, false);
        }

        net.minecraft.network.chat.MutableComponent summary = Component.empty();
        for (int i = 0; i < SUMMARY_ORDER.length; i++) {
            Activity activity = SUMMARY_ORDER[i];
            if (i > 0) summary.append(" · ");
            summary.append(activity.displayName()).append(" " + counts[activity.ordinal()]);
        }
        graphics.drawString(font, summary, x0, y + 74, INK, false);

        // 每个活动按钮底下一条它的颜色
        for (Activity activity : Activity.values()) {
            PlaqueButton button = brushButtons[activity.ordinal()];
            if (button == null) continue;
            graphics.fill(button.getX() + 3, button.getY() + button.getHeight() - 4, button.getX() + button.getWidth() - 3,
                    button.getY() + button.getHeight() - 2, 0xFF000000 | activity.color);
        }
    }

    private void submitName() {
        if (nameBox == null || !view.isMayor()) return;
        String name = nameBox.getValue().strip();
        if (!name.isEmpty()) {
            send(TownActionPayload.rename(view.id(), name));
            nameBox.setFocused(false);
        }
    }

    private void send(TownActionPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Nullable
    private TownView.ResidentView selectedResident() {
        if (selectedId == null) return null;
        for (TownView.ResidentView resident : view.residents()) {
            if (resident.id().equals(selectedId)) return resident;
        }
        return null;
    }

    private int maxScroll() {
        return Math.max(0, view.residents().size() - VISIBLE_ROWS);
    }

    // ------------------------------------------------------------ 输入

    @Override
    public void tick() {
        super.tick();
        if (++refreshTimer >= REFRESH_INTERVAL) {
            refreshTimer = 0;
            send(TownActionPayload.refresh(view.id()));
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (tab == TAB_SCHEDULE && button == 0 && hourAt(mouseX, mouseY) >= 0) {
            paintHour(hourAt(mouseX, mouseY), brush);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == TAB_SCHEDULE && button == 0 && hourAt(mouseX, mouseY) >= 0) {
            paintHour(hourAt(mouseX, mouseY), brush);
            return true;
        }
        if (tab == TAB_RESIDENTS && button == 0) {
            int listX = left + MARGIN;
            int listY = top + LIST_TOP;
            if (mouseX >= listX && mouseX < left + PANEL_WIDTH - MARGIN && mouseY >= listY && mouseY < listY + VISIBLE_ROWS * ROW_HEIGHT) {
                int index = scroll + (int) ((mouseY - listY) / ROW_HEIGHT);
                if (index < view.residents().size()) {
                    selectedId = view.residents().get(index).id();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == TAB_RESIDENTS && scrollY != 0) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameBox != null && nameBox.isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            submitName();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------ 绘制

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int right = left + PANEL_WIDTH;
        int bottom = top + PANEL_HEIGHT;
        // 木框
        graphics.fill(left, top, right, bottom, OUTLINE);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, FRAME);
        graphics.fill(left + 1, top + 1, right - 1, top + 2, FRAME_LIGHT);
        graphics.fill(left + 1, top + 1, left + 2, bottom - 1, FRAME_LIGHT);
        // 羊皮纸
        graphics.fill(left + 6, top + 6, right - 6, bottom - 6, OUTLINE);
        graphics.fill(left + 7, top + 7, right - 7, bottom - 7, PARCHMENT);
        graphics.fill(left + 7, bottom - 9, right - 7, bottom - 7, PARCHMENT_DARK);
        graphics.fill(right - 9, top + 7, right - 7, bottom - 7, PARCHMENT_DARK);
        // 标题下的墨线
        graphics.fill(left + MARGIN, top + 25, right - MARGIN, top + 26, INK);

        if (tab == TAB_OVERVIEW && nameBox != null) {
            int boxX = nameBox.getX() - 4;
            int boxY = nameBox.getY() - 4;
            graphics.fill(boxX, boxY, boxX + 112, boxY + 16, OUTLINE);
            graphics.fill(boxX + 1, boxY + 1, boxX + 111, boxY + 15, PARCHMENT_DARK);
        }
        if (tab == TAB_RESIDENTS) {
            renderResidentRows(graphics);
        } else if (tab == TAB_SCHEDULE) {
            renderScheduleCells(graphics);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int x0 = left + MARGIN;
        graphics.drawString(font, view.name(), x0, top + 13, INK, false);
        if (tab == TAB_OVERVIEW) {
            renderOverview(graphics, x0);
        } else if (tab == TAB_RESIDENTS) {
            renderResidentText(graphics, x0);
        } else if (tab == TAB_SCHEDULE) {
            renderScheduleText(graphics, x0);
        }
    }

    private void renderOverview(GuiGraphics graphics, int x0) {
        int y = top + CONTENT_TOP;
        graphics.drawString(font, Component.translatable("townfolk.gui.mayor", view.mayorName()), x0, y, INK, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.population", view.residents().size(), view.popCap()), x0, y + 14, INK, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.housing", view.homes(), view.freeHomes(), view.beds()), x0, y + 28, INK, false);

        graphics.drawString(font, Component.translatable("townfolk.gui.pop_cap"), x0, y + 54, INK, false);
        String cap = String.valueOf(view.popCap());
        graphics.drawString(font, cap, x0 + 105 - font.width(cap) / 2, y + 54, INK, false);

        graphics.drawString(font, Component.translatable("townfolk.gui.town_name"), x0, y + 82, INK, false);

        graphics.drawString(font, Component.translatable("townfolk.gui.treasury", view.treasury()), x0, y + 106, INK, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.allowance"), x0, y + 128, INK, false);
        String allowance = String.valueOf(view.allowance());
        graphics.drawString(font, allowance, x0 + 105 - font.width(allowance) / 2, y + 128, INK, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.allowance_hint"), x0 + 146, y + 128, INK_FADED, false);

        if (!view.isMayor()) {
            graphics.drawString(font, Component.translatable("townfolk.gui.not_mayor"), x0, y + 41, INK_FADED, false);
        }
    }

    /** 名单的底色条。画在背景层，这样文字都在它上面。 */
    private void renderResidentRows(GuiGraphics graphics) {
        int x0 = left + MARGIN;
        int x1 = left + PANEL_WIDTH - MARGIN;
        int y = top + LIST_TOP;
        graphics.fill(x0, y - 2, x1, y - 1, INK_FADED);
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = scroll + row;
            if (index >= view.residents().size()) break;
            int rowY = y + row * ROW_HEIGHT;
            if (view.residents().get(index).id().equals(selectedId)) {
                graphics.fill(x0, rowY, x1, rowY + ROW_HEIGHT, ROW_SELECTED);
            } else if (index % 2 == 1) {
                graphics.fill(x0, rowY, x1, rowY + ROW_HEIGHT, ROW_ALT);
            }
        }
    }

    private void renderResidentText(GuiGraphics graphics, int x0) {
        int headerY = top + CONTENT_TOP;
        graphics.drawString(font, Component.translatable("townfolk.gui.col.name"), x0 + COL_NAME, headerY, INK_FADED, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.col.gender"), x0 + COL_GENDER, headerY, INK_FADED, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.col.age"), x0 + COL_AGE, headerY, INK_FADED, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.col.job"), x0 + COL_JOB, headerY, INK_FADED, false);

        int total = view.residents().size();
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int index = scroll + row;
            if (index >= total) break;
            TownView.ResidentView resident = view.residents().get(index);
            int rowY = top + LIST_TOP + row * ROW_HEIGHT + 2;
            graphics.drawString(font, resident.name(), x0 + COL_NAME, rowY, INK, false);
            graphics.drawString(font, genderText(resident), x0 + COL_GENDER, rowY, INK, false);
            graphics.drawString(font, String.valueOf(resident.age()), x0 + COL_AGE, rowY, INK, false);
            graphics.drawString(font, jobText(resident), x0 + COL_JOB, rowY, INK, false);
        }

        int infoY = top + LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + 4;
        if (total > VISIBLE_ROWS) {
            String range = (scroll + 1) + "-" + Math.min(scroll + VISIBLE_ROWS, total) + " / " + total;
            graphics.drawString(font, range, left + PANEL_WIDTH - MARGIN - font.width(range), infoY + 24, INK_FADED, false);
        }

        TownView.ResidentView selected = selectedResident();
        if (selected == null) {
            graphics.drawString(font, Component.translatable("townfolk.gui.pick_resident"), x0, infoY, INK_FADED, false);
            graphics.drawString(font, Component.translatable("townfolk.gui.job_hint"), x0, infoY + 12, INK_FADED, false);
            return;
        }
        Component status = Component.translatable("townfolk.gui.status_home",
                ResidentStatus.byOrdinal(selected.status()).displayName(),
                Component.translatable(HOME_KEYS[Mth.clamp(selected.home(), 0, HOME_KEYS.length - 1)]));
        graphics.drawString(font, status, x0, infoY, INK, false);

        Component none = Component.translatable("townfolk.gui.none");
        Component family = Component.translatable("townfolk.gui.family",
                selected.spouse().isEmpty() ? none : Component.literal(selected.spouse()),
                selected.father().isEmpty() ? none : Component.literal(selected.father()),
                selected.mother().isEmpty() ? none : Component.literal(selected.mother()));
        graphics.drawString(font, family, x0, infoY + 12, INK, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.wallet", selected.money(), selected.satiety()), x0, infoY + 24, INK, false);
    }

    private static Component genderText(TownView.ResidentView resident) {
        return Component.translatable(resident.male() ? "townfolk.gender.male" : "townfolk.gender.female");
    }

    private static Component jobText(TownView.ResidentView resident) {
        if (resident.age() < ResidentEntity.ADULT_AGE) return Component.translatable("townfolk.child");
        return Profession.byOrdinal(resident.job()).displayName();
    }
}
