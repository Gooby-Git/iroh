package net.vulkanmod.config.option;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.InfoOptionWidget;
import net.vulkanmod.config.gui.widget.OptionWidget;

import java.util.function.Supplier;

/**
 * A read-only row: a name and a value text, optionally a click action (a link). Used for the Shaders placeholder section
 * and the About tab. With an availability reason it is shown greyed.
 */
public class InfoOption extends Option<String> {
    private final Supplier<Component> value;
    private Runnable onClick;

    public InfoOption(Component name, Supplier<Component> value) {
        super(name, v -> { }, () -> "");
        this.value = value;
    }

    public InfoOption setOnClick(Runnable r) {
        this.onClick = r;
        return this;
    }

    @Override
    public InfoOption setUnavailable(Supplier<Component> reason) {
        super.setUnavailable(reason);
        return this;
    }

    @Override
    public InfoOption setTooltip(Component text) {
        super.setTooltip(text);
        return this;
    }

    public Runnable getOnClick() {
        return onClick;
    }

    public Component getValueText() {
        return value.get();
    }

    @Override
    public Component getDisplayedValue() {
        return value.get();
    }

    @Override
    public boolean isChanged() {
        return false;
    }

    @Override
    public OptionWidget<?> createOptionWidget(int x, int y, int width, int height) {
        return new InfoOptionWidget(this, x, y, width, height, this.name);
    }
}
