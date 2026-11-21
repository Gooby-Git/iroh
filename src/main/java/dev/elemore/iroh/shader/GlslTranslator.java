package dev.elemore.iroh.shader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a vanilla-style (GLSL 1.50, OpenGL) vertex + fragment pair into Vulkan GLSL 4.50.
 * <p>
 * Replaces the base's line-based {@code GlslConverter} for mod and resource-pack shaders (P1-2a). It
 * tokenizes the whole source (comments stripped, preprocessor lines kept as units), finds the global
 * declarations at brace depth 0 and rewrites only those, in place; function bodies and everything else
 * pass through. Handles multiple declarators per statement, arrays, initializers, interpolation and
 * layout qualifiers, declarations split over lines, several statements per line, trailing tokens.
 * <p>
 * Rules (GL semantics the pack sees under OpenGL):
 * <ul>
 *   <li>Non-sampler uniforms of basic type go into one std140 block at binding 0 shared by both stages,
 *       with explicit member offsets computed by {@link #layoutUniforms} (the same layout the runtime UBO
 *       uses). Uniforms the JSON does not list stay in the block and read zero, as an unset GL uniform does.
 *       Uniforms with an initializer that the JSON does not list keep their initializer (GL keeps it).</li>
 *   <li>Uniforms of other types (struct, array, bool, mat2...) become private globals initialised to zero
 *       ({@code GL_EXT_null_initializer}); the JSON cannot feed them in vanilla either.</li>
 *   <li>Samplers get bindings 1..n in place. {@code imageIdx} (the emulated GL texture unit) is the sampler's
 *       index in the JSON list, because vanilla binds JSON sampler j to unit j; a sampler the JSON does not
 *       list reads unit 0, as an unset GL sampler uniform does.</li>
 *   <li>Vertex inputs take the location of their name in the vertex format (vanilla binds attribute
 *       locations by format element name); an input the format does not have becomes a private global with
 *       GL's default attribute value (0,0,0,1).</li>
 *   <li>Varyings get locations by name, shared between stages (explicit {@code layout(location)} kept).</li>
 *   <li>Fragment outputs keep their names; without a location they get 0,1,... in order.
 *       {@code gl_FragColor} gets a declared output.</li>
 *   <li>{@code gl_VertexID/gl_InstanceID} become {@code gl_VertexIndex/gl_InstanceIndex}; identifiers that
 *       are keywords in 4.50 but not in 1.50 ({@code sample}, {@code patch}, ...) are renamed.</li>
 * </ul>
 */
public final class GlslTranslator {

    public enum Stage { VERTEX, FRAGMENT }

    /** A non-sampler uniform that lives in the shared block. */
    public record UniformDecl(String type, String name, int offsetBytes) {}

    /** A sampler: descriptor binding and emulated texture unit. */
    public record SamplerDecl(String type, String name, int binding, int imageIdx) {}

    public record Output(String vertex, String fragment, List<UniformDecl> uniforms, List<SamplerDecl> samplers,
                         List<String> zeroUniforms, List<String> notes) {}

    public static final class TranslationException extends RuntimeException {
        public final Stage stage;

        TranslationException(Stage stage, String message) {
            super(message);
            this.stage = stage;
        }
    }

    /**
     * Computes the std140 layout of the shared block. Must return byte offsets matching the runtime UBO
     * (the caller builds the UBO from the same list, see {@link ShaderConversion}).
     */
    public interface UniformLayout {
        /** @return byte offset per uniform, same order as the input; throws for an unsupported type */
        int[] offsets(List<String[]> typeAndName);
    }

    // ---------------------------------------------------------------- configuration

    private final List<String> attributeNames;   // vertex format element names, or null = declaration order
    private final List<String> jsonSamplers;     // JSON sampler names in order, or null = declaration order
    private final Set<String> jsonUniforms;      // JSON uniform names, or null = all count as present
    private final UniformLayout layout;

    public GlslTranslator(List<String> attributeNames, List<String> jsonSamplers, Set<String> jsonUniforms,
                          UniformLayout layout) {
        this.attributeNames = attributeNames;
        this.jsonSamplers = jsonSamplers;
        this.jsonUniforms = jsonUniforms;
        this.layout = layout;
    }

