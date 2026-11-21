package dev.elemore.iroh.config;

/**
 * Geometry of the settings screen (td-7a78ea, rc3v2). Pure (no Minecraft types), tested headless by
 * {@code ./gradlew tooltipLayoutTest}. The tooltip is not a floating box any more: the screen reserves a description panel
 * that never overlaps the option list, the tab column, the button bar or the header, so no row is ever covered.
 *
 * <ul>
 *   <li>Wide screens (room for a 140 px column right of the list): the panel is that column, from the top of the list to
 *       the bottom of the list, up to 220 px wide.</li>
 *   <li>Otherwise the panel sits under the list and above the Apply / Done row, full width: the list gets shorter by the panel height, fixed per screen
 *       height ({@link #bottomLines}) so the list does not jump when a tooltip appears.</li>
 * </ul>
 * Text is wrapped to {@code panel width - 2 * PAD} with the renderer's own splitter and clipped to {@code maxLines}
 * (the last line becomes "..."); the box is sized from the wrapped lines.
 */
public final class OptionScreenLayout {
    public static final int PAD = 3;
    public static final int LINE_HEIGHT = 10;
    public static final int TOP = 40;
    public static final int LEFT = 100;
    public static final int MAX_LIST_WIDTH = 420;
    public static final int BAR_BOTTOM_MARGIN = 7;
    public static final int BUTTON_HEIGHT = 20;
    public static final int MIN_COLUMN = 140;
    public static final int MAX_COLUMN = 220;
    public static final int MARGIN = 4;

    public record Rect(int x0, int y0, int x1, int y1) {
        public boolean overlaps(Rect o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
        }
        public int width() { return x1 - x0; }
        public int height() { return y1 - y0; }
    }

    /** list: the option list; panel: the reserved description area; docked: the panel is the column right of the list. */
    public record Layout(Rect list, Rect panel, boolean docked) {
        public int innerWidth() { return panel.width() - 2 * PAD; }
        public int maxLines() { return Math.max(1, (panel.height() - 2 * PAD) / LINE_HEIGHT); }
    }

    /** Wrapped text at a width: widest line in px and number of lines. */
    public record Wrapped(int textWidth, int lines) {}

    public interface Wrapper {
        Wrapped wrap(int maxWidth);
    }

    /** The box actually drawn: sized from the wrapped lines, top-left of the panel; lines are the ones drawn. */
    public record Box(Rect rect, int lines, boolean truncated) {}

    public static int bottomLines(int screenH) {
        return screenH >= 540 ? 10 : screenH >= 300 ? 8 : 6;
    }

    /** @param applyX left edge of the Apply button (kept for callers; the bottom panel is above the button row) */
    public static Layout compute(int w, int h, int applyX) {
        int listW = Math.min((int) (w * 0.65f), MAX_LIST_WIDTH);
        int listBottom = h - 60;
        int colX = LEFT + listW + 10;
        int colW = Math.min(w - 10 - colX, MAX_COLUMN);
        if (colW >= MIN_COLUMN) {
            return new Layout(new Rect(LEFT, TOP, LEFT + listW, listBottom), new Rect(colX, TOP, colX + colW, listBottom), true);
        }
        int hp = bottomLines(h) * LINE_HEIGHT + 2 * PAD;
        int x1 = w - 10;                                           // full width, above the Apply / Done row
        int y1 = h - BUTTON_HEIGHT - BAR_BOTTOM_MARGIN - 4;
        Rect panel = new Rect(LEFT, y1 - hp, x1, y1);
        return new Layout(new Rect(LEFT, TOP, LEFT + listW, panel.y0() - 4), panel, false);
    }

    public static Box box(Layout l, Wrapper wrapper) {
        Wrapped wr = wrapper.wrap(l.innerWidth());
        int lines = Math.min(wr.lines(), l.maxLines());
        int bw = Math.min(l.panel().width(), wr.textWidth() + 2 * PAD);
        Rect p = l.panel();
        return new Box(new Rect(p.x0(), p.y0(), p.x0() + bw, p.y0() + lines * LINE_HEIGHT + 2 * PAD), lines, wr.lines() > lines);
    }
}
