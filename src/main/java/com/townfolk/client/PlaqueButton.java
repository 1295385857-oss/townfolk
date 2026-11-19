package com.townfolk.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 木牌样式的按钮，用在管理界面里。selected 用来表示当前选中的页签或选项。 */
public class PlaqueButton extends Button {
    public boolean selected;

    public PlaqueButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + getWidth();
        int y1 = y0 + getHeight();

        int fill;
        int text;
        if (!active) {
            fill = TownScreen.WOOD_DISABLED;
            text = TownScreen.TEXT_DISABLED;
        } else if (selected) {
            fill = TownScreen.WOOD_SELECTED;
            text = TownScreen.TEXT_GOLD;
        } else if (isHovered()) {
            fill = TownScreen.WOOD_HOVER;
            text = TownScreen.TEXT_LIGHT;
        } else {
            fill = TownScreen.WOOD;
            text = TownScreen.TEXT_LIGHT;
        }

        graphics.fill(x0, y0, x1, y1, TownScreen.OUTLINE);
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill);
        if (active) {
            graphics.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, TownScreen.WOOD_HIGHLIGHT);
        }

        Font font = Minecraft.getInstance().font;
        int textX = x0 + (getWidth() - font.width(getMessage())) / 2;
        int textY = y0 + (getHeight() - 8) / 2;
        graphics.drawString(font, getMessage(), textX, textY, text, false);
    }
}