    // Types the runtime UBO can hold (dev Uniform.createUniformInfo). Anything else: private zero global.
    public static final Set<String> BLOCK_TYPES = Set.of("float", "int", "vec2", "vec3", "vec4",
            "ivec2", "ivec3", "ivec4", "mat3", "mat4");

    private static final Set<String> STORAGE = Set.of("uniform", "in", "out", "varying", "attribute");
    private static final Set<String> OTHER_QUALIFIERS = Set.of("flat", "smooth", "noperspective", "centroid",
            "invariant", "highp", "mediump", "lowp", "const", "precise");
    private static final Set<String> INTERPOLATION = Set.of("flat", "smooth", "noperspective", "centroid", "invariant");
    private static final Set<String> PRECISION = Set.of("highp", "mediump", "lowp");

    // keywords in GLSL 4.50 (Vulkan) that are plain identifiers in 1.50
    private static final Set<String> NEW_KEYWORDS = Set.of("sample", "patch", "subroutine", "precise", "buffer",
            "shared", "coherent", "restrict", "readonly", "writeonly", "resource", "atomic_uint");

    // ---------------------------------------------------------------- tokens

    private enum T { IDENT, NUMBER, PUNCT, PREPROC }

    private record Tok(T kind, String text, int start, int end) {
        boolean is(String s) {
            return text.equals(s);
        }
    }

    /** Comments become spaces (newlines kept) so offsets and line structure survive. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    // a line comment ends at the newline, but "\\\n" continues it
                    if (s.charAt(i) == '\\' && i + 1 < n && s.charAt(i + 1) == '\n') {
                        out.append(' ').append('\n');
                        i += 2;
                        continue;
                    }
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<Tok> tokenize(String s) {
        List<Tok> toks = new ArrayList<>();
        int n = s.length();
        int i = 0;
        boolean lineStart = true;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                lineStart = true;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#' && lineStart) {
                int st = i;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '\\' && i + 1 < n && s.charAt(i + 1) == '\n') {
                        i += 2;
                        continue;
                    }
                    if (d == '\\' && i + 2 < n && s.charAt(i + 1) == '\r' && s.charAt(i + 2) == '\n') {
                        i += 3;
                        continue;
                    }
                    if (d == '\n')
                        break;
                    i++;
                }
                toks.add(new Tok(T.PREPROC, s.substring(st, i), st, i));
                continue;
            }
            lineStart = false;
            int st = i;
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'))
                    i++;
                toks.add(new Tok(T.IDENT, s.substring(st, i), st, i));
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                while (i < n) {
                    char d = s.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '.' || d == '_') {
                        i++;
                    } else if ((d == '+' || d == '-') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')
                            && !s.substring(st, i).startsWith("0x")) {
                        i++;
                    } else {
                        break;
                    }
                }
                toks.add(new Tok(T.NUMBER, s.substring(st, i), st, i));
            } else {
                i++;
                toks.add(new Tok(T.PUNCT, String.valueOf(c), st, i));
            }
        }
        return toks;
    }

    // ---------------------------------------------------------------- parsed declarations

    private enum DKind { UNIFORM, SAMPLER, IN, OUT }

    private static final class Decl {
        DKind kind;
        String layoutArgs;        // inside layout( ... ), or null
        List<String> quals = new ArrayList<>();
        String type;
        String typeArray = "";    // e.g. "[4]" written after the type
        String name;
        String array = "";        // e.g. "[4]" written after the name
        String init;              // initializer text, or null
        int start, end;           // span of the whole statement in the stripped source (only on first declarator)
        boolean first;
        Integer location;         // explicit or assigned
    }

    private static final class StageSrc {
        Stage stage;
        String text;              // comment-stripped
        List<Tok> toks;
        int version = 110;
        boolean hasVersion;
        List<String> extensions = new ArrayList<>();
        List<int[]> dropSpans = new ArrayList<>();   // #version / #extension lines
        List<List<Decl>> stmts = new ArrayList<>();  // one list per handled statement (several declarators)
        boolean usesFragColor;
        boolean needsNullInit;
        List<String> mainPrologue = new ArrayList<>(); // rc8: statements inserted at the start of main()
    }

    /**
     * rc8 (td-40ae94): vanilla binds the integer UV elements (UV1 overlay, UV2 lightmap: SHORT) with
     * glVertexAttribIPointer. A shader that declares them as float vectors (some pack / mod core shaders write
     * {@code in vec2 UV2;}) is a mismatch Vulkan reports (R16G16_SINT feeding a float input). The input is declared with
     * the integer type the format supplies and converted once at the start of main() into a private float global of the
     * declared name, so the shader's code is unchanged and the values are the numeric light / overlay coordinates.
     */
    private static final Set<String> INTEGER_ATTRIBUTES = Set.of("UV1", "UV2");

