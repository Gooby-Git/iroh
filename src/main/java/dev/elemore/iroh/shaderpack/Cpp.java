package dev.elemore.iroh.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The GLSL preprocessor of the pack front end (stage v), also used in "properties mode" for the conditional
 * preprocessing of {@code shaders.properties} / {@code block.properties} (stage ii).
 * <p>
 * Why our own and not shaderc's: the pack's directives live in comments ({@code /* DRAWBUFFERS:01 *}{@code /},
 * {@code /* const int colortex0Format = RGBA16F; *}{@code /}) and only the ones on lines a branch actually took
 * count, so the output must be the live lines <em>with their comments</em>. It is a C preprocessor without
 * stringizing: object and function-like macros with per-token hide sets (rescanning against the rest of the
 * input), {@code ##} pasting, {@code defined}, {@code #if} integer/float arithmetic (an undefined identifier is
 * 0), {@code #include "rel"} / {@code "/abs"} through a resolver, {@code __LINE__ __FILE__ __VERSION__}.
 * {@code #version}, {@code #extension}, {@code #pragma}, {@code #error}, {@code #line} pass through.
 * <p>
 * Output: live text with every macro expanded and every consumed directive replaced by an empty line, so line
 * {@code n} of the output has an origin {@link #origins()}{@code [n-1]} (file and line). A macro call spanning
 * several lines is padded with newlines after it so later lines stay aligned.
 * <p>
 * Bounds (a pack is untrusted): include depth 40, total lines read {@link #MAX_LINES}, macro expansion steps
 * {@link #MAX_STEPS}. Exceeding one throws {@link CppException}, never a StackOverflowError.
 */
public final class Cpp {

    public static final int MAX_LINES = 4_000_000;
    public static final int MAX_STEPS = 50_000_000;
    public static final int MAX_DEPTH = 40;

    public static final class CppException extends RuntimeException {
        public final String file;
        public final int line;

        public CppException(String file, int line, String msg) {
            super((file == null ? "" : file + ":" + line + ": ") + msg);
            this.file = file;
            this.line = line;
        }
    }

    /** A macro: params == null for object-like. */
    public record Macro(String name, List<String> params, List<Tok> body) {
        public String bodyText() {
            StringBuilder sb = new StringBuilder();
            for (Tok t : body)
                sb.append(t.text);
            return sb.toString().trim();
        }
    }

    public interface IncludeResolver {
        /** @return [resolvedPath, text] or null when missing */
        String[] resolve(String fromFile, String path);
    }

    public record Origin(String file, int line) {}

    // ------------------------------------------------------------------------------------------- tokens

    enum K { IDENT, NUMBER, PUNCT, WS, NL, COMMENT, STRING, OTHER }

    public static final class Tok {
        final K k;
        final String text;
        Set<String> hide;

        Tok(K k, String text) {
            this(k, text, Collections.emptySet());
        }

        Tok(K k, String text, Set<String> hide) {
            this.k = k;
            this.text = text;
            this.hide = hide;
        }

        Tok withHide(Set<String> h) {
            return new Tok(k, text, h);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int st = i;
            if (c == '\n') {
                out.add(new Tok(K.NL, "\n"));
                i++;
            } else if (c == ' ' || c == '\t' || c == '\f' || c == '\u000b' || c == '\r') {
                while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\f' || s.charAt(i) == '\u000b' || s.charAt(i) == '\r'))
                    i++;
                out.add(new Tok(K.WS, s.substring(st, i)));
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n')
                    i++;
                out.add(new Tok(K.COMMENT, s.substring(st, i)));
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                out.add(new Tok(K.COMMENT, s.substring(st, i)));
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$'))
                    i++;
                out.add(new Tok(K.IDENT, s.substring(st, i)));
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    if ((d == '+' || d == '-') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')
                            && !(s.startsWith("0x", st) || s.startsWith("0X", st))) {
                        i++;
                    } else if (Character.isLetterOrDigit(d) || d == '.' || d == '_') {
                        i++;
                    } else {
                        break;
                    }
                }
                out.add(new Tok(K.NUMBER, s.substring(st, i)));
            } else if (c == '"') {
                i++;
                while (i < n && s.charAt(i) != '"' && s.charAt(i) != '\n')
                    i++;
                if (i < n && s.charAt(i) == '"')
                    i++;
                out.add(new Tok(K.STRING, s.substring(st, i)));
            } else {
                // two-char punctuators that matter to #if and pasting
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                switch (two) {
                    case "##", "&&", "||", "==", "!=", "<=", ">=", "<<", ">>", "++", "--", "+=", "-=", "*=", "/=" -> {
                        out.add(new Tok(K.PUNCT, two));
                        i += 2;
                    }
                    default -> {
                        out.add(new Tok(K.PUNCT, String.valueOf(c)));
                        i++;
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- state

    private final Map<String, Macro> macros = new LinkedHashMap<>();
    private final IncludeResolver resolver;
    private final boolean propertiesMode;
    private UnaryOperator<String> lineRewriter = null;
    private final StringBuilder out = new StringBuilder();
    private final List<Origin> origins = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> includes = new ArrayList<>();
    private int linesRead;
    private long steps;
    private int version = 110;
    private String profile = "";
    private boolean sawVersion;
    private String curFile = "";
    private int curLine;
    private int lineCursor;   // line of the text being expanded (for __LINE__)

    /**
     * @param propertiesMode conditionals only: no comments, no macro expansion of text, unknown {@code #word}
     *                       lines are comments (properties files use '#' for comments)
     */
    public Cpp(IncludeResolver resolver, boolean propertiesMode) {
        this.resolver = resolver;
        this.propertiesMode = propertiesMode;
    }

    /** Applied to every raw line (outside block comments) before it is read: option values are applied here. */
    public void setLineRewriter(UnaryOperator<String> r) {
        this.lineRewriter = r;
    }

    public void define(String name, String value) {
        macros.put(name, new Macro(name, null, trimWs(lex(value == null ? "" : value))));
    }

    public void undef(String name) {
        macros.remove(name);
    }

    public Map<String, Macro> macros() {
        return macros;
    }

    public boolean isDefined(String name) {
        return macros.containsKey(name);
    }

    public String text() {
        return out.toString();
    }

    public List<Origin> origins() {
        return origins;
    }

    public List<String> notes() {
        return notes;
    }

    public List<String> includes() {
        return includes;
    }

    public int version() {
        return version;
    }

    public String profile() {
        return profile;
    }

    public boolean sawVersion() {
        return sawVersion;
    }

    // ------------------------------------------------------------------------------------------- driver

    private static final class Cond {
        final boolean parentLive;
        boolean taken;      // some branch already taken
        boolean live;       // current branch live
        boolean sawElse;

        Cond(boolean parentLive, boolean live) {
            this.parentLive = parentLive;
            this.live = parentLive && live;
            this.taken = this.live;
        }
    }

    public void run(String file, String text) {
        process(file, text, 0);
    }

    private void process(String file, String text, int depth) {
        if (depth > MAX_DEPTH)
            throw new CppException(file, 0, "#include nested deeper than " + MAX_DEPTH);
        String[] lines = text.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty() && text.endsWith("\n"))
            lines = java.util.Arrays.copyOf(lines, lines.length - 1);
        linesRead += lines.length;
        if (linesRead > MAX_LINES)
            throw new CppException(file, 0, "more than " + MAX_LINES + " lines read (include budget)");

        List<Cond> conds = new ArrayList<>();
        boolean inComment = false;
        // pending text lines (live), expanded together so macro calls may span lines
        StringBuilder block = new StringBuilder();
        List<Origin> blockOrigins = new ArrayList<>();

        int i = 0;
        while (i < lines.length) {
            int lineNo = i + 1;
            String line = lines[i];
            // join continuation lines (directives and text alike, as the compiler does)
            int consumed = 1;
            while (line.endsWith("\\") && i + consumed < lines.length) {
                line = line.substring(0, line.length() - 1) + lines[i + consumed];
                consumed++;
            }
            i += consumed;
            boolean live = conds.isEmpty() || conds.get(conds.size() - 1).live;

            boolean startsInComment = inComment;
            if (!startsInComment && lineRewriter != null && live && !propertiesMode) {
                String r = lineRewriter.apply(line);
                if (r != null)
                    line = r;
            }
            if (!propertiesMode)
                inComment = commentStateAfter(line, inComment);

            String trimmed = line.stripLeading();
            boolean directive = !startsInComment && trimmed.startsWith("#");

            if (!directive) {
                if (live) {
                    if (propertiesMode) {
                        flush(block, blockOrigins);
                        emitLine(line, new Origin(file, lineNo), consumed);
                    } else {
                        block.append(line).append('\n');
                        blockOrigins.add(new Origin(file, lineNo));
                        for (int k = 1; k < consumed; k++) {
                            block.append('\n');
                            blockOrigins.add(new Origin(file, lineNo + k));
                        }
                    }
                }
                continue;
            }

            // a directive: everything pending is expanded first
            flush(block, blockOrigins);
            curFile = file;
            curLine = lineNo;
            // properties mode: "# if you want..." is a comment, only "#if" is a directive
            String body = propertiesMode ? trimmed.substring(1) : trimmed.substring(1).stripLeading();
            String word = leadingWord(body);
            String rest = body.substring(word.length());
            if (!propertiesMode)
                rest = stripDirectiveComment(rest);
            rest = rest.trim();

            switch (word) {
                case "if", "ifdef", "ifndef" -> {
                    boolean v = false;
                    if (live) {
                        if (word.equals("if")) {
                            v = evalCondition(rest);
                        } else {
                            String name = leadingWord(rest);
                            if (name.isEmpty()) {
                                notes.add(file + ":" + lineNo + ": #" + word + " without a name, taken as #if 1 (pack bug)");
                                v = true;
                            } else {
                                v = macros.containsKey(name) == word.equals("ifdef");
                            }
                        }
                    }
                    conds.add(new Cond(live, v));
                    emitBlank(file, lineNo, consumed);
                }
                case "elif" -> {
                    if (conds.isEmpty())
                        throw new CppException(file, lineNo, "#elif without #if");
                    Cond c = conds.get(conds.size() - 1);
                    if (c.taken || !c.parentLive) {
                        c.live = false;
                    } else {
                        c.live = evalCondition(rest);
                        c.taken = c.live;
                    }
                    emitBlank(file, lineNo, consumed);
                }
                case "else" -> {
                    if (conds.isEmpty())
                        throw new CppException(file, lineNo, "#else without #if");
                    Cond c = conds.get(conds.size() - 1);
                    c.live = c.parentLive && !c.taken;
                    c.taken = true;
                    c.sawElse = true;
                    emitBlank(file, lineNo, consumed);
                }
                case "endif" -> {
                    if (conds.isEmpty())
                        throw new CppException(file, lineNo, "#endif without #if");
                    conds.remove(conds.size() - 1);
                    emitBlank(file, lineNo, consumed);
                }
                default -> {
                    if (!live) {
                        emitBlank(file, lineNo, consumed);
                        break;
                    }
                    if (propertiesMode && !word.equals("define") && !word.equals("undef")) {
                        // a comment line in a properties file
                        emitBlank(file, lineNo, consumed);
                        break;
                    }
                    switch (word) {
                        case "define" -> {
                            defineFrom(rest, file, lineNo);
                            emitBlank(file, lineNo, consumed);
                        }
                        case "undef" -> {
                            macros.remove(leadingWord(rest));
                            emitBlank(file, lineNo, consumed);
                        }
                        case "include" -> {
                            String p = rest;
                            if (p.length() >= 2 && (p.startsWith("\"") && p.endsWith("\"") || p.startsWith("<") && p.endsWith(">")))
                                p = p.substring(1, p.length() - 1);
                            else
                                throw new CppException(file, lineNo, "malformed #include " + rest);
                            String[] r = resolver == null ? null : resolver.resolve(file, p);
                            if (r == null)
                                throw new CppException(file, lineNo, "#include \"" + p + "\": file not found");
                            includes.add(r[0]);
                            process(r[0], r[1], depth + 1);
                        }
                        case "version" -> {
                            String[] parts = rest.split("\\s+");
                            try {
                                int v = Integer.parseInt(parts[0]);
                                if (!sawVersion) {
                                    version = v;
                                    profile = parts.length > 1 ? parts[1] : "";
                                    sawVersion = true;
                                    define("__VERSION__", String.valueOf(v));
                                    if (profile.equals("compatibility"))
                                        define("GL_compatibility_profile", "1");
                                    else if (v >= 150)
                                        define("GL_core_profile", "1");
                                } else {
                                    notes.add(file + ":" + lineNo + ": second #version " + rest + " ignored");
                                }
                            } catch (NumberFormatException e) {
                                throw new CppException(file, lineNo, "bad #version " + rest);
                            }
                            emitRaw("#version " + rest, file, lineNo, consumed);
                        }
                        case "extension", "pragma", "line" -> emitRaw("#" + word + " " + rest, file, lineNo, consumed);
                        case "error" -> emitRaw("#error " + rest, file, lineNo, consumed);
                        case "" -> emitBlank(file, lineNo, consumed); // null directive "#"
                        default -> {
                            notes.add(file + ":" + lineNo + ": unknown directive #" + word + " dropped");
                            emitBlank(file, lineNo, consumed);
                        }
                    }
                }
            }
        }
        flush(block, blockOrigins);
        if (!conds.isEmpty())
            throw new CppException(file, lines.length, "unterminated #if (" + conds.size() + " open)");
    }

    /** Whether a block comment is open at the end of {@code line} given the state at its start. */
    private static boolean commentStateAfter(String line, boolean in) {
        int n = line.length();
        int i = 0;
        while (i < n) {
            if (in) {
                int e = line.indexOf("*/", i);
                if (e < 0)
                    return true;
                in = false;
                i = e + 2;
            } else {
                char c = line.charAt(i);
                if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/')
                    return false;
                if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                    in = true;
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    int e = line.indexOf('"', i + 1);
                    i = e < 0 ? n : e + 1;
                    continue;
                }
                i++;
            }
        }
        return in;
    }

    private static String stripDirectiveComment(String s) {
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/')
                break;
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                if (e < 0)
                    break;
                sb.append(' ');
                i = e + 2;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String leadingWord(String s) {
        int i = 0;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'))
            i++;
        return s.substring(0, i);
    }

    private void defineFrom(String rest, String file, int line) {
        String name = leadingWord(rest);
        if (name.isEmpty())
            throw new CppException(file, line, "#define without a name");
        String after = rest.substring(name.length());
        List<String> params = null;
        if (after.startsWith("(")) {
            int close = after.indexOf(')');
            if (close < 0)
                throw new CppException(file, line, "#define " + name + ": unterminated parameter list");
            params = new ArrayList<>();
            for (String p : after.substring(1, close).split(",")) {
                p = p.trim();
                if (!p.isEmpty())
                    params.add(p);
            }
            after = after.substring(close + 1);
        }
        macros.put(name, new Macro(name, params, trimWs(lex(after))));
    }

    private static List<Tok> trimWs(List<Tok> l) {
        int a = 0;
        int b = l.size();
        while (a < b && (l.get(a).k == K.WS || l.get(a).k == K.NL || l.get(a).k == K.COMMENT))
            a++;
        while (b > a && (l.get(b - 1).k == K.WS || l.get(b - 1).k == K.NL || l.get(b - 1).k == K.COMMENT))
            b--;
        return new ArrayList<>(l.subList(a, b));
    }

    private void emitBlank(String file, int line, int count) {
        for (int k = 0; k < count; k++) {
            out.append('\n');
            origins.add(new Origin(file, line + k));
        }
    }

    private void emitRaw(String text, String file, int line, int count) {
        out.append(text).append('\n');
        origins.add(new Origin(file, line));
        for (int k = 1; k < count; k++) {
            out.append('\n');
            origins.add(new Origin(file, line + k));
        }
    }

    private void emitLine(String text, Origin o, int count) {
        out.append(text).append('\n');
        origins.add(o);
        for (int k = 1; k < count; k++) {
            out.append('\n');
            origins.add(new Origin(o.file(), o.line() + k));
        }
    }

    private void flush(StringBuilder block, List<Origin> blockOrigins) {
        if (block.isEmpty())
            return;
        lineCursor = blockOrigins.get(0).line();
        String expanded = expandText(block.toString());
        int have = 0;
        for (int k = 0; k < expanded.length(); k++)
            if (expanded.charAt(k) == '\n')
                have++;
        int want = blockOrigins.size();
        out.append(expanded);
        // a macro call that spanned lines ate newlines: pad so later origins stay aligned
        for (int k = have; k < want; k++)
            out.append('\n');
        origins.addAll(blockOrigins);
        for (int k = want; k < have; k++)
            origins.add(blockOrigins.get(want - 1));
        block.setLength(0);
        blockOrigins.clear();
    }

    // ------------------------------------------------------------------------------------------- expansion

    /** Expands every macro in {@code text} (comments pass through untouched). */
    public String expandText(String text) {
        List<Tok> res = expand(lex(text), null);
        StringBuilder sb = new StringBuilder(text.length() + 64);
        for (Tok t : res)
            sb.append(t.text);
        return sb.toString();
    }

    private void step() {
        if (++steps > MAX_STEPS)
            throw new CppException(curFile, curLine, "macro expansion budget exhausted (recursive macros?)");
    }

    /**
     * Expands a token list. {@code definedOp}: when non-null, {@code defined X} is folded to 1/0 first
     * (#if context).
     */
    private List<Tok> expand(List<Tok> input, Boolean ifContext) {
        // stack: the end of the list is the front of the input
        ArrayList<Tok> stack = new ArrayList<>(input.size());
        for (int k = input.size() - 1; k >= 0; k--)
            stack.add(input.get(k));
        List<Tok> result = new ArrayList<>(input.size());
        while (!stack.isEmpty()) {
            step();
            Tok t = stack.remove(stack.size() - 1);
            if (t.k != K.IDENT) {
                if (t.k == K.NL)
                    lineCursor++;
                result.add(t);
                continue;
            }
            Macro m = macros.get(t.text);
            if (m == null || t.hide.contains(t.text)) {
                if (t.text.equals("__LINE__") && !macros.containsKey("__LINE__")) {
                    result.add(new Tok(K.NUMBER, String.valueOf(ifContext != null ? curLine : lineCursor)));
                } else if (t.text.equals("__FILE__") && !macros.containsKey("__FILE__")) {
                    result.add(new Tok(K.NUMBER, "0"));
                } else {
                    result.add(t);
                }
                continue;
            }
            if (m.params == null) {
                Set<String> hs = new HashSet<>(t.hide);
                hs.add(m.name);
                pushFront(stack, withHide(m.body, hs), true);
                continue;
            }
            // function-like: needs '(' next (skipping whitespace, newlines, comments)
            int look = stack.size() - 1;
            while (look >= 0 && (stack.get(look).k == K.WS || stack.get(look).k == K.NL || stack.get(look).k == K.COMMENT))
                look--;
            if (look < 0 || !stack.get(look).text.equals("(")) {
                result.add(t);
                continue;
            }
            // consume up to '('
            while (stack.size() - 1 > look)
                stack.remove(stack.size() - 1);
            stack.remove(stack.size() - 1); // '('
            List<List<Tok>> args = new ArrayList<>();
            List<Tok> cur = new ArrayList<>();
            int depth = 0;
            Tok close = null;
            while (!stack.isEmpty()) {
                Tok a = stack.remove(stack.size() - 1);
                if (a.text.equals("(")) {
                    depth++;
                } else if (a.text.equals(")")) {
                    if (depth == 0) {
                        close = a;
                        break;
                    }
                    depth--;
                } else if (a.text.equals(",") && depth == 0) {
                    args.add(cur);
                    cur = new ArrayList<>();
                    continue;
                }
                cur.add(a.k == K.NL ? new Tok(K.WS, " ") : a);
            }
            if (close == null)
                throw new CppException(curFile, curLine, "unterminated call of macro " + m.name);
            args.add(cur);
            if (m.params.isEmpty() && args.size() == 1 && trimWs(args.get(0)).isEmpty())
                args.clear();
            if (args.size() != m.params.size())
                throw new CppException(curFile, curLine, "macro " + m.name + " takes " + m.params.size() + " arguments, got " + args.size());
            Set<String> hs = new HashSet<>(t.hide);
            hs.retainAll(close.hide);
            hs.add(m.name);
            List<Tok> sub = substitute(m, args, ifContext);
            pushFront(stack, withHide(sub, hs), true);
        }
        return result;
    }

    private static void pushFront(ArrayList<Tok> stack, List<Tok> toks, boolean pad) {
        // surround with spaces so expansions never glue onto neighbours ("-" "-1" -> "- -1")
        if (pad)
            stack.add(new Tok(K.WS, " "));
        for (int k = toks.size() - 1; k >= 0; k--)
            stack.add(toks.get(k));
        if (pad)
            stack.add(new Tok(K.WS, " "));
    }

    private static List<Tok> withHide(List<Tok> body, Set<String> hs) {
        List<Tok> r = new ArrayList<>(body.size());
        for (Tok b : body) {
            if (b.hide.isEmpty()) {
                r.add(b.withHide(hs));
            } else {
                Set<String> u = new HashSet<>(b.hide);
                u.addAll(hs);
                r.add(b.withHide(u));
            }
        }
        return r;
    }

    private List<Tok> substitute(Macro m, List<List<Tok>> args, Boolean ifContext) {
        List<Tok> body = m.body;
        List<Tok> out = new ArrayList<>();
        for (int k = 0; k < body.size(); k++) {
            Tok b = body.get(k);
            int pi = b.k == K.IDENT ? m.params.indexOf(b.text) : -1;
            if (pi < 0) {
                out.add(b);
                continue;
            }
            boolean pasted = nextNonWs(body, k, 1) != null && nextNonWs(body, k, 1).text.equals("##")
                    || nextNonWs(body, k, -1) != null && nextNonWs(body, k, -1).text.equals("##");
            List<Tok> arg = trimWs(args.get(pi));
            out.addAll(pasted ? arg : expand(arg, ifContext));
        }
        // ## pasting
        if (out.stream().noneMatch(t -> t.text.equals("##")))
            return out;
        List<Tok> r = new ArrayList<>();
        for (int k = 0; k < out.size(); k++) {
            Tok t = out.get(k);
            if (t.text.equals("##")) {
                while (!r.isEmpty() && r.get(r.size() - 1).k == K.WS)
                    r.remove(r.size() - 1);
                int j = k + 1;
                while (j < out.size() && out.get(j).k == K.WS)
                    j++;
                String left = r.isEmpty() ? "" : r.remove(r.size() - 1).text;
                String right = j < out.size() ? out.get(j).text : "";
                List<Tok> glued = lex(left + right);
                r.addAll(glued);
                k = j;
            } else {
                r.add(t);
            }
        }
        return r;
    }

    private static Tok nextNonWs(List<Tok> l, int from, int dir) {
        for (int k = from + dir; k >= 0 && k < l.size(); k += dir)
            if (l.get(k).k != K.WS)
                return l.get(k);
        return null;
    }

    // ------------------------------------------------------------------------------------------- #if

    /** Evaluates a #if expression with the current macros. */
    public boolean evalCondition(String expr) {
        return evalValue(expr).truthy();
    }

    public Num evalValue(String expr) {
        List<Tok> toks = lex(expr);
        // fold "defined X" / "defined(X)" before expansion
        List<Tok> folded = new ArrayList<>();
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.k == K.IDENT && t.text.equals("defined")) {
                int j = k + 1;
                while (j < toks.size() && toks.get(j).k == K.WS)
                    j++;
                boolean paren = j < toks.size() && toks.get(j).text.equals("(");
                if (paren) {
                    j++;
                    while (j < toks.size() && toks.get(j).k == K.WS)
                        j++;
                }
                if (j >= toks.size() || toks.get(j).k != K.IDENT)
                    throw new CppException(curFile, curLine, "'defined' without a name in: " + expr);
                String name = toks.get(j).text;
                if (paren) {
                    j++;
                    while (j < toks.size() && toks.get(j).k == K.WS)
                        j++;
                    if (j >= toks.size() || !toks.get(j).text.equals(")"))
                        throw new CppException(curFile, curLine, "'defined(' without ')' in: " + expr);
                }
                folded.add(new Tok(K.NUMBER, macros.containsKey(name) ? "1" : "0"));
                k = j;
            } else {
                folded.add(t);
            }
        }
        List<Tok> exp = expand(folded, Boolean.TRUE);
        List<Tok> clean = new ArrayList<>();
        for (Tok t : exp)
            if (t.k != K.WS && t.k != K.NL && t.k != K.COMMENT)
                clean.add(t);
        ExprParser p = new ExprParser(clean, expr);
        Num v = p.ternary();
        if (p.pos != clean.size())
            throw new CppException(curFile, curLine, "cannot evaluate #if " + expr + " (stopped at '" + clean.get(p.pos).text + "')");
        return v;
    }

    /** A #if value: integer or float (packs compare float options in #if; JCPP-style tolerance). */
    public record Num(double d, boolean isInt) {
        boolean truthy() {
            return d != 0;
        }

        long l() {
            return (long) d;
        }

        static Num of(boolean b) {
            return new Num(b ? 1 : 0, true);
        }
    }

    private final class ExprParser {
        final List<Tok> t;
        final String src;
        int pos;

        ExprParser(List<Tok> t, String src) {
            this.t = t;
            this.src = src;
        }

        boolean at(String s) {
            return pos < t.size() && t.get(pos).text.equals(s);
        }

        boolean eat(String s) {
            if (at(s)) {
                pos++;
                return true;
            }
            return false;
        }

        Num ternary() {
            Num c = binary(0);
            if (eat("?")) {
                Num a = ternary();
                if (!eat(":"))
                    throw err("expected ':'");
                Num b = ternary();
                return c.truthy() ? a : b;
            }
            return c;
        }

        static final String[][] LEVELS = {
                {"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}};

        Num binary(int level) {
            if (level == LEVELS.length)
                return unary();
            Num a = binary(level + 1);
            while (true) {
                String op = null;
                for (String o : LEVELS[level])
                    if (at(o)) {
                        op = o;
                        break;
                    }
                if (op == null)
                    return a;
                // "<" ">" are single tokens; "<=" lexed as two-char
                pos++;
                Num b = binary(level + 1);
                a = apply(op, a, b);
            }
        }

        Num apply(String op, Num a, Num b) {
            boolean ints = a.isInt && b.isInt;
            return switch (op) {
                case "||" -> Num.of(a.truthy() || b.truthy());
                case "&&" -> Num.of(a.truthy() && b.truthy());
                case "|" -> new Num(a.l() | b.l(), true);
                case "^" -> new Num(a.l() ^ b.l(), true);
                case "&" -> new Num(a.l() & b.l(), true);
                case "==" -> Num.of(a.d == b.d);
                case "!=" -> Num.of(a.d != b.d);
                case "<" -> Num.of(a.d < b.d);
                case ">" -> Num.of(a.d > b.d);
                case "<=" -> Num.of(a.d <= b.d);
                case ">=" -> Num.of(a.d >= b.d);
                case "<<" -> new Num(a.l() << b.l(), true);
                case ">>" -> new Num(a.l() >> b.l(), true);
                case "+" -> new Num(a.d + b.d, ints);
                case "-" -> new Num(a.d - b.d, ints);
                case "*" -> new Num(a.d * b.d, ints);
                case "/" -> {
                    if (b.d == 0)
                        throw err("division by zero");
                    yield ints ? new Num(a.l() / b.l(), true) : new Num(a.d / b.d, false);
                }
                case "%" -> {
                    if (b.d == 0)
                        throw err("modulo by zero");
                    yield ints ? new Num(a.l() % b.l(), true) : new Num(a.d % b.d, false);
                }
                default -> throw err("operator " + op);
            };
        }

        Num unary() {
            if (eat("!"))
                return Num.of(!unary().truthy());
            if (eat("-")) {
                Num v = unary();
                return new Num(-v.d, v.isInt);
            }
            if (eat("+"))
                return unary();
            if (eat("~"))
                return new Num(~unary().l(), true);
            if (eat("(")) {
                Num v = ternary();
                if (!eat(")"))
                    throw err("expected ')'");
                return v;
            }
            if (pos >= t.size())
                throw err("unexpected end");
            Tok x = t.get(pos++);
            if (x.k == K.NUMBER)
                return parseNumber(x.text);
            if (x.k == K.IDENT) {
                if (x.text.equals("true"))
                    return Num.of(true);
                // an identifier left after expansion is 0 (C rule); "false" too
                return new Num(0, true);
            }
            throw err("unexpected '" + x.text + "'");
        }

        CppException err(String m) {
            return new CppException(curFile, curLine, "cannot evaluate #if " + src.trim() + ": " + m);
        }
    }

    static Num parseNumber(String s) {
        String x = s;
        try {
            if (x.startsWith("0x") || x.startsWith("0X")) {
                x = x.replaceAll("[uUlL]+$", "");
                return new Num(Long.parseLong(x.substring(2), 16), true);
            }
            if (x.matches("[0-9]+[uUlL]*")) {
                x = x.replaceAll("[uUlL]+$", "");
                if (x.length() > 1 && x.startsWith("0"))
                    return new Num(Long.parseLong(x, 8), true);
                return new Num(Long.parseLong(x), true);
            }
            x = x.replaceAll("(lf|LF|[fF])$", "");
            return new Num(Double.parseDouble(x), false);
        } catch (NumberFormatException e) {
            throw new CppException(null, 0, "bad number '" + s + "'");
        }
    }
}
