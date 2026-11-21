package net.vulkanmod.config.gui;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import dev.elemore.iroh.config.IrohOptions;
import net.vulkanmod.Initializer;
import org.lwjgl.glfw.GLFW;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.Options;
import net.vulkanmod.vulkan.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;

public class VOptionScreen extends Screen {
    public final static int RED = ColorUtil.ARGB.pack(0.3f, 0.0f, 0.0f, 0.8f);
    final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath("vulkanmod", "iroh_logo.png");

    private final Screen parent;

    private final List<OptionPage> optionPages;

    private int currentListIdx = 0;

    private int tooltipX;
    private int tooltipY;
    private int tooltipWidth;

    private VButtonWidget aboutButton;

    private VButtonWidget doneButton;
    private VButtonWidget applyButton;

    private final List<VButtonWidget> pageButtons = Lists.newArrayList();
    private final List<VButtonWidget> buttons = Lists.newArrayList();

    public VOptionScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;

        this.optionPages = new ArrayList<>();
    }

    private void addPages() {
        this.optionPages.clear();

        this.optionPages.add(new OptionPage(Component.translatable("iroh.pages.video").getString(), Options.getVideoOpts()));
        this.optionPages.add(new OptionPage(Component.translatable("iroh.pages.quality").getString(), Options.getQualityOpts()));
        this.optionPages.add(new OptionPage(Component.translatable("iroh.pages.performance").getString(), Options.getPerformanceOpts()));
        this.optionPages.add(new OptionPage(Component.translatable("iroh.pages.shaders").getString(), Options.getShadersOpts()));
        this.optionPages.add(new OptionPage(Component.translatable("iroh.pages.advanced").getString(), Options.getAdvancedOpts()));
        this.optionPages.add(new OptionPage(Component.translatable("iroh.pages.about").getString(), Options.getAboutOpts()));
    }

    @Override
    protected void init() {
        this.addPages();

        int top = 40;
        int bottom = 60;
        int itemHeight = 20;

        int leftMargin = 100;
//        int listWidth = (int) (this.width * 0.65f);
        int listWidth = Math.min((int) (this.width * 0.65f), 420);
        int listHeight = this.height - top - bottom;

        this.buildLists(leftMargin, top, listWidth, listHeight, itemHeight);

        int x = leftMargin + listWidth + 10;
//        int width = Math.min(this.width - this.tooltipX - 10, 200);
        int width = this.width - x - 10;
        int y = 50;

        if (width < 200) {
            x = 100;
            width = listWidth;
            y = this.height - bottom + 10;
        }

        this.tooltipX = x;
        this.tooltipY = y;
        this.tooltipWidth = width;

        buildPage();

        this.applyButton.active = false;
    }

    private void buildLists(int left, int top, int listWidth, int listHeight, int itemHeight) {
        for (OptionPage page : this.optionPages) {
            page.createList(left, top, listWidth, listHeight, itemHeight);
        }
    }

    private void addPageButtons(int x0, int y0, int width, int height, boolean verticalLayout) {
        int x = x0;
        int y = y0;
        for (int i = 0; i < this.optionPages.size(); ++i) {
            var page = this.optionPages.get(i);
            final int finalIdx = i;
            VButtonWidget widget = new VButtonWidget(x, y, width, height, Component.nullToEmpty(page.name), button -> this.setOptionList(finalIdx));
            this.buttons.add(widget);
            this.pageButtons.add(widget);
            this.addWidget(widget);

            if (verticalLayout)
                y += height + 1;
            else
                x += width + 1;
        }

        this.pageButtons.get(this.currentListIdx).setSelected(true);
    }

    private void buildPage() {
        this.buttons.clear();
        this.pageButtons.clear();
        this.clearWidgets();

//        this.addPageButtons(20, 6, 60, 20, false);
        this.addPageButtons(10, 40, 80, 22, true);

        VOptionList currentList = this.optionPages.get(this.currentListIdx).getOptionList();
        this.addWidget(currentList);

        this.addButtons();
    }

    private void addButtons() {
        int rightMargin = 20;
        int buttonHeight = 20;
        int padding = 10;
        int buttonMargin = 5;
        int buttonWidth = minecraft.font.width(CommonComponents.GUI_DONE) + 2 * padding;
        int x0 = (this.width - buttonWidth - rightMargin);
        int y0 = this.height - buttonHeight - 7;

        this.doneButton = new VButtonWidget(
                x0, y0,
                buttonWidth, buttonHeight,
                CommonComponents.GUI_DONE,
                button -> this.minecraft.setScreen(this.parent)
        );

        buttonWidth = minecraft.font.width(Component.translatable("iroh.buttons.apply")) + 2 * padding;
        x0 -= (buttonWidth + buttonMargin);
        this.applyButton = new VButtonWidget(
                x0, y0,
                buttonWidth, buttonHeight,
                Component.translatable("iroh.buttons.apply"),
                button -> this.applyOptions()
        );

        // "About Iroh" replaces the upstream support link: it opens the About tab, where VulkanMod and xCollateral are credited
        buttonWidth = minecraft.font.width(Component.translatable("iroh.buttons.about")) + 10;
        x0 = (this.width - buttonWidth - rightMargin);
        this.aboutButton = new VButtonWidget(
                x0, 6,
                buttonWidth, buttonHeight,
                Component.translatable("iroh.buttons.about"),
                button -> this.setOptionList(this.optionPages.size() - 1)
        );

        this.buttons.add(this.applyButton);
        this.buttons.add(this.doneButton);
        this.buttons.add(this.aboutButton);

        this.addWidget(this.applyButton);
        this.addWidget(this.doneButton);
        this.addWidget(this.aboutButton);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (GuiEventListener element : this.children()) {
            if (element.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(element);
                if (button == 0) {
                    this.setDragging(true);
                }

                this.updateState();
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.setDragging(false);
        this.updateState();
        return this.getChildAt(mouseX, mouseY)
                .filter(guiEventListener -> guiEventListener.mouseReleased(mouseX, mouseY, button))
                .isPresent();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int i, int j, float f) {
        if (this.minecraft.level == null) {
            this.renderPanorama(guiGraphics, f);
        }

        this.renderBlurredBackground(f);
        this.renderMenuBackground(guiGraphics);

    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(guiGraphics, 0, 0, delta);

        GuiRenderer.guiGraphics = guiGraphics;
        GuiRenderer.setPoseStack(guiGraphics.pose());

        RenderSystem.enableBlend();

        // Iroh's own mark and name (the upstream logo is not shown here; VulkanMod is credited on the About tab)
        int size = minecraft.font.lineHeight * 3;
        guiGraphics.blit(ICON, 12, 8, 0f, 0f, size, size, size, size);
        guiGraphics.drawString(this.font, Component.literal("Iroh"), 12 + size + 6, 8 + (size - 8) / 2, 0xFFFFFFFF, true);

        if (IrohOptions.restartPending()) {
            guiGraphics.drawString(this.font, Component.translatable("iroh.screen.restartPending"), 100, this.height - 22, 0xFFFFD060, true);
        }

        VOptionList currentList = this.optionPages.get(this.currentListIdx).getOptionList();
        currentList.updateState(mouseX, mouseY);
        currentList.renderWidget(mouseX, mouseY);
        renderButtons(mouseX, mouseY);

        List<FormattedCharSequence> list = getHoveredButtonTooltip(currentList, mouseX, mouseY);
        if (list != null) {
            this.renderTooltip(list, this.tooltipX, this.tooltipY);
        }
    }

    public void renderButtons(int mouseX, int mouseY) {
        for (VButtonWidget button : buttons) {
            button.render(mouseX, mouseY);
        }
    }

    private void renderTooltip(List<FormattedCharSequence> list, int x, int y) {
        int padding = 3;
        int width = GuiRenderer.getMaxTextWidth(this.font, list);
        int height = list.size() * 10;
        float intensity = 0.05f;
        int color = ColorUtil.ARGB.pack(intensity, intensity, intensity, 0.6f);
        GuiRenderer.fill(x - padding, y - padding, x + width + padding, y + height + padding, color);

//        intensity = 0.4f;
//        color = ColorUtil.ARGB.pack(intensity, intensity, intensity, 0.9f);
        color = RED;
        GuiRenderer.renderBorder(x - padding, y - padding, x + width + padding, y + height + padding, 1, color);

        int yOffset = 0;
        for (var text : list) {
            GuiRenderer.drawString(this.font, text, x, y + yOffset, 0xffffffff);
            yOffset += 10;
        }
    }

    private List<FormattedCharSequence> getHoveredButtonTooltip(VOptionList buttonList, int mouseX, int mouseY) {
        VAbstractWidget widget = buttonList.getHoveredWidget(mouseX, mouseY);
        if (widget == null)
            widget = buttonList.selectedWidget();
        if (widget != null) {
            var tooltip = widget.getTooltip();
            if (tooltip == null)
                return null;

            return this.font.split(tooltip, this.tooltipWidth);
        }
        return null;
    }

    private void updateState() {
        boolean modified = false;
        for (var page : this.optionPages) {
            modified |= page.optionChanged();
        }

        this.applyButton.active = modified;
    }

    private void setOptionList(int i) {
        this.currentListIdx = i;

        this.buildPage();

        this.pageButtons.get(i).setSelected(true);
        this.updateState();
    }

    private void applyOptions() {
        List<OptionPage> pages = List.copyOf(this.optionPages);
        for (var page : pages) {
            page.applyOptionChanges();
        }

        Initializer.CONFIG.write();
        this.minecraft.options.save();
        this.updateState();
    }

    // ---- keyboard / controller (the base screen was mouse only; Controlify drives it through its virtual mouse) ----

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        VOptionList list = this.optionPages.get(this.currentListIdx).getOptionList();
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN -> { list.moveSelection(1); return true; }
            case GLFW.GLFW_KEY_UP -> { list.moveSelection(-1); return true; }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                var w = list.selectedWidget();
                if (w == null) return false;
                w.keyAdjust(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1);
                this.updateState();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && keyCode != GLFW.GLFW_KEY_SPACE) {
                    if (this.applyButton.active) this.applyOptions();
                    return true;
                }
                var w = list.selectedWidget();
                if (w == null) return false;
                w.keyActivate();
                this.updateState();
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN, GLFW.GLFW_KEY_PAGE_UP -> {
                int n = this.optionPages.size();
                int next = (this.currentListIdx + (keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : n - 1)) % n;
                this.setOptionList(next);
                return true;
            }
            default -> { }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