    private static String integerTwin(String floatType) {
        return switch (floatType) {
            case "float" -> "int";
            case "vec2" -> "ivec2";
            case "vec3" -> "ivec3";
            case "vec4" -> "ivec4";
            default -> null;
        };
    }

    // ---------------------------------------------------------------- entry point

    public Output translate(String vertexSrc, String fragmentSrc) {
        StageSrc vs = parse(Stage.VERTEX, vertexSrc);
        StageSrc fs = parse(Stage.FRAGMENT, fragmentSrc);
        List<String> notes = new ArrayList<>();

        // ---- uniforms (shared block) and samplers, merged over both stages by name
        LinkedHashMap<String, String> blockUniforms = new LinkedHashMap<>();
        LinkedHashMap<String, String> samplerTypes = new LinkedHashMap<>();
        for (StageSrc st : List.of(vs, fs)) {
            for (List<Decl> stmt : st.stmts) {
                for (Decl d : stmt) {
                    if (d.kind == DKind.SAMPLER) {
                        String prev = samplerTypes.putIfAbsent(d.name, d.type + d.typeArray + d.array);
                        if (prev != null && !prev.equals(d.type + d.typeArray + d.array))
                            throw new TranslationException(st.stage, "sampler " + d.name + " declared as " + prev + " and " + d.type);
                    } else if (d.kind == DKind.UNIFORM && inBlock(d)) {
                        String prev = blockUniforms.putIfAbsent(d.name, d.type);
                        if (prev != null && !prev.equals(d.type))
                            throw new TranslationException(st.stage, "uniform " + d.name + " declared as " + prev + " and " + d.type);
                    }
                }
            }
        }

        if (blockUniforms.isEmpty())
            blockUniforms.put("IrohPad", "vec4"); // a block needs one member; zero slot, never read

        List<String[]> typeAndName = new ArrayList<>();
        blockUniforms.forEach((n, t) -> typeAndName.add(new String[]{t, n}));
        int[] offsets = layout.offsets(typeAndName);
        List<UniformDecl> uniforms = new ArrayList<>();
        List<String> zero = new ArrayList<>();
        for (int i = 0; i < typeAndName.size(); i++) {
            String n = typeAndName.get(i)[1];
            uniforms.add(new UniformDecl(typeAndName.get(i)[0], n, offsets[i]));
            if (jsonUniforms != null && !jsonUniforms.contains(n) && !n.equals("IrohPad"))
                zero.add(n);
        }

        // uniforms that cannot live in the block read a zero constant (or their initializer)
        Set<String> seenPrivate = new HashSet<>();
        for (StageSrc st : List.of(vs, fs))
            for (List<Decl> stmt : st.stmts)
                for (Decl d : stmt)
                    if (d.kind == DKind.UNIFORM && !inBlock(d) && d.init == null && seenPrivate.add(d.name)) {
                        zero.add(d.name);
                        if (jsonUniforms != null && jsonUniforms.contains(d.name))
                            notes.add("uniform " + d.name + " (" + d.type + d.typeArray + d.array + ") is in the JSON but cannot be fed: zero");
                    }

        // samplers: JSON order first (that index is the GL unit), then GLSL-only ones (unit 0)
        List<SamplerDecl> samplers = new ArrayList<>();
        Map<String, Integer> samplerBinding = new HashMap<>();
        int binding = 1;
        List<String> ordered = new ArrayList<>();
        if (jsonSamplers != null)
            for (String s : jsonSamplers)
                if (samplerTypes.containsKey(s) && !ordered.contains(s))
                    ordered.add(s);
        for (String s : samplerTypes.keySet())
            if (!ordered.contains(s))
                ordered.add(s);
        for (int i = 0; i < ordered.size(); i++) {
            String s = ordered.get(i);
            int unit;
            if (jsonSamplers == null) {
                unit = i;
            } else {
                int j = jsonSamplers.indexOf(s);
                unit = j >= 0 ? j : 0;
                if (j < 0)
                    notes.add("sampler " + s + " not in JSON: reads unit 0");
            }
            if (!samplerTypes.get(s).startsWith("sampler2D") || samplerTypes.get(s).contains("["))
                notes.add("sampler " + s + " is " + samplerTypes.get(s) + ": bound as a 2D image");
            samplers.add(new SamplerDecl(samplerTypes.get(s), s, binding, unit));
            samplerBinding.put(s, binding);
            binding++;
        }

        // ---- varyings: locations by name, shared between stages
        Map<String, Integer> varyingLoc = new HashMap<>();
        Set<Integer> usedVarying = new HashSet<>();
        for (StageSrc st : List.of(vs, fs))
            for (List<Decl> stmt : st.stmts)
                for (Decl d : stmt)
                    if (isVarying(st.stage, d) && d.location != null) {
                        varyingLoc.putIfAbsent(d.name, d.location);
                        for (int k = 0; k < slots(d); k++)
                            usedVarying.add(d.location + k);
                    }
        for (StageSrc st : List.of(vs, fs))
            for (List<Decl> stmt : st.stmts)
                for (Decl d : stmt)
                    if (isVarying(st.stage, d) && !varyingLoc.containsKey(d.name)) {
                        int need = slots(d);
                        int loc = 0;
                        while (!free(usedVarying, loc, need))
                            loc++;
                        for (int k = 0; k < need; k++)
                            usedVarying.add(loc + k);
                        varyingLoc.put(d.name, loc);
                    }
        for (StageSrc st : List.of(vs, fs))
            for (List<Decl> stmt : st.stmts)
                for (Decl d : stmt)
                    if (isVarying(st.stage, d))
                        d.location = varyingLoc.get(d.name);

        // vertex inputs
        int seq = 0;
        for (List<Decl> stmt : vs.stmts)
            for (Decl d : stmt)
                if (d.kind == DKind.IN && d.location == null && !d.name.startsWith("gl_")) {
                    if (attributeNames != null) {
                        int idx = attributeNames.indexOf(d.name);
                        if (idx >= 0) {
                            d.location = idx;
                        } else {
                            notes.add("vertex input " + d.name + " not in the vertex format: constant default");
                        }
                    } else {
                        d.location = seq;
                        seq += slots(d);
                    }
                }

        // fragment outputs
        Set<Integer> usedOut = new HashSet<>();
        for (List<Decl> stmt : fs.stmts)
            for (Decl d : stmt)
                if (d.kind == DKind.OUT && d.location != null)
                    usedOut.add(d.location);
        int outLoc = 0;
        boolean anyOut = false;
        for (List<Decl> stmt : fs.stmts)
            for (Decl d : stmt)
                if (d.kind == DKind.OUT && !d.name.startsWith("gl_")) {
                    anyOut = true;
                    if (d.location == null) {
                        while (usedOut.contains(outLoc))
                            outLoc++;
                        d.location = outLoc;
                        usedOut.add(outLoc);
                    }
                }

        String header = blockCode(uniforms);
        String vOut = generate(vs, header, samplerBinding, false);
        String fOut = generate(fs, header, samplerBinding, fs.usesFragColor && !anyOut);
        return new Output(vOut, fOut, uniforms, samplers, zero, notes);
    }

