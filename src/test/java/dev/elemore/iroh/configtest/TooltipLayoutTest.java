package dev.elemore.iroh.configtest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.config.OptionScreenLayout;
import dev.elemore.iroh.config.OptionScreenLayout.Layout;
import dev.elemore.iroh.config.OptionScreenLayout.Rect;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Headless layout check for the settings description panel (td-7a78ea, rc3v2). For GUI scales at 1280x720 (1 to 3: vanilla
 * clamps 4 to 3 there) and 1920x1080 (1 to 4), with two fake fonts (6 px per glyph, and a proportional one), for every
 * tooltip string in en_us.json plus the longest with Iroh's appended notes:
 * the panel stays on screen and overlaps none of the list (so none of its rows), the tab column, Apply, Done, About Iroh,
 * the logo or the restart notice; the wrapped text is split with the injected width function (the game uses Font.split and
 * Font.width the same way) and the drawn box contains every line it draws. Truncation (an over-long text cut to the panel
 * lines) is counted and must be zero for every real tooltip.
 * {@code ./gradlew tooltipLayoutTest}; last line "# tooltiplayout PASS n/n".
 */
public final class TooltipLayoutTest {
    private static final List<String> FAILS = new ArrayList<>();
    private static int checks;

    /** Greedy word wrap with an injected width function, like Font.split; returns the lines. */
    static List<String> split(String text, int maxWidth, ToIntFunction<String> width) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ")) {
                String trial = cur.length() == 0 ? word : cur + " " + word;
                if (cur.length() > 0 && width.applyAsInt(trial) > maxWidth) {
                    out.add(cur.toString());
                    cur = new StringBuilder(word);
                } else cur = new StringBuilder(trial);
                // a single word wider than the line is broken by character
                while (width.applyAsInt(cur.toString()) > maxWidth && cur.length() > 1) {
                    int k = cur.length() - 1;
                    while (k > 1 && width.applyAsInt(cur.substring(0, k)) > maxWidth) k--;
                    out.add(cur.substring(0, k));
                    cur = new StringBuilder(cur.substring(k));
                }
            }
            out.add(cur.toString());
        }
        return out;
    }

    private static int glyph6(String s) { return s.length() * 6; }

    private static int proportional(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += "il.,:;'!|".indexOf(c) >= 0 ? 3 : "ft Ij".indexOf(c) >= 0 ? 5 : c >= 'A' && c <= 'Z' ? 7 : 6;
        return w;
    }

    public static void main(String[] args) throws Exception {
        List<String> texts = new ArrayList<>();
        JsonObject lang = JsonParser.parseReader(new InputStreamReader(
                TooltipLayoutTest.class.getResourceAsStream("/assets/vulkanmod/lang/en_us.json"))).getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : lang.entrySet())
            if (e.getKey().contains("tooltip")) texts.add(e.getValue().getAsString());
        String longest = texts.stream().max((a, b) -> a.length() - b.length()).get();
        // Iroh appends the reload note and up to three unavailable-value lines (CyclingOptionWidget.getTooltip)
        texts.add(longest + "\n\nNeeds a chunk reload.\n\nFabulous: needs the transparency chain\nOff: unavailable on this GPU");
        check("lang has tooltips (" + texts.size() + ", longest " + longest.length() + " chars)", texts.size() > 20 && longest.length() < 400);

        int[][] screens = {{1280, 720, 1}, {1280, 720, 2}, {1280, 720, 3}, {1920, 1080, 1}, {1920, 1080, 2}, {1920, 1080, 3}, {1920, 1080, 4}};
        List<ToIntFunction<String>> fonts = List.of(TooltipLayoutTest::glyph6, TooltipLayoutTest::proportional);
        int truncated = 0, dockedScreens = 0;
        for (int[] sc : screens) {
            int w = (int) Math.ceil(sc[0] / (double) sc[2]), h = (int) Math.ceil(sc[1] / (double) sc[2]);
            // VOptionScreen.init: Done / Apply widths = text + 20; six-glyph run stands in for the font
            int doneW = 4 * 6 + 20, applyW = 5 * 6 + 20, aboutW = 10 * 6 + 10;
            int applyX = w - 20 - doneW - 5 - applyW;
            Layout l = OptionScreenLayout.compute(w, h, applyX);
            String tag = sc[0] + "x" + sc[1] + " scale " + sc[2] + " (" + w + "x" + h + (l.docked() ? " docked" : " bottom") + ")";
            if (l.docked()) dockedScreens++;

            int y0 = h - 20 - 7;
            List<Rect> furniture = new ArrayList<>();
            furniture.add(new Rect(w - 20 - doneW, y0, w - 20, y0 + 20));                          // Done
            furniture.add(new Rect(applyX, y0, applyX + applyW, y0 + 20));                         // Apply
            furniture.add(new Rect(w - aboutW - 20, 6, w - 20, 26));                               // About Iroh
            furniture.add(new Rect(8, 6, 12 + 27 + 6 + 30, 36));                                   // logo + name
            furniture.add(new Rect(100, 14, 100 + 40 * 6, 24));                                    // restart notice
            for (int i = 0; i < 6; i++) furniture.add(new Rect(10, 40 + i * 23, 90, 62 + i * 23)); // tabs
            Rect screen = new Rect(0, 0, w, h);

            check(tag + ": panel inside screen " + l.panel(), l.panel().x0() >= OptionScreenLayout.MARGIN && l.panel().y0() >= 0
                    && l.panel().x1() <= w - OptionScreenLayout.MARGIN && l.panel().y1() <= h - OptionScreenLayout.MARGIN);
            check(tag + ": list inside screen and at least 3 rows high (" + l.list().height() + ")", screen.overlaps(l.list()) && l.list().height() >= 3 * 23 && l.list().y1() <= h);
            check(tag + ": panel does not overlap the list (so no row) " + l.panel() + " " + l.list(), !l.panel().overlaps(l.list()));
            for (Rect f : furniture) {
                check(tag + ": panel clear of " + f, !l.panel().overlaps(f));
                check(tag + ": list clear of " + f, !l.list().overlaps(f) || f.x0() >= 100 && f.y0() >= l.list().y1() && false);
            }
            check(tag + ": panel holds at least 4 lines (" + l.maxLines() + ")", l.maxLines() >= 4);

            for (ToIntFunction<String> font : fonts) {
                for (String text : texts) {
                    OptionScreenLayout.Box box = OptionScreenLayout.box(l, mw -> {
                        List<String> lines = split(text, mw, font);
                        int widest = 0;
                        for (String s : lines) widest = Math.max(widest, font.applyAsInt(s));
                        return new OptionScreenLayout.Wrapped(widest, lines.size());
                    });
                    List<String> lines = split(text, l.innerWidth(), font);
                    int drawn = Math.min(lines.size(), box.lines());
                    int widest = 0;
                    for (int i = 0; i < drawn; i++) widest = Math.max(widest, font.applyAsInt(lines.get(i)));
                    boolean textInside = widest + 2 * OptionScreenLayout.PAD <= box.rect().width()
                            && drawn * OptionScreenLayout.LINE_HEIGHT + 2 * OptionScreenLayout.PAD <= box.rect().height();
                    boolean boxInPanel = box.rect().x0() >= l.panel().x0() && box.rect().x1() <= l.panel().x1()
                            && box.rect().y0() >= l.panel().y0() && box.rect().y1() <= l.panel().y1();
                    boolean boxClear = !box.rect().overlaps(l.list());
                    for (Rect f : furniture) boxClear &= !box.rect().overlaps(f);
                    String t = tag + " text " + text.length() + "ch";
                    check(t + ": every drawn line inside the box (widest " + widest + ", box " + box.rect() + ")", textInside);
                    check(t + ": box inside the panel", boxInPanel);
                    check(t + ": box clear of list rows and the button bar", boxClear);
                    if (box.truncated()) {
                        truncated++;
                        if (text != texts.get(texts.size() - 1))
                            check(t + ": a real tooltip is cut (" + lines.size() + " lines, " + box.lines() + " fit)", false);
                    }
                }
            }
        }
        System.out.println("docked screens " + dockedScreens + " of " + screens.length + ", truncated placements (extreme text only) " + truncated);
        check("the extreme text is the only one ever cut", true);
        System.out.println(FAILS.isEmpty() ? "# tooltiplayout PASS " + checks + "/" + checks
                : "# tooltiplayout FAIL " + FAILS.size() + " of " + checks + ": " + FAILS.subList(0, Math.min(12, FAILS.size())));
        if (!FAILS.isEmpty())
            System.exit(1);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) FAILS.add(name);
    }
}
