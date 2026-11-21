package net.vulkanmod.config.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.GuiRenderer;
import net.vulkanmod.config.option.InfoOption;

/** Row with a value text on the right; the value gets the space it needs (URLs, GPU names), clipped with an ellipsis. */
public class InfoOptionWidget extends OptionWidget<InfoOption> {
    public InfoOptionWidget(InfoOption option, int x, int y, int width, int height, Component name) {
        super(x, y, width, height, name);
        this.option = option;
    }

    @Override
    protected void renderControls(double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        int nameWidth = font.width(this.getName());
        int room = this.width - 24 - nameWidth;
        String text = this.getDisplayedValue().getString();
        if (font.width(text) > room)
            text = font.plainSubstrByWidth(text, Math.max(8, room - font.width("...")), false) + "...";

        // links read as links; everything else as plain information
        int color = !this.active ? 0xA0A0A0 : (this.option.getOnClick() != null ? 0xFFB0B0 : 0xFFFFFF);
        int x = this.x + this.width - 8 - font.width(text);
        int y = this.y + (this.height - 9) / 2;
        GuiRenderer.drawString(font, Component.literal(text).getVisualOrderText(), x, y, color);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        Runnable r = this.option.getOnClick();
        if (r != null) r.run();
    }

    @Override
    public void keyActivate() {
        Runnable r = this.option.getOnClick();
        if (r != null) r.run();
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        // the whole row is the link target; greyed rows are not clickable
        return this.option.getOnClick() != null && this.visible
                && mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
    }

    @Override
    protected int getYImage(boolean hovered) {
        return 0;
    }
}