    private boolean inBlock(Decl d) {
        if (!BLOCK_TYPES.contains(d.type) || !d.array.isEmpty() || !d.typeArray.isEmpty())
            return false;
        // an initialised uniform the JSON does not set keeps its initializer, as in GL
        return d.init == null || jsonUniforms == null || jsonUniforms.contains(d.name);
    }

    private static boolean isVarying(Stage stage, Decl d) {
        if (d.name.startsWith("gl_"))
            return false;
        return stage == Stage.VERTEX ? d.kind == DKind.OUT : d.kind == DKind.IN;
    }

    private static boolean free(Set<Integer> used, int loc, int n) {
        for (int k = 0; k < n; k++)
            if (used.contains(loc + k))
                return false;
        return true;
    }

    private static int slots(Decl d) {
        int per = switch (d.type) {
            case "mat2", "mat2x2", "mat2x3", "mat2x4", "dvec3", "dvec4" -> 2;
            case "mat3", "mat3x2", "mat3x3", "mat3x4" -> 3;
            case "mat4", "mat4x2", "mat4x3", "mat4x4" -> 4;
            default -> 1;
        };
        int count = arrayCount(d.typeArray) * arrayCount(d.array);
        return per * count;
    }

    private static int arrayCount(String arr) {
        if (arr.isEmpty())
            return 1;
        String inner = arr.substring(1, arr.length() - 1).trim();
        try {
            return Math.max(1, Integer.parseInt(inner));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ---------------------------------------------------------------- parsing

    private StageSrc parse(Stage stage, String src) {
        StageSrc st = new StageSrc();
        st.stage = stage;
        st.text = stripComments(src.replace("\r\n", "\n"));
        st.toks = tokenize(st.text);
        List<Tok> toks = st.toks;

        int depthBrace = 0;
        int depthParen = 0;
        boolean stmtStart = true;
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.kind == T.PREPROC) {
                String body = t.text.substring(1).trim();
                if (body.startsWith("version")) {
                    String[] parts = body.split("\\s+");
                    if (parts.length > 1) {
                        try {
                            st.version = Integer.parseInt(parts[1]);
                            st.hasVersion = true;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    st.dropSpans.add(new int[]{t.start, t.end});
                } else if (body.startsWith("extension")) {
                    st.extensions.add(t.text.trim());
                    st.dropSpans.add(new int[]{t.start, t.end});
                }
                continue;
            }
            if (t.kind == T.IDENT && (t.is("gl_FragColor")) && stage == Stage.FRAGMENT)
                st.usesFragColor = true;

            if (depthBrace == 0 && depthParen == 0 && stmtStart && t.kind == T.IDENT
                    && (STORAGE.contains(t.text) || OTHER_QUALIFIERS.contains(t.text) || t.is("layout"))
                    && !t.is("const")) {
                int end = findStatementEnd(toks, i);
                if (end < 0)
                    throw new TranslationException(stage, "unterminated declaration starting with '" + t.text + "'");
                List<Decl> decls = parseDeclaration(stage, st, toks, i, end);
                if (decls != null)
                    st.stmts.add(decls);
                i = end; // the ';'
                stmtStart = true;
                continue;
            }

            switch (t.text) {
                case "{" -> depthBrace++;
                case "}" -> {
                    depthBrace--;
                    if (depthBrace == 0)
                        stmtStart = true;
                    continue;
                }
                case "(" -> depthParen++;
                case ")" -> depthParen--;
                case ";" -> {
                    if (depthBrace == 0 && depthParen == 0) {
                        stmtStart = true;
                        continue;
                    }
                }
                default -> {
                }
            }
            if (depthBrace == 0)
                stmtStart = false;
        }
        if (depthBrace != 0)
            throw new TranslationException(stage, "unbalanced braces");
        return st;
    }

    /** @return index of the ';' that ends the statement starting at {@code from}, or -1 */
    private static int findStatementEnd(List<Tok> toks, int from) {
        int depth = 0;
        for (int i = from; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.kind == T.PREPROC)
                continue;
            switch (t.text) {
                case "{", "(", "[" -> depth++;
                case "}", ")", "]" -> depth--;
                case ";" -> {
                    if (depth == 0)
                        return i;
                }
                default -> {
                }
            }
        }
        return -1;
    }

    /** @return declarators of a handled statement, or null to leave the statement as written */
    private List<Decl> parseDeclaration(Stage stage, StageSrc st, List<Tok> toks, int from, int end) {
        for (int k = from; k < end; k++)
            if (toks.get(k).kind == T.PREPROC)
                throw new TranslationException(stage, "preprocessor directive inside a declaration near '" + toks.get(from).text + "'");

        int i = from;
        String layoutArgs = null;
        List<String> quals = new ArrayList<>();
        String storage = null;
        while (i < end) {
            Tok t = toks.get(i);
            if (t.is("layout")) {
                int close = matching(toks, i + 1, end);
                if (close < 0)
                    throw new TranslationException(stage, "bad layout qualifier");
                layoutArgs = st.text.substring(toks.get(i + 1).end, toks.get(close).start).trim();
                i = close + 1;
            } else if (STORAGE.contains(t.text)) {
                storage = t.text;
                i++;
            } else if (OTHER_QUALIFIERS.contains(t.text)) {
                quals.add(t.text);
                i++;
            } else {
                break;
            }
        }
        if (storage == null)
            return null; // e.g. "highp float x;" or "precision ..." handled as plain code
        if (i >= end)
            return null; // "layout(...) in;" style global layout declarations

        Tok typeTok = toks.get(i);
        if (typeTok.kind != T.IDENT)
            throw new TranslationException(stage, "expected a type after '" + storage + "', got '" + typeTok.text + "'");
        if (i + 1 < end && toks.get(i + 1).is("{"))
            throw new TranslationException(stage, "interface/uniform block '" + typeTok.text + "' is not supported");
        String type = typeTok.text;
        i++;
        String typeArray = "";
        if (i < end && toks.get(i).is("[")) {
            int close = matching(toks, i, end);
            typeArray = st.text.substring(toks.get(i).start, toks.get(close).end).replaceAll("\\s+", "");
            i = close + 1;
        }

        DKind kind = switch (storage) {
            case "uniform" -> isSamplerType(type) ? DKind.SAMPLER : DKind.UNIFORM;
            case "in", "attribute" -> DKind.IN;
            case "out" -> DKind.OUT;
            case "varying" -> stage == Stage.VERTEX ? DKind.OUT : DKind.IN;
            default -> throw new IllegalStateException();
        };

        Integer explicitLoc = null;
        if (layoutArgs != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("location\\s*=\\s*(\\d+)").matcher(layoutArgs);
            if (m.find())
                explicitLoc = Integer.parseInt(m.group(1));
        }

        List<Decl> out = new ArrayList<>();
        int declStart = i;
        boolean firstDecl = true;
        while (declStart < end) {
            int stop = declStart;
            int depth = 0;
            while (stop < end) {
                String s = toks.get(stop).text;
                if (s.equals("(") || s.equals("[") || s.equals("{"))
                    depth++;
                else if (s.equals(")") || s.equals("]") || s.equals("}"))
                    depth--;
                else if (s.equals(",") && depth == 0)
                    break;
                stop++;
            }
            Tok nameTok = toks.get(declStart);
            if (nameTok.kind != T.IDENT)
                throw new TranslationException(stage, "expected a name in declaration of type " + type + ", got '" + nameTok.text + "'");
            Decl d = new Decl();
            d.kind = kind;
            d.layoutArgs = layoutArgs;
            d.quals = quals;
            d.type = type;
            d.typeArray = typeArray;
            d.name = nameTok.text;
            d.location = explicitLoc;
            int j = declStart + 1;
            if (j < stop && toks.get(j).is("[")) {
                int close = matching(toks, j, stop);
                d.array = st.text.substring(toks.get(j).start, toks.get(close).end).replaceAll("\\s+", "");
                j = close + 1;
            }
            if (j < stop && toks.get(j).is("=")) {
                d.init = st.text.substring(toks.get(j + 1).start, toks.get(stop - 1).end);
                j = stop;
            }
            if (j != stop)
                throw new TranslationException(stage, "unexpected '" + toks.get(j).text + "' in declaration of " + d.name);
            d.first = firstDecl;
            d.start = toks.get(from).start;
            d.end = toks.get(end).end;
            firstDecl = false;
            out.add(d);
            if (explicitLoc != null && (kind == DKind.IN || kind == DKind.OUT) && out.size() > 1)
                throw new TranslationException(stage, "explicit location on a multi-declarator statement");
            declStart = stop + 1;
        }
        return out;
    }

    private static int matching(List<Tok> toks, int open, int limit) {
        String o = toks.get(open).text;
        String c = switch (o) {
            case "(" -> ")";
            case "[" -> "]";
            case "{" -> "}";
            default -> null;
        };
        if (c == null)
            return -1;
        int depth = 0;
        for (int i = open; i < limit; i++) {
            String s = toks.get(i).text;
            if (s.equals(o))
                depth++;
            else if (s.equals(c) && --depth == 0)
                return i;
        }
        return -1;
    }

    private static boolean isSamplerType(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler");
    }

    // ---------------------------------------------------------------- generation

    private static String blockCode(List<UniformDecl> uniforms) {
        StringBuilder sb = new StringBuilder("layout(std140, binding = 0) uniform IrohUniforms {\n");
        for (UniformDecl u : uniforms)
            sb.append("    layout(offset = ").append(u.offsetBytes()).append(") ").append(u.type()).append(' ')
              .append(u.name()).append(";\n");
        sb.append("};\n");
        return sb.toString();
    }

    private String generate(StageSrc st, String block, Map<String, Integer> samplerBinding, boolean addFragColor) {
        Map<String, String> renames = renames(st);

        // replacement text per handled statement
        record Edit(int start, int end, String text) {}
        List<Edit> edits = new ArrayList<>();
        for (int[] span : st.dropSpans)
            edits.add(new Edit(span[0], span[1], ""));

        for (List<Decl> stmt : st.stmts) {
            StringBuilder sb = new StringBuilder();
            for (Decl d : stmt) {
                String name = renames.getOrDefault(d.name, d.name);
                String fullType = d.type + d.typeArray;
                switch (d.kind) {
                    case UNIFORM -> {
                        if (inBlock(d))
                            continue; // lives in the shared block
                        if (d.init != null) {
                            sb.append("const ").append(fullType).append(' ').append(name).append(d.array)
                              .append(" = ").append(d.init).append(";\n");
                        } else {
                            st.needsNullInit = true;
                            sb.append(fullType).append(' ').append(name).append(d.array).append(" = {};\n");
                        }
                    }
                    case SAMPLER -> {
                        sb.append("layout(binding = ").append(samplerBinding.get(d.name)).append(") uniform ")
                          .append(fullType).append(' ').append(name).append(d.array).append(";\n");
                    }
                    case IN, OUT -> {
                        if (d.name.startsWith("gl_")) {
                            sb.append(st.text, d.start, d.end).append('\n');
                            break; // builtin redeclaration: keep as written (once)
                        }
                        String io = d.kind == DKind.IN ? "in" : "out";
                        if (st.stage == Stage.VERTEX && d.kind == DKind.IN && d.location == null) {
                            sb.append(fullType).append(' ').append(name).append(d.array).append(" = ")
                              .append(defaultAttribute(d.type)).append(";\n");
                            break;
                        }
                        if (st.stage == Stage.VERTEX && d.kind == DKind.IN && attributeNames != null
                                && INTEGER_ATTRIBUTES.contains(d.name) && integerTwin(d.type) != null && d.array.isEmpty()) {
                            String twin = integerTwin(d.type);
                            sb.append("layout(location = ").append(d.location).append(") in ").append(twin)
                              .append(" iroh_int_").append(name).append(";\n");
                            sb.append(fullType).append(' ').append(name).append(";\n");
                            st.mainPrologue.add(name + " = " + d.type + "(iroh_int_" + name + ");");
                            break;
                        }
                        List<String> lq = new ArrayList<>();
                        if (d.layoutArgs != null) {
                            for (String a : d.layoutArgs.split(","))
                                if (!a.trim().startsWith("location") && !a.isBlank())
                                    lq.add(a.trim());
                        }
                        lq.add(0, "location = " + d.location);
                        sb.append("layout(").append(String.join(", ", lq)).append(") ");
                        for (String q : d.quals)
                            if (INTERPOLATION.contains(q))
                                sb.append(q).append(' ');
                        // integer varyings must be flat in Vulkan GLSL
                        if (isIntegerType(d.type) && !d.quals.contains("flat")
                                && ((st.stage == Stage.FRAGMENT && d.kind == DKind.IN) || (st.stage == Stage.VERTEX && d.kind == DKind.OUT)))
                            sb.append("flat ");
                        sb.append(io).append(' ').append(fullType).append(' ').append(name).append(d.array).append(";\n");
                    }
                }
                if (d.kind == DKind.IN || d.kind == DKind.OUT) {
                    if (d.name.startsWith("gl_"))
                        break; // the whole statement was copied
                }
            }
            Decl first = stmt.get(0);
            edits.add(new Edit(first.start, first.end, sb.toString()));
        }
        edits.sort((a, b) -> Integer.compare(a.start, b.start));

        StringBuilder body = new StringBuilder(st.text.length() + 512);
        int pos = 0;
        for (Edit e : edits) {
            if (e.start < pos)
                continue; // overlapping (cannot happen; guard)
            appendRenamed(body, st, pos, e.start, renames);
            body.append(e.text);
            pos = e.end;
        }
        appendRenamed(body, st, pos, st.text.length(), renames);
        if (!st.mainPrologue.isEmpty()) {
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{").matcher(body);
            if (!mm.find())
                throw new TranslationException(st.stage, "no main() to convert integer vertex inputs in");
            body.insert(mm.end(), "\n    " + String.join("\n    ", st.mainPrologue) + "\n");
        }

        StringBuilder out = new StringBuilder(body.length() + 1024);
        out.append("#version 450\n");
        for (String ext : st.extensions)
            out.append(ext).append('\n');
        if (st.needsNullInit)
            out.append("#extension GL_EXT_null_initializer : enable\n");
        out.append(block);
        if (addFragColor)
            out.append("layout(location = 0) out vec4 iroh_FragColor;\n");
        out.append("#line 1\n");
        out.append(body);
        return out.toString();
    }

    private Map<String, String> renames(StageSrc st) {
        Map<String, String> r = new HashMap<>();
        r.put("gl_VertexID", "gl_VertexIndex");
        r.put("gl_InstanceID", "gl_InstanceIndex");
        r.put("gl_FragColor", "iroh_FragColor");
        if (st.version < 400)
            for (String k : NEW_KEYWORDS)
                r.put(k, k + "_iroh");
        if (st.version < 130) {
            r.put("texture2D", "texture");
            r.put("texture2DLod", "textureLod");
            r.put("texture2DProj", "textureProj");
            r.put("textureCube", "texture");
            r.put("texture3D", "texture");
        }
        return r;
    }

    private static void appendRenamed(StringBuilder out, StageSrc st, int from, int to, Map<String, String> renames) {
        if (from >= to)
            return;
        String s = st.text;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < to && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_'))
                    j++;
                String id = s.substring(i, j);
                // keep "#define sample ..." style directives renamed as well: same name everywhere
                out.append(renames.getOrDefault(id, id));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < to && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.'))
                    j++;
                out.append(s, i, j);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
    }

    private static boolean isIntegerType(String t) {
        return t.equals("int") || t.equals("uint") || t.startsWith("ivec") || t.startsWith("uvec");
    }

    private static String defaultAttribute(String type) {
        return switch (type) {
            case "vec4" -> "vec4(0.0, 0.0, 0.0, 1.0)";
            case "ivec4" -> "ivec4(0, 0, 0, 1)";
            case "uvec4" -> "uvec4(0u, 0u, 0u, 1u)";
            default -> type + "(0)";
        };
    }
}
