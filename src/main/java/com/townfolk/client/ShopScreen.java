package com.townfolk.client;

import com.townfolk.network.ShopActionPayload;
import com.townfolk.network.ShopDataPayload;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** 收银台的账本：钱箱里有多少钱，每种货现在卖多少、还剩多少、今天卖了多少、多少人想买没买到。 */
public class ShopScreen extends Screen {
    private static final int PANEL_WIDTH = 264;
    private static final int PANEL_HEIGHT = 210;
    private static final int MARGIN = 14;
    private static final int LIST_TOP = 72;
    private static final int ROW_HEIGHT = 14;
    private static final int VISIBLE_ROWS = 8;
    private static final int COL_PRICE = 112;
    private static final int COL_STOCK = 150;
    private static final int COL_SOLD = 182;
    private static final int COL_MISSED = 214;
    private static final int REFRESH_INTERVAL = 20;
    private static final int PRICE_UP = 0xFFA62A22;
    private static final int PRICE_DOWN = 0xFF2F6B2A;

    private ShopDataPayload data;
    private int left;
    private int top;
    private int scroll;
    private int refreshTimer;
    @Nullable private PlaqueButton takeButton;

    public ShopScreen(ShopDataPayload data) {
        super(Component.translatable("townfolk.gui.shop.title"));
        this.data = data;
    }

    public BlockPos pos() {
        return data.pos();
    }

    public void update(ShopDataPayload newData) {
        data = newData;
        scroll = Mth.clamp(scroll, 0, Math.max(0, data.lines().size() - VISIBLE_ROWS));
        if (takeButton != null) takeButton.active = data.money() > 0;
    }

    @Override
    protected void init() {
        left = (width - PANEL_WIDTH) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        takeButton = addRenderableWidget(new PlaqueButton(left + PANEL_WIDTH - MARGIN - 44, top + 30, 44, 16,
                Component.translatable("townfolk.gui.shop.take"),
                b -> PacketDistributor.sendToServer(new ShopActionPayload(data.pos(), ShopActionPayload.TAKE_MONEY))));
        takeButton.active = data.money() > 0;
    }

    @Override
    public void tick() {
        super.tick();
        if (++refreshTimer >= REFRESH_INTERVAL) {
            refreshTimer = 0;
            PacketDistributor.sendToServer(new ShopActionPayload(data.pos(), ShopActionPayload.REFRESH));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, data.lines().size() - VISIBLE_ROWS));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int right = left + PANEL_WIDTH;
        int bottom = top + PANEL_HEIGHT;
        graphics.fill(left, top, right, bottom, TownScreen.OUTLINE);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, TownScreen.FRAME);
        graphics.fill(left + 1, top + 1, right - 1, top + 2, TownScreen.FRAME_LIGHT);
        graphics.fill(left + 6, top + 6, right - 6, bottom - 6, TownScreen.OUTLINE);
        graphics.fill(left + 7, top + 7, right - 7, bottom - 7, TownScreen.PARCHMENT);
        graphics.fill(left + MARGIN, top + 25, right - MARGIN, top + 26, TownScreen.INK);
        graphics.fill(left + MARGIN, top + LIST_TOP - 2, right - MARGIN, top + LIST_TOP - 1, TownScreen.INK_FADED);
        for (int row = 0; row < VISIBLE_ROWS && scroll + row < data.lines().size(); row++) {
            if ((scroll + row) % 2 == 1) {
                int y = top + LIST_TOP + row * ROW_HEIGHT;
                graphics.fill(left + MARGIN, y, right - MARGIN, y + ROW_HEIGHT, TownScreen.ROW_ALT);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int x0 = left + MARGIN;
        graphics.drawString(font, title, x0, top + 13, TownScreen.INK, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.shop.money", data.money()), x0, top + 34, TownScreen.INK, false);

        Component state = !data.inShop() ? Component.translatable("townfolk.gui.shop.not_in_shop")
                : data.tended() ? Component.translatable("townfolk.gui.shop.open")
                : Component.translatable("townfolk.gui.shop.closed");
        graphics.drawString(font, state, x0, top + 47, data.inShop() && data.tended() ? PRICE_DOWN : TownScreen.INK_FADED, false);

        int headerY = top + LIST_TOP - 12;
        graphics.drawString(font, Component.translatable("townfolk.gui.shop.col.item"), x0 + 20, headerY, TownScreen.INK_FADED, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.shop.col.price"), x0 + COL_PRICE, headerY, TownScreen.INK_FADED, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.shop.col.stock"), x0 + COL_STOCK, headerY, TownScreen.INK_FADED, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.shop.col.sold"), x0 + COL_SOLD, headerY, TownScreen.INK_FADED, false);
        graphics.drawString(font, Component.translatable("townfolk.gui.shop.col.missed"), x0 + COL_MISSED, headerY, TownScreen.INK_FADED, false);

        if (data.lines().isEmpty()) {
            graphics.drawString(font, Component.translatable("townfolk.gui.shop.empty"), x0, top + LIST_TOP + 4, TownScreen.INK_FADED, false);
        }
        for (int row = 0; row < VISIBLE_ROWS && scroll + row < data.lines().size(); row++) {
            ShopDataPayload.Line line = data.lines().get(scroll + row);
            int y = top + LIST_TOP + row * ROW_HEIGHT;
            ResourceLocation id = ResourceLocation.tryParse(line.item());
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            ItemStack stack = new ItemStack(item);
            // 物品图标缩小一点放进一行里
            graphics.pose().pushPose();
            graphics.pose().translate(x0 + 2, y, 0);
            graphics.pose().scale(0.8F, 0.8F, 1.0F);
            graphics.renderItem(stack, 0, 0);
            graphics.pose().popPose();
            graphics.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), COL_PRICE - 24), x0 + 20, y + 3, TownScreen.INK, false);
            // 比基准价贵用红色，便宜用绿色
            int priceColor = line.price() > line.basePrice() ? PRICE_UP : line.price() < line.basePrice() ? PRICE_DOWN : TownScreen.INK;
            graphics.drawString(font, String.valueOf(line.price()), x0 + COL_PRICE, y + 3, priceColor, false);
            graphics.drawString(font, String.valueOf(line.stock()), x0 + COL_STOCK, y + 3, line.stock() == 0 ? PRICE_UP : TownScreen.INK, false);
            graphics.drawString(font, String.valueOf(line.soldToday()), x0 + COL_SOLD, y + 3, TownScreen.INK, false);
            graphics.drawString(font, String.valueOf(line.missedToday()), x0 + COL_MISSED, y + 3, line.missedToday() > 0 ? PRICE_UP : TownScreen.INK_FADED, false);
        }
        graphics.drawString(font, Component.translatable("townfolk.gui.shop.hint"), x0, top + PANEL_HEIGHT - 22, TownScreen.INK_FADED, false);
    }
}
