package dev.elemore.iroh.configtest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.config.TooltipPlacer;
import dev.elemore.iroh.config.TooltipPlacer.Rect;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Headless layout check for the settings tooltip (td-7a78ea): for GUI scales 1 to 4 at 1280x720 and 1920x1080, every
 * vertical position of a hovered row, and every tooltip string in en_us.json (plus the longest with Iroh's appended notes),
 * the placed box must stay inside the screen, never cover the hovered row, and (where a placement exists) never cover Apply,
 * Done, About Iroh or the restart notice. Text width is modelled at 6 px per glyph, wider than Minecraft's font, so the
 * model wraps to more lines than the game does. The screen geometry below is copied from VOptionScreen.init / addButtons.
 * {@code ./gradlew tooltipLayoutTest}; last line "# tooltiplayout PASS n/n".
 */
public final class TooltipLayoutTest {
    private static final List<String> FAILS = new ArrayList<>();
    private static int checks;
    private static final int GLYPH = 6;

    /** Greedy word wrap at GLYPH px per char, with \n as hard breaks, like Font.split. */
    private static TooltipPlacer.Wrapped wrap(String text, int maxWidth) {
        int maxChars = Math.max(1, maxWidth / GLYPH);
        int lines = 0, widest = 0;
        for (String para : text.split("\n", -1)) {
            int cur = 0;
            boolean any = false;
            for (String word : para.split(" ")) {
                int need = word.length() + (cur > 0 ? 1 : 0);
                if (cur > 0 && cur + need > maxChars) {
                    lines++;
                    widest = Math.max(widest, cur);
                    cur = word.length();
                } else cur += need;
                any = true;
            }
            if (any || para.isEmpty()) { lines++; widest = Math.max(widest, cur); }
        }
        return new TooltipPlacer.Wrapped(widest * GLYPH, lines);
    }

    public static void main(String[] args) throws Exception {
        List<String> texts = new ArrayList<>();
        JsonObject lang = JsonParser.parseReader(new InputStreamReader(
                TooltipLayoutTest.class.getResourceAsStream("/assets/vulkanmod/lang/en_us.json"))).getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : lang.entrySet())
            if (e.getKey().contains("tooltip")) texts.add(e.getValue().getAsString());
        String longest = texts.stream().max((a, b) -> a.length() - b.length()).get();
        texts.add(longest + "\n\nNeeds a chunk reload.");
        // extreme: the longest tooltip plus three unavailable-value lines; only fits by cutting lines
        final String extreme = longest + "\n\nNeeds a chunk reload.\n\nOff: unavailable on this GPU (no ray tracing support)\nFast: not available yet\nFabulous: needs the transparency chain";
        texts.add(extreme);
        texts.add("Short.");
        check("lang has tooltips (" + texts.size() + ")", texts.size() > 20);

        int[][] screens = {{1280, 720}, {1920, 1080}};
        long placements = 0, strictBarFree = 0, fallbacks = 0, truncated = 0;
        for (int[] fb : screens) {
            for (int scale = 1; scale <= 4; scale++) {
                int w = (int) Math.ceil(fb[0] / (double) scale), h = (int) Math.ceil(fb[1] / (double) scale);
                // VOptionScreen.init
                int top = 40, bottom = 60, left = 100, itemH = 20;
                int listW = Math.min((int) (w * 0.65f), 420);
                int listH = h - top - bottom;
                Rect list = new Rect(left, top, left + listW, top + listH);
                int itemW = (int) (0.95f * listW);
                // addButtons (font width of a glyph run + 2 * 10 padding)
                int doneW = 4 * GLYPH + 20, applyW = 5 * GLYPH + 20, aboutW = 10 * GLYPH + 10;
                int y0 = h - 20 - 7;
                int doneX = w - doneW - 20;
                List<Rect> bar = new ArrayList<>();
                bar.add(new Rect(doneX, y0, doneX + doneW, y0 + 20));
                int applyX = doneX - (applyW + 5);
                bar.add(new Rect(applyX, y0, applyX + applyW, y0 + 20));
                bar.add(new Rect(w - aboutW - 20, 6, w - aboutW - 20 + aboutW, 26));
                bar.add(new Rect(100, h - 22, 100 + 40 * GLYPH, h - 12)); // restart notice
                List<Rect> chrome = new ArrayList<>();
                for (int i = 0; i < 6; i++) chrome.add(new Rect(10, 40 + i * 23, 90, 62 + i * 23));

                for (int ry = list.y0(); ry <= list.y1() - itemH; ry += 1) {
                    Rect row = new Rect(list.x0(), ry, list.x0() + itemW, ry + itemH);
                    for (String text : texts) {
                        placements++;
                        TooltipPlacer.Placement p = TooltipPlacer.place(w, h, list, row, bar, chrome, mw -> wrap(text, mw));
                        String tag = fb[0] + "x" + fb[1] + " scale " + scale + " row y " + ry + " text " + text.length() + "ch";
                        boolean inside = p.box().x0() >= TooltipPlacer.MARGIN && p.box().y0() >= TooltipPlacer.MARGIN
                                && p.box().x1() <= w - TooltipPlacer.MARGIN && p.box().y1() <= h - TooltipPlacer.MARGIN;
                        boolean overRow = p.box().overlaps(row);
                        boolean overBar = false;
                        for (Rect b : bar) overBar |= p.box().overlaps(b);
                        if (p.pass() == 3) fallbacks++;
                        if (!overBar) strictBarFree++;
                        // hard rules at every scale: never over the hovered row, always on screen
                        checkQuiet(tag + ": inside screen " + p.box(), inside);
                        checkQuiet(tag + ": covers hovered row " + p.box() + " vs " + row, !overRow);
                        // button bar: hard rule at every scale (an over-tall text is cut to the room that is left)
                        if (p.maxLines() < Integer.MAX_VALUE) truncated++;
                        checkQuiet(tag + ": covers the button bar " + p.box(), !overBar);
                    }
                }
            }
        }
        System.out.println("placements: " + placements + ", bar-free " + strictBarFree + ", fallback pass " + fallbacks + ", truncated " + truncated);
        check("placements checked: " + placements + " (bar-free " + strictBarFree + ", fallback pass " + fallbacks + ", truncated to fit " + truncated + ")", true);
        // the exact rc2 failure: 1280x720 guiScale 3 (427x240), Quality tab, last visible row, the 199 char Graphics tooltip
        {
            int w = 427, h = 240;
            Rect list = new Rect(100, 40, 100 + 277, 180);
            Rect row = new Rect(100, 160, 100 + 263, 180);
            List<Rect> bar = List.of(new Rect(w - 44 - 20, 213, w - 20, 233), new Rect(w - 44 - 20 - 55, 213, w - 64 - 5, 233));
            String g = lang.get("iroh.options.graphics.tooltip").getAsString();
            TooltipPlacer.Placement p = TooltipPlacer.place(w, h, list, row, bar, List.of(), mw -> wrap(g, mw));
            check("rc2 case: not over the row or Apply/Done: " + p, !p.box().overlaps(row) && !p.box().overlaps(bar.get(0)) && !p.box().overlaps(bar.get(1)));
        }
        System.out.println(FAILS.isEmpty() ? "# tooltiplayout PASS " + checks + "/" + checks
                : "# tooltiplayout FAIL " + FAILS.size() + " of " + checks + ": " + FAILS.subList(0, Math.min(12, FAILS.size())));
        if (!FAILS.isEmpty())
            System.exit(1);
    }

    private static void checkQuiet(String name, boolean ok) {
        checks++;
        if (!ok) FAILS.add(name);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) FAILS.add(name);
    }
}
