package net.vulkanmod.config.option;

import net.minecraft.network.chat.Component;

import net.vulkanmod.config.gui.widget.CyclingOptionWidget;
import net.vulkanmod.config.gui.widget.OptionWidget;
import org.apache.commons.lang3.ArrayUtils;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class CyclingOption<E> extends Option<E> {
    private E[] values;
    private int index;

    public CyclingOption(Component name, E[] values, Consumer<E> setter, Supplier<E> getter) {
        super(name, setter, getter);
        this.values = values;

        this.index = this.findNewValueIndex();
    }

    @Override
    public OptionWidget<?> createOptionWidget(int x, int y, int width, int height) {
        return new CyclingOptionWidget(this, x, y, width, height, this.name);
    }

    public void updateOption(E[] values, Consumer<E> setter, Supplier<E> getter) {
        this.onApply = setter;
        this.valueSupplier = getter;

        this.values = values;
        this.index = ArrayUtils.indexOf(this.values, this.getNewValue());
    }

    public int index() { return this.index; }

    public void setValues(E[] values) {
        this.values = values;
    }

    /** Reason one value cannot be picked (greyed, skipped when cycling), or null. */
    private java.util.function.Function<E, Component> valueReason;

    public CyclingOption<E> setValueReason(java.util.function.Function<E, Component> f) {
        this.valueReason = f;
        return this;
    }

    public Component reasonFor(E value) {
        return this.valueReason == null ? null : this.valueReason.apply(value);
    }

    private boolean pickable(int i) {
        return i >= 0 && i < values.length && reasonFor(values[i]) == null;
    }

    public boolean hasPrev() {
        for (int i = this.index - 1; i >= 0; i--) if (pickable(i)) return true;
        return false;
    }

    public boolean hasNext() {
        for (int i = this.index + 1; i < values.length; i++) if (pickable(i)) return true;
        return false;
    }

    public void prevValue() {
        for (int i = this.index - 1; i >= 0; i--) {
            if (pickable(i)) { this.index = i; break; }
        }
        this.updateValue();
    }

    public void nextValue() {
        for (int i = this.index + 1; i < values.length; i++) {
            if (pickable(i)) { this.index = i; break; }
        }
        this.updateValue();
    }

    private void updateValue() {
        if(this.index >= 0 && this.index < this.values.length) {
            this.newValue = values[this.index];

            if (onChange != null)
                onChange.run();
        }
    }

    public void setNewValue(E e) {
        super.setNewValue(e);
        this.index = findNewValueIndex();
    }

    private int findNewValueIndex() {
        for (int i = 0; i < this.values.length; i++) {
            if (this.values[i].equals(this.newValue))
                return i;
        }
        return -1;
    }

    public E[] getValues() {
        return values;
    }
}
