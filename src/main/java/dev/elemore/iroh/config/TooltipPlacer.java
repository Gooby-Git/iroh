package dev.elemore.iroh.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Places the settings screen tooltip (td-7a78ea). Pure geometry (no Minecraft types) so it is tested headless
 * ({@code ./gradlew tooltipLayoutTest}).
 *
 * <p>Rules, in the spirit of vanilla's tooltip positioners (stay inside the screen, sit beside the thing you point at,
 * never cover it):
 * <ol>
 *   <li>The box stays inside the screen with a margin on every side.</li>
 *   <li>It never covers the hovered row.</li>
 *   <li>It does not cover the button bar (Apply, Done, About), the tab column or the restart notice when any placement
 *       avoids them; pass 0 avoids all of them, pass 1 only the button bar and the
 *       row; if the text is too tall for any placement even at full width it is cut to the lines that fit (pass 3,
 *       {@link Placement#maxLines}) instead of covering anything.</li>
 *   <li>Preference: right of the list when the column is wide enough (the original docked layout), else below the row,
 *       else above the row. Text wraps at {@link #MAX_WRAP} px; only when no placement fits at that width is the box
 *       widened (never wider than the screen allows).</li>
 * </ol>
 */
public final class TooltipPlacer {
    public static final int PAD = 3;
    public static final int LINE_HEIGHT = 10;
    public static final int MARGIN = 4;
    public static final int GAP = 4;
    /** Normal wrap width, GUI units. */
    public static final int MAX_WRAP = 220;

    public record Rect(int x0, int y0, int x1, int y1) {
        public boolean overlaps(Rect o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
        }

        public long overlapArea(Rect o) {
            long w = Math.min(x1, o.x1) - Math.max(x0, o.x0);
            long h = Math.min(y1, o.y1) - Math.max(y0, o.y0);
            return w > 0 && h > 0 ? w * h : 0;
        }

        public int width() { return x1 - x0; }
        public int height() { return y1 - y0; }
    }

    /** Wrapped text at a width: the widest line in px and the line count. */
    public record Wrapped(int textWidth, int lines) {}

    public interface Wrapper {
        Wrapped wrap(int maxWidth);
    }

    /** Where to draw: top-left of the first text line is {@code (box.x0 + PAD, box.y0 + PAD)}. */
    public record Placement(Rect box, int wrapWidth, int pass, int maxLines) {
        public boolean truncated(int lines) { return lines > maxLines; }
    }

    /**
     * @param list   the option list rectangle (rows live inside it)
     * @param row    the hovered or selected row, clipped to the list
     * @param bar    buttons that must stay readable (Apply, Done, About Iroh) and the restart notice
     * @param chrome the tab column and other furniture, avoided when possible
     */
    public static Placement place(int screenW, int screenH, Rect list, Rect row, List<Rect> bar, List<Rect> chrome, Wrapper wrapper) {
        List<Rect> barAndChrome = new ArrayList<>(bar);
        barAndChrome.addAll(chrome);
        List<List<Rect>> passes = List.of(barAndChrome, bar);

        int maxAvail = screenW - 2 * MARGIN;
        int[] widths = {MAX_WRAP, 300, 380, 460, maxAvail};

        int columnW = Math.min(screenW - (list.x1() + 10) - MARGIN, MAX_WRAP);
        for (int pass = 0; pass < passes.size(); pass++) {
            if (columnW >= 140) { // the docked column right of the list, wrapped to its width
                Wrapped wr = wrapper.wrap(columnW - 2 * PAD);
                int bw = wr.textWidth() + 2 * PAD;
                int bh = wr.lines() * LINE_HEIGHT + 2 * PAD;
                List<Rect> docked = candidates(screenW, screenH, list, row, bw, bh, GAP).subList(0, 2);
                for (Rect box : docked)
                    if (inside(box, screenW, screenH) && !box.overlaps(row) && clear(box, passes.get(pass)))
                        return new Placement(box, columnW - 2 * PAD, pass, Integer.MAX_VALUE);
            }
            for (int w : widths) {
                int wrapW = Math.min(w, maxAvail) - 2 * PAD;
                if (wrapW < 60) continue;
                Wrapped wr = wrapper.wrap(wrapW);
                int bw = wr.textWidth() + 2 * PAD;
                int bh = wr.lines() * LINE_HEIGHT + 2 * PAD;
                for (Rect box : candidates(screenW, screenH, list, row, bw, bh, GAP)) {
                    if (inside(box, screenW, screenH) && !box.overlaps(row) && clear(box, passes.get(pass)))
                        return new Placement(box, wrapW, pass, Integer.MAX_VALUE);
                }
                if (w >= maxAvail) break;
            }
        }

        // Nothing fits at any width: the text is taller than the room above or below the row. Use the larger room at the
        // widest width and cut the text to the lines that fit (the screen appends "..."), never covering the row or the bar.
        int wrapW = maxAvail - 2 * PAD;
        Wrapped wr = wrapper.wrap(wrapW);
        int bw = wr.textWidth() + 2 * PAD;
        int bx = Math.max(MARGIN, Math.min(row.x0(), screenW - MARGIN - bw));
        int belowTop = row.y1() + GAP, belowBottom = screenH - MARGIN;
        int aboveBottom = row.y0() - GAP, aboveTop = MARGIN;
        for (Rect r : bar) {
            if (r.x0() >= bx + bw || r.x1() <= bx) continue;
            if (r.y0() >= belowTop) belowBottom = Math.min(belowBottom, r.y0() - GAP);
            if (r.y1() <= aboveBottom) aboveTop = Math.max(aboveTop, r.y1() + GAP);
        }
        int below = belowBottom - belowTop, above = aboveBottom - aboveTop;
        boolean useBelow = below >= above;
        int room = Math.max(2 * PAD + LINE_HEIGHT, useBelow ? below : above);
        int lines = Math.max(1, Math.min(wr.lines(), (room - 2 * PAD) / LINE_HEIGHT));
        int bh = lines * LINE_HEIGHT + 2 * PAD;
        int y0 = useBelow ? belowTop : aboveBottom - bh;
        return new Placement(new Rect(bx, y0, bx + bw, y0 + bh), wrapW, 3, lines);
    }

    /** Candidate boxes in preference order, not yet validated. */
    private static List<Rect> candidates(int screenW, int screenH, Rect list, Rect row, int bw, int bh, int gap) {
        List<Rect> out = new ArrayList<>();
        // right of the list, aligned with the row, then with the top of the list
        int rx = list.x1() + 10;
        out.add(new Rect(rx, row.y0(), rx + bw, row.y0() + bh));
        out.add(new Rect(rx, list.y0(), rx + bw, list.y0() + bh));
        // below the row, then above it; left edge on the row's left edge, pushed left if it would leave the screen
        int bx = Math.max(MARGIN, Math.min(row.x0(), screenW - MARGIN - bw));
        out.add(new Rect(bx, row.y1() + gap, bx + bw, row.y1() + gap + bh));
        out.add(new Rect(bx, row.y0() - gap - bh, bx + bw, row.y0() - gap));
        // beside the row on its right edge when the row does not span the list
        int sx = Math.max(MARGIN, Math.min(row.x1() + GAP, screenW - MARGIN - bw));
        out.add(new Rect(sx, row.y1() + gap, sx + bw, row.y1() + gap + bh));
        out.add(new Rect(sx, row.y0() - gap - bh, sx + bw, row.y0() - gap));
        return out;
    }

    private static boolean inside(Rect b, int screenW, int screenH) {
        return b.x0() >= MARGIN && b.y0() >= MARGIN && b.x1() <= screenW - MARGIN && b.y1() <= screenH - MARGIN;
    }

    private static boolean clear(Rect b, List<Rect> avoid) {
        for (Rect r : avoid)
            if (b.overlaps(r)) return false;
        return true;
    }
}
