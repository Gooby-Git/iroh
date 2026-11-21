package dev.elemore.iroh.shaderpack;

import dev.elemore.iroh.shaderpack.GlslLex.T;
import dev.elemore.iroh.shaderpack.GlslLex.Tok;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage (vi): translates one program of an Iris/OptiFine pack (GLSL 1.20 to 4.60, compatibility profile, after
 * {@link Cpp}) into Vulkan GLSL 4.60 for shaderc. Works on tokens and a declaration-level parse of the global
 * statements (function bodies pass through, identifiers renamed token by token); never regexes a file.
 * <p>
 * The program is the unit (Vitrail lesson): one uniform block with the union of both stages' uniforms in a
 * canonical (name) order, varyings located by name from one program-wide table. Fixed interface (decisions in
 * knowledge/iris-loader.md "P3-1 front end"):
 * <ul>
 *   <li>set 0 binding 0: std140 block {@code IrohPackUniforms}, explicit offsets, members sorted by name;
 *       compatibility matrices become members ({@code gl_ModelViewMatrix} -> {@code iroh_ModelViewMatrix} ...).</li>
 *   <li>set 0, other bindings: samplers and images from the pack-wide {@link PackBindings} table (stable
 *       across programs and options); set 1: SSBOs at the binding the pack wrote ({@code bufferObject.<i>});
 *       set 2: pack uniform blocks.</li>
 *   <li>vertex inputs at fixed locations by name ({@link #ATTRIBUTES}); unknown names from 24 up.</li>
 *   <li>fragment {@code gl_FragData[n]} -> {@code iroh_FragData[n]} at location n (location n = n-th draw buffer),
 *       {@code gl_FragColor} -> location 0.</li>
 *   <li>vertex epilogue: GL clip depth to Vulkan ({@code z = (z + w) / 2}), so depth buffers hold GL window
 *       depth; no Y flip (targets are stored GL-style, row 0 = bottom, flipped once at present).</li>
 *   <li>a fragment input no vertex output writes is owed by the vertex stage, which declares it and writes zero.</li>
 * </ul>
 * Anything it cannot translate fails the program with a {@link TranslateException} naming the construct and the
 * line in the preprocessed unit (the caller maps it back to the pack file).
 */
public final class PackGlsl {

    public enum Stage {
        VERTEX("vertex"), FRAGMENT("fragment"), GEOMETRY("geometry"), TESS_CONTROL("tess_control"),
        TESS_EVAL("tess_eval"), COMPUTE("compute");

        public final String key;

        Stage(String key) {
            this.key = key;
        }

        public static Stage of(String key) {
            for (Stage s : values())
                if (s.key.equals(key))
                    return s;
            throw new IllegalArgumentException(key);
        }
    }

    public static final class TranslateException extends RuntimeException {
        public final Stage stage;
        public final int line; // 1-based line in the preprocessed unit, 0 = unknown

        TranslateException(Stage stage, int line, String msg) {
            super(msg);
            this.stage = stage;
            this.line = line;
        }
    }

    /** Fixed vertex input locations. Types are what Iroh feeds; a pack may declare fewer components. */
    public static final Map<String, Integer> ATTRIBUTES = new LinkedHashMap<>();
    /** compatibility vertex built-ins -> [replacement name, type] */
    static final Map<String, String[]> VERTEX_BUILTIN_INPUTS = new LinkedHashMap<>();

    static {
        String[][] a = {{"iris_Vertex", "0"}, {"iris_Color", "1"}, {"iris_MultiTexCoord0", "2"}, {"iris_MultiTexCoord1", "3"},
                {"iris_MultiTexCoord2", "4"}, {"iris_MultiTexCoord3", "5"}, {"iris_Normal", "6"}, {"mc_Entity", "7"},
                {"mc_midTexCoord", "8"}, {"at_tangent", "9"}, {"at_midBlock", "10"}, {"at_velocity", "11"},
                {"vaPosition", "12"}, {"vaColor", "13"}, {"vaUV0", "14"}, {"vaUV1", "15"}, {"vaUV2", "16"},
                {"vaNormal", "17"}, {"iris_MultiTexCoord4", "18"}, {"iris_MultiTexCoord5", "19"},
                {"iris_MultiTexCoord6", "20"}, {"iris_MultiTexCoord7", "21"}, {"iris_SecondaryColor", "22"},
                {"iris_FogCoord", "23"}};
        for (String[] e : a)
            ATTRIBUTES.put(e[0], Integer.parseInt(e[1]));
        String[][] b = {{"gl_Vertex", "iris_Vertex", "vec4"}, {"gl_Color", "iris_Color", "vec4"},
                {"gl_Normal", "iris_Normal", "vec3"}, {"gl_SecondaryColor", "iris_SecondaryColor", "vec4"},
                {"gl_FogCoord", "iris_FogCoord", "float"}};
        for (String[] e : b)
            VERTEX_BUILTIN_INPUTS.put(e[0], new String[]{e[1], e[2]});
        for (int i = 0; i < 8; i++)
            VERTEX_BUILTIN_INPUTS.put("gl_MultiTexCoord" + i, new String[]{"iris_MultiTexCoord" + i, "vec4"});
    }

    /** compatibility uniform built-ins -> [member name, type, array] */
    static final Map<String, String[]> UNIFORM_BUILTINS = new LinkedHashMap<>();

    static {
        String[][] u = {{"gl_ModelViewMatrix", "mat4"}, {"gl_ProjectionMatrix", "mat4"}, {"gl_ModelViewProjectionMatrix", "mat4"},
                {"gl_ModelViewMatrixInverse", "mat4"}, {"gl_ProjectionMatrixInverse", "mat4"},
                {"gl_ModelViewProjectionMatrixInverse", "mat4"}, {"gl_ModelViewMatrixTranspose", "mat4"},
                {"gl_ProjectionMatrixTranspose", "mat4"}, {"gl_ModelViewMatrixInverseTranspose", "mat4"},
                {"gl_NormalMatrix", "mat3"}, {"gl_NormalScale", "float"}};
        for (String[] e : u)
            UNIFORM_BUILTINS.put(e[0], new String[]{"iroh_" + e[0].substring(3), e[1], "0"});
        UNIFORM_BUILTINS.put("gl_TextureMatrix", new String[]{"iroh_TextureMatrix", "mat4", "8"});
        UNIFORM_BUILTINS.put("gl_TextureMatrixInverse", new String[]{"iroh_TextureMatrixInverse", "mat4", "8"});
        UNIFORM_BUILTINS.put("gl_Fog", new String[]{"iroh_Fog", "IrohFogParameters", "0"});
    }

    private static Map<String, String> sorted(String... kv) {
        Map<String, String> m = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Core-profile (1.17+) names Iris declares itself when a stage uses them without declaring them. */
    static final Map<String, String> IMPLICIT_INPUTS = sorted("vaPosition", "vec3", "vaColor", "vec4", "vaUV0", "vec2",
            "vaUV1", "ivec2", "vaUV2", "ivec2", "vaNormal", "vec3");
    static final Map<String, String> IMPLICIT_UNIFORMS = sorted("modelViewMatrix", "mat4", "modelViewMatrixInverse", "mat4",
            "projectionMatrix", "mat4", "projectionMatrixInverse", "mat4", "textureMatrix", "mat4", "normalMatrix", "mat3",
            "chunkOffset", "vec3", "alphaTestRef", "float");

    /** Compatibility state Iroh does not emulate: a program using one fails, naming it. */
    static final Set<String> UNSUPPORTED_BUILTINS = Set.of("gl_LightSource", "gl_LightModel", "gl_FrontLightModelProduct",
            "gl_BackLightModelProduct", "gl_FrontLightProduct", "gl_BackLightProduct", "gl_FrontMaterial", "gl_BackMaterial",
            "gl_Point", "gl_ClipPlane", "gl_TextureEnvColor", "gl_EyePlaneS", "gl_EyePlaneT", "gl_EyePlaneR", "gl_EyePlaneQ",
            "gl_ObjectPlaneS", "gl_ObjectPlaneT", "gl_ObjectPlaneR", "gl_ObjectPlaneQ", "gl_DepthRange", "gl_MaxLights");

    /** legacy texture functions with the same signature as a 4.60 one */
    static final Map<String, String> FUNC_RENAMES = new HashMap<>();

    static {
        String[][] f = {{"texture2D", "texture"}, {"texture2DLod", "textureLod"}, {"texture2DProj", "textureProj"},
                {"texture2DProjLod", "textureProjLod"}, {"texture2DGrad", "textureGrad"}, {"texture2DGradARB", "textureGrad"},
                {"texture2DLodEXT", "textureLod"}, {"texture2DGradEXT", "textureGrad"}, {"texture2DRect", "texture"},
                {"texture1D", "texture"}, {"texture1DLod", "textureLod"}, {"texture3D", "texture"}, {"texture3DLod", "textureLod"},
                {"texture3DProj", "textureProj"}, {"textureCube", "texture"}, {"textureCubeLod", "textureLod"},
                {"texture2DArray", "texture"}, {"texture2DArrayLod", "textureLod"},
                {"texelFetch2D", "texelFetch"}, {"texelFetch3D", "texelFetch"}, {"texelFetch1D", "texelFetch"},
                {"textureSize2D", "textureSize"}, {"textureSize3D", "textureSize"},
                {"shadow2D", "iroh_shadow2D"}, {"shadow2DLod", "iroh_shadow2DLod"}, {"shadow2DProj", "iroh_shadow2DProj"},
                {"shadow2DProjLod", "iroh_shadow2DProjLod"}, {"shadow2DGrad", "iroh_shadow2DGrad"},
                {"ftransform", "iroh_ftransform"}};
        for (String[] e : f)
            FUNC_RENAMES.put(e[0], e[1]);
    }

    /** Keywords of 4.60 that older GLSL let packs use as names; renamed when used as a name. */
    static final Set<String> NEW_KEYWORDS = Set.of("sample", "patch", "subroutine", "precise", "buffer", "shared",
            "coherent", "volatile", "restrict", "readonly", "writeonly", "resource", "atomic_uint", "noperspective",
            "smooth", "flat", "centroid", "invariant", "layout", "demote", "texture", "sampler", "filter", "common",
            "partition", "active", "superp", "input", "output");

    static final Set<String> STORAGE = Set.of("uniform", "in", "out", "inout", "varying", "attribute", "buffer", "shared", "const");
    static final Set<String> INTERP = Set.of("flat", "smooth", "noperspective", "centroid", "sample", "patch", "invariant", "precise");
    static final Set<String> MEMORY = Set.of("coherent", "volatile", "restrict", "readonly", "writeonly");
    static final Set<String> PRECISION = Set.of("highp", "mediump", "lowp");

    /** names a {@code const} directive may carry (stored as metadata, see {@link #isDirectiveName}). */
    static final Pattern BUFFER_DIRECTIVE = Pattern.compile(
            "(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4]|shadowcolor\\d*|shadowtex[01]|depthtex\\d)(Format|Clear|ClearColor|MipmapEnabled|Mipmap|Nearest)");
    static final Set<String> GLOBAL_DIRECTIVES = Set.of("shadowMapResolution", "shadowMapFov", "shadowDistance",
            "shadowDistanceRenderMul", "shadowIntervalSize", "shadowHardwareFiltering", "shadowHardwareFiltering0",
            "shadowHardwareFiltering1", "generateShadowMipmap", "generateShadowColorMipmap", "sunPathRotation",
            "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife", "ambientOcclusionLevel",
            "superSamplingLevel", "noiseTextureResolution", "entityShadowDistanceMul", "shadowNearPlane", "shadowFarPlane",
            "workGroups", "workGroupsRender", "voxelDistance", "shadowColor0Format", "shadowColor1Format");

    static boolean isDirectiveName(String n) {
        return GLOBAL_DIRECTIVES.contains(n) || BUFFER_DIRECTIVE.matcher(n).matches();
    }

    /** texture format names (they are not GLSL identifiers: a live directive using one must not reach shaderc) */
    static final Set<String> FORMAT_NAMES = Set.of("R8", "RG8", "RGB8", "RGBA8", "R8_SNORM", "RG8_SNORM", "RGB8_SNORM",
            "RGBA8_SNORM", "R16", "RG16", "RGB16", "RGBA16", "R16_SNORM", "RG16_SNORM", "RGB16_SNORM", "RGBA16_SNORM",
            "R16F", "RG16F", "RGB16F", "RGBA16F", "R32F", "RG32F", "RGB32F", "RGBA32F", "R32I", "RG32I", "RGB32I",
            "RGBA32I", "R32UI", "RG32UI", "RGB32UI", "RGBA32UI", "R8I", "RG8I", "RGB8I", "RGBA8I", "R8UI", "RG8UI",
            "RGB8UI", "RGBA8UI", "R16I", "RG16I", "RGB16I", "RGBA16I", "R16UI", "RG16UI", "RGB16UI", "RGBA16UI",
            "R3_G3_B2", "RGB5_A1", "RGB10_A2", "R11F_G11F_B10F", "RGB9_E5", "RGB10_A2UI");

    // =========================================================================================== stage unit

    /** A declared global of interest. */
    public static final class Decl {
        public String kind;        // uniform, sampler, image, in, out, shared, const, global, ssbo, ubo
        public String type;
        public String name;
        public String array = "";  // "[4]" etc. (type array and name array merged)
        public String init;        // initializer text or null
        public String layout;      // layout args or null
        public List<String> quals = new ArrayList<>();
        public Integer location;
        public Integer binding;
        int stmt;                  // statement index
        int line;
    }

    record Stmt(int start, int end, int firstTok, int lastTok, int line) {}

    public static final class Unit {
        public final Stage stage;
        final String text;            // comment-stripped
        final List<Tok> toks;
        final List<Stmt> stmts = new ArrayList<>();
        final Map<Integer, String> replace = new HashMap<>();   // stmt index -> replacement ("" drop)
        final List<String> extensions = new ArrayList<>();
        final List<int[]> dropPreproc = new ArrayList<>();      // token index of dropped #version/#extension/#line
        public final List<Decl> decls = new ArrayList<>();
        final Set<String> functions = new HashSet<>();
        final Map<String, List<Std140.Member>> structs = new LinkedHashMap<>();
        final Set<String> idents = new HashSet<>();
        final Map<String, Integer> identCount = new HashMap<>();
        public int version = 110;
        public String profile = "";
        public final Map<String, String> constDirectives = new LinkedHashMap<>();
        public final List<String> legacyFormatDirectives = new ArrayList<>();
        public List<Integer> drawBuffers;      // null = no directive
        public String drawBuffersSource = "none";
        public int[] localSize;
        public final List<String> notes = new ArrayList<>();
        int fragDataMax = -1;
        boolean fragDataDynamic;
        int texCoordMax = -1;
        boolean texCoordDynamic;
        boolean hasMain;
        final Set<String> keywordRenames = new HashSet<>();

        Unit(Stage stage, String text, List<Tok> toks) {
            this.stage = stage;
            this.text = text;
            this.toks = toks;
        }

        boolean uses(String id) {
            return idents.contains(id);
        }

        public Set<String> identifiers() {
            return idents;
        }
    }

    /** Phase A: parse one preprocessed stage (comments still present). */
    public static Unit analyze(Stage stage, String preprocessed) {
        // directives in comments, only from live text (the preprocessor dropped dead lines)
        List<String> commentDirectiveSites = new ArrayList<>();
        Unit probe = null;
        String stripped = GlslLex.stripComments(preprocessed);
        Unit u = new Unit(stage, stripped, GlslLex.lex(stripped));
        scanComments(u, preprocessed);
        parseGlobals(u);
        for (Tok t : u.toks)
            if (t.kind() == T.IDENT) {
                u.idents.add(t.text());
                u.identCount.merge(t.text(), 1, Integer::sum);
            }
        scanBuiltinIndices(u);
        Set<String> declared = new HashSet<>();
        for (Decl d : u.decls)
            declared.add(d.name);
        for (Map.Entry<String, String> e : IMPLICIT_UNIFORMS.entrySet())
            if (u.idents.contains(e.getKey()) && !declared.contains(e.getKey()) && !u.functions.contains(e.getKey()))
                u.decls.add(implicit("uniform", e.getValue(), e.getKey()));
        if (stage == Stage.VERTEX)
            for (Map.Entry<String, String> e : IMPLICIT_INPUTS.entrySet())
                if (u.idents.contains(e.getKey()) && !declared.contains(e.getKey()))
                    u.decls.add(implicit("in", e.getValue(), e.getKey()));
        return u;
    }

    private static Decl implicit(String kind, String type, String name) {
        Decl d = new Decl();
        d.kind = kind;
        d.type = type;
        d.name = name;
        d.stmt = -1;
        d.quals = new ArrayList<>();
        return d;
    }

    private static final Pattern DRAWBUFFERS = Pattern.compile("^/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9A-Fa-f]*)");
    private static final Pattern RENDERTARGETS = Pattern.compile("^/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]*)");
    private static final Pattern COMMENT_CONST = Pattern.compile("const\\s+(\\w+)\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern LEGACY_FORMAT = Pattern.compile("(GCOLOR|GDEPTH|GNORMAL|COMPOSITE|GAUX[1-4]|COLORTEX\\d+|SHADOWCOLOR\\d?)FORMAT\\s*:\\s*(\\w+)");

    private static void scanComments(Unit u, String s) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && (s.charAt(i + 1) == '/' || s.charAt(i + 1) == '*')) {
                boolean block = s.charAt(i + 1) == '*';
                int e = block ? s.indexOf("*/", i + 2) : s.indexOf('\n', i);
                e = e < 0 ? n : (block ? e + 2 : e);
                String com = s.substring(i, e);
                if (block) {
                    Matcher d = DRAWBUFFERS.matcher(com);
                    Matcher r = RENDERTARGETS.matcher(com);
                    if (d.find()) {
                        List<Integer> l = new ArrayList<>();
                        for (char ch : d.group(1).toCharArray())
                            l.add(Character.digit(ch, 16));
                        u.drawBuffers = l;
                        u.drawBuffersSource = "DRAWBUFFERS";
                    } else if (r.find()) {
                        List<Integer> l = new ArrayList<>();
                        for (String p : r.group(1).split(","))
                            if (!p.isBlank())
                                l.add(Integer.parseInt(p.trim()));
                        u.drawBuffers = l;
                        u.drawBuffersSource = "RENDERTARGETS";
                    }
                }
                Matcher cc = COMMENT_CONST.matcher(com);
                while (cc.find())
                    if (isDirectiveName(cc.group(2)))
                        u.constDirectives.put(cc.group(2), cc.group(3).trim());
                Matcher lf = LEGACY_FORMAT.matcher(com);
                while (lf.find())
                    u.legacyFormatDirectives.add(lf.group(1) + "=" + lf.group(2));
                i = e;
            } else if (c == '"') {
                int e = s.indexOf('"', i + 1);
                i = e < 0 ? n : e + 1;
            } else {
                i++;
            }
        }
    }

    private static void scanBuiltinIndices(Unit u) {
        List<Tok> t = u.toks;
        for (int i = 0; i + 1 < t.size(); i++) {
            Tok a = t.get(i);
            if (a.kind() != T.IDENT)
                continue;
            boolean fd = a.is("gl_FragData");
            boolean tc = a.is("gl_TexCoord");
            if (!fd && !tc)
                continue;
            int idx = -1;
            boolean dyn = true;
            if (t.get(i + 1).is("[") && i + 3 < t.size() && t.get(i + 2).kind() == T.NUMBER && t.get(i + 3).is("]")) {
                try {
                    idx = Integer.parseInt(t.get(i + 2).text().replaceAll("[uU]$", ""));
                    dyn = false;
                } catch (NumberFormatException ignored) {
                }
            }
            if (fd) {
                if (dyn)
                    u.fragDataDynamic = true;
                else
                    u.fragDataMax = Math.max(u.fragDataMax, idx);
            } else {
                if (dyn)
                    u.texCoordDynamic = true;
                else
                    u.texCoordMax = Math.max(u.texCoordMax, idx);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ global parse

    private static void parseGlobals(Unit u) {
        List<Tok> toks = u.toks;
        int i = 0;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            if (t.kind() == T.PREPROC) {
                String body = t.text().substring(1).trim();
                if (body.startsWith("version")) {
                    String[] p = body.split("\\s+");
                    try {
                        u.version = Integer.parseInt(p[1]);
                    } catch (RuntimeException ignored) {
                    }
                    u.profile = p.length > 2 ? p[2] : "";
                    u.dropPreproc.add(new int[]{i});
                } else if (body.startsWith("extension")) {
                    u.extensions.add(body);
                    u.dropPreproc.add(new int[]{i});
                } else if (body.startsWith("line")) {
                    u.dropPreproc.add(new int[]{i});
                }
                i++;
                continue;
            }
            int end = statementEnd(u, i);
            int idx = u.stmts.size();
            u.stmts.add(new Stmt(t.start(), toks.get(end).end(), i, end, t.line()));
            analyzeStatement(u, idx, i, end);
            i = end + 1;
        }
    }

    /** index of the last token of the global statement starting at {@code from} */
    private static int statementEnd(Unit u, int from) {
        List<Tok> toks = u.toks;
        int depth = 0;
        for (int i = from; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.kind() == T.PREPROC)
                continue;
            switch (t.text()) {
                case "(", "[" -> depth++;
                case ")", "]" -> depth--;
                case "{" -> {
                    if (depth == 0 && i > from && toks.get(i - 1).is(")")) {
                        // function body: ends at the matching brace
                        int d = 0;
                        for (int j = i; j < toks.size(); j++) {
                            if (toks.get(j).is("{"))
                                d++;
                            else if (toks.get(j).is("}") && --d == 0)
                                return j;
                        }
                        throw new TranslateException(u.stage, t.line(), "unbalanced braces in function body");
                    }
                    depth++;
                }
                case "}" -> depth--;
                case ";" -> {
                    if (depth == 0)
                        return i;
                }
                default -> {
                }
            }
        }
        throw new TranslateException(u.stage, toks.get(from).line(), "unterminated global statement starting with '" + toks.get(from).text() + "'");
    }

    private static void analyzeStatement(Unit u, int idx, int a, int b) {
        List<Tok> toks = u.toks;
        Tok first = toks.get(a);
        if (first.is("precision")) {
            u.replace.put(idx, "");
            return;
        }
        if (first.is(";"))
            return;
        if (first.is("struct")) {
            parseStruct(u, a, b);
            return;
        }
        // skip qualifiers to find what kind of statement this is
        int i = a;
        String layout = null;
        List<String> quals = new ArrayList<>();
        String storage = null;
        while (i <= b) {
            Tok t = toks.get(i);
            if (t.is("layout") && i + 1 <= b && toks.get(i + 1).is("(")) {
                int close = matching(toks, i + 1, b);
                String args = u.text.substring(toks.get(i + 1).end(), toks.get(close).start()).trim();
                layout = layout == null ? args : layout + ", " + args;
                i = close + 1;
            } else if (STORAGE.contains(t.text()) && !(t.is("buffer") && i + 1 <= b && !isIdent(toks.get(i + 1)))) {
                if (storage != null && !(storage.equals("const") || t.is("const")))
                    throw new TranslateException(u.stage, t.line(), "two storage qualifiers '" + storage + "' and '" + t.text() + "'");
                if (t.is("const") && storage != null)
                    quals.add("const");
                else if (storage != null && storage.equals("const")) {
                    quals.add("const");
                    storage = t.text();
                } else
                    storage = t.text();
                i++;
            } else if (INTERP.contains(t.text()) || MEMORY.contains(t.text()) || PRECISION.contains(t.text())) {
                if (!PRECISION.contains(t.text()))
                    quals.add(t.text());
                i++;
            } else {
                break;
            }
        }
        if (i > b || toks.get(i).is(";")) {
            // qualifier-only statement: layout(local_size_x = 8) in;  invariant gl_Position;
            if (layout != null && "in".equals(storage) && u.stage == Stage.COMPUTE) {
                u.localSize = new int[]{1, 1, 1};
                for (String part : layout.split(",")) {
                    String[] kv = part.split("=");
                    if (kv.length != 2)
                        continue;
                    String k = kv[0].trim();
                    int v;
                    try {
                        v = Integer.parseInt(kv[1].trim());
                    } catch (NumberFormatException e) {
                        throw new TranslateException(u.stage, first.line(), "local size '" + part.trim() + "' is not a literal");
                    }
                    switch (k) {
                        case "local_size_x" -> u.localSize[0] = v;
                        case "local_size_y" -> u.localSize[1] = v;
                        case "local_size_z" -> u.localSize[2] = v;
                        default -> {
                        }
                    }
                }
            }
            return;
        }
        Tok typeTok = toks.get(i);
        if (!isIdent(typeTok))
            throw new TranslateException(u.stage, typeTok.line(), "unexpected '" + typeTok.text() + "' at global scope");
        // interface block: qualifiers Name { ... } [inst];
        if (i + 1 <= b && toks.get(i + 1).is("{")) {
            if (storage == null)
                throw new TranslateException(u.stage, typeTok.line(), "unexpected block '" + typeTok.text() + "' at global scope");
            parseBlock(u, idx, a, b, i, storage, layout, quals);
            return;
        }
        // invariant gl_Position;  (name only, no type)
        if (i + 1 <= b && toks.get(i + 1).is(";") && quals.contains("invariant")) {
            u.replace.put(idx, "");
            return;
        }
        int j = i + 1;
        String typeArray = "";
        if (j <= b && toks.get(j).is("[")) {
            int close = matching(toks, j, b);
            typeArray = compact(u.text.substring(toks.get(j).start(), toks.get(close).end()));
            j = close + 1;
        }
        // function prototype or definition: type name ( ...
        if (j + 1 <= b && isIdent(toks.get(j)) && toks.get(j + 1).is("(")) {
            u.functions.add(toks.get(j).text());
            if (toks.get(j).is("main") && toks.get(b).is("}"))
                u.hasMain = true;
            return;
        }
        // declarators
        int declStart = j;
        boolean firstDecl = true;
        while (declStart <= b) {
            if (toks.get(declStart).is(";"))
                break;
            int stop = declStart;
            int depth = 0;
            while (stop <= b) {
                String s = toks.get(stop).text();
                if (s.equals("(") || s.equals("[") || s.equals("{"))
                    depth++;
                else if (s.equals(")") || s.equals("]") || s.equals("}"))
                    depth--;
                else if ((s.equals(",") || s.equals(";")) && depth == 0)
                    break;
                stop++;
            }
            Tok nameTok = toks.get(declStart);
            if (!isIdent(nameTok))
                throw new TranslateException(u.stage, nameTok.line(), "expected a name after type '" + typeTok.text() + "', got '" + nameTok.text() + "'");
            Decl d = new Decl();
            d.type = typeTok.text();
            d.name = nameTok.text();
            d.layout = layout;
            d.quals = quals;
            d.stmt = idx;
            d.line = nameTok.line();
            String arr = typeArray;
            int k = declStart + 1;
            while (k < stop && toks.get(k).is("[")) { // arrays of arrays: x[4][8]
                int close = matching(toks, k, stop);
                arr = arr + compact(u.text.substring(toks.get(k).start(), toks.get(close).end()));
                k = close + 1;
            }
            d.array = arr;
            if (k < stop && toks.get(k).is("=")) {
                d.init = u.text.substring(toks.get(k + 1).start(), toks.get(stop - 1).end());
                k = stop;
            }
            if (k != stop)
                throw new TranslateException(u.stage, toks.get(k).line(), "unexpected '" + toks.get(k).text() + "' in declaration of " + d.name);
            d.kind = classify(u, storage, d);
            if (layout != null) {
                Matcher m = Pattern.compile("location\\s*=\\s*(\\d+)").matcher(layout);
                if (m.find())
                    d.location = Integer.parseInt(m.group(1));
                Matcher bm = Pattern.compile("binding\\s*=\\s*(\\d+)").matcher(layout);
                if (bm.find())
                    d.binding = Integer.parseInt(bm.group(1));
            }
            u.decls.add(d);
            firstDecl = false;
            declStart = stop + 1;
        }
    }

    private static String classify(Unit u, String storage, Decl d) {
        boolean opaque = isSampler(d.type) || isImage(d.type);
        if (storage == null)
            return "global";
        return switch (storage) {
            case "uniform" -> isSampler(d.type) ? "sampler" : isImage(d.type) ? "image" : "uniform";
            case "in", "attribute" -> "in";
            case "out" -> "out";
            case "varying" -> u.stage == Stage.VERTEX ? "out" : "in";
            case "const" -> "const";
            case "shared" -> "shared";
            case "inout" -> throw new TranslateException(u.stage, d.line, "'inout' at global scope (" + d.name + ")");
            case "buffer" -> throw new TranslateException(u.stage, d.line, "'buffer' variable outside a block (" + d.name + ")");
            default -> {
                if (opaque)
                    yield "sampler";
                throw new TranslateException(u.stage, d.line, "unknown storage " + storage);
            }
        };
    }

    private static void parseStruct(Unit u, int a, int b) {
        List<Tok> toks = u.toks;
        if (a + 2 > b || !isIdent(toks.get(a + 1)) || !toks.get(a + 2).is("{"))
            return;
        String name = toks.get(a + 1).text();
        int close = matching(toks, a + 2, b + 1);
        List<Std140.Member> members = new ArrayList<>();
        int i = a + 3;
        while (i < close) {
            int end = i;
            while (end < close && !toks.get(end).is(";"))
                end++;
            // [precision] type name[, name][arr];
            int k = i;
            while (k < end && PRECISION.contains(toks.get(k).text()))
                k++;
            if (k < end) {
                String type = toks.get(k).text();
                k++;
                while (k < end) {
                    if (isIdent(toks.get(k))) {
                        int arr = 0;
                        String nm = toks.get(k).text();
                        if (k + 3 < end + 1 && k + 1 < end && toks.get(k + 1).is("[") && toks.get(k + 2).kind() == T.NUMBER) {
                            try {
                                arr = Integer.parseInt(toks.get(k + 2).text());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        members.add(new Std140.Member(type, nm, arr));
                    }
                    k++;
                }
            }
            i = end + 1;
        }
        u.structs.put(name, members);
    }

    private static void parseBlock(Unit u, int idx, int a, int b, int nameIdx, String storage, String layout, List<String> quals) {
        List<Tok> toks = u.toks;
        Decl d = new Decl();
        d.type = toks.get(nameIdx).text();
        d.layout = layout;
        d.quals = quals;
        d.stmt = idx;
        d.line = toks.get(nameIdx).line();
        int close = matching(toks, nameIdx + 1, b + 1);
        d.init = u.text.substring(toks.get(nameIdx + 1).start(), toks.get(close).end()); // member list incl. braces
        String inst = null;
        int k = close + 1;
        if (k <= b && isIdent(toks.get(k))) {
            inst = toks.get(k).text();
            k++;
            if (k <= b && toks.get(k).is("[")) {
                int c2 = matching(toks, k, b);
                d.array = compact(u.text.substring(toks.get(k).start(), toks.get(c2).end()));
            }
        }
        d.name = inst == null ? "" : inst;
        if (layout != null) {
            Matcher bm = Pattern.compile("binding\\s*=\\s*(\\d+)").matcher(layout);
            if (bm.find())
                d.binding = Integer.parseInt(bm.group(1));
        }
        switch (storage) {
            case "buffer" -> d.kind = "ssbo";
            case "uniform" -> d.kind = "ubo";
            case "in", "out" -> {
                if (d.type.equals("gl_PerVertex")) {
                    u.replace.put(idx, "");
                    return;
                }
                throw new TranslateException(u.stage, d.line, "interface block '" + storage + " " + d.type + " { }' between stages is not supported");
            }
            default -> throw new TranslateException(u.stage, d.line, "block with storage '" + storage + "'");
        }
        u.decls.add(d);
    }

    static boolean isIdent(Tok t) {
        return t.kind() == T.IDENT;
    }

    static boolean isSampler(String t) {
        return t.startsWith("sampler") || t.startsWith("isampler") || t.startsWith("usampler");
    }

    static boolean isImage(String t) {
        return t.startsWith("image") || t.startsWith("iimage") || t.startsWith("uimage");
    }

    static boolean isIntegerType(String t) {
        return t.matches("u?int|[iu]vec[234]|bool|bvec[234]");
    }

    static int matching(List<Tok> toks, int open, int limit) {
        String o = toks.get(open).text();
        String c = switch (o) {
            case "(" -> ")";
            case "[" -> "]";
            case "{" -> "}";
            default -> throw new IllegalArgumentException(o);
        };
        int depth = 0;
        for (int i = open; i <= Math.min(limit, toks.size() - 1); i++) {
            String s = toks.get(i).text();
            if (s.equals(o))
                depth++;
            else if (s.equals(c) && --depth == 0)
                return i;
        }
        throw new TranslateException(null, toks.get(open).line(), "unbalanced '" + o + "'");
    }

    static String compact(String s) {
        return s.replaceAll("\\s+", "");
    }

    static int arrayCount(String arr) {
        if (arr == null || arr.isEmpty())
            return 0;
        int total = 1;
        Matcher m = Pattern.compile("\\[([^\\]]*)\\]").matcher(arr);
        while (m.find()) {
            String in = m.group(1).trim();
            if (in.isEmpty())
                return -1;
            try {
                total *= Integer.parseInt(in.replaceAll("[uU]$", ""));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return total;
    }

    // =========================================================================================== program level

    /** Pack-wide binding table for samplers and images. */
    public interface PackBindings {
        int binding(String name);
    }

    /** {@code initializer}: the GLSL default the pack wrote ({@code uniform float x = 1.0;}), null if none. */
    public record UniformMember(String name, String type, int array, int offset, int size, String source, String initializer) {}

    public record Binding(String name, String type, int set, int binding, String format, List<String> access,
                          Set<Stage> stages) {}

    public record Varying(String name, String type, int location, int slots, String from) {}

    public static final class Result {
        public final Map<Stage, String> glsl = new LinkedHashMap<>();
        public final Map<Stage, Integer> headerLines = new LinkedHashMap<>();
        public final List<UniformMember> uniforms = new ArrayList<>();
        public int blockSize;
        public final List<Binding> samplers = new ArrayList<>();
        public final List<Binding> images = new ArrayList<>();
        public final List<Binding> buffers = new ArrayList<>();
        public final List<Map<String, Object>> vertexInputs = new ArrayList<>();
        public final List<Varying> varyings = new ArrayList<>();
        public final List<Map<String, Object>> fragmentOutputs = new ArrayList<>();
        public List<Integer> drawBuffers;
        public String drawBuffersSource;
        public int[] localSize;
        public final Map<String, String> constDirectives = new LinkedHashMap<>();
        public final List<String> legacyFormatDirectives = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public final Set<String> builtinsUsed = new TreeSet<>();
        /** sampler / image / uniform names some stage references beyond its declaration (dead declarations excluded) */
        public final Set<String> referenced = new TreeSet<>();
        public final Map<Stage, Integer> version = new LinkedHashMap<>();
    }

    /** Phase B: translates the program's stages together. */
    public static Result translate(Map<Stage, Unit> units, PackBindings bindings) {
        return translate(units, bindings, Map.of());
    }

    /** GLSL image format layout qualifiers (GL_ARB_shader_image_load_store / GLSL 4.60 4.4.6.2). */
    static final Set<String> IMAGE_FORMATS = Set.of("rgba32f", "rgba16f", "rg32f", "rg16f", "r11f_g11f_b10f", "r32f", "r16f",
            "rgba16", "rgb10_a2", "rgba8", "rg16", "rg8", "r16", "r8", "rgba16_snorm", "rgba8_snorm", "rg16_snorm",
            "rg8_snorm", "r16_snorm", "r8_snorm", "rgba32i", "rgba16i", "rgba8i", "rg32i", "rg16i", "rg8i", "r32i", "r16i",
            "r8i", "rgba32ui", "rgba16ui", "rgb10_a2ui", "rgba8ui", "rg32ui", "rg16ui", "rg8ui", "r32ui", "r16ui", "r8ui");

    /**
     * @param imageFormats image name -> internal format from the pack's {@code image.<name>} directives: an image
     *                     the GLSL declares without a format qualifier gets that one (GL takes the format from the
     *                     bound image; SPIR-V needs it in the type unless the device has the WithoutFormat features)
     */
    public static Result translate(Map<Stage, Unit> units, PackBindings bindings, Map<String, String> imageFormats) {
        Result r = new Result();
        for (Stage s : List.of(Stage.GEOMETRY, Stage.TESS_CONTROL, Stage.TESS_EVAL))
            if (units.containsKey(s))
                throw new TranslateException(s, 0, s.key + " stage present: geometry and tessellation stages are not translated (P3-1 detects them; Navidson uses none)");
        Unit vs = units.get(Stage.VERTEX);
        Unit fs = units.get(Stage.FRAGMENT);
        Unit cs = units.get(Stage.COMPUTE);

        for (Unit u : units.values()) {
            r.version.put(u.stage, u.version);
            for (String id : u.idents)
                if (UNSUPPORTED_BUILTINS.contains(id))
                    throw new TranslateException(u.stage, firstLine(u, id), "compatibility built-in " + id + " (fixed-function lighting/clip state) is not emulated");
            r.constDirectives.putAll(u.constDirectives);
            r.legacyFormatDirectives.addAll(u.legacyFormatDirectives);
            r.notes.addAll(u.notes);
        }

        // ---- uniform block: union of both stages, sorted by name
        Map<String, List<Std140.Member>> structs = new LinkedHashMap<>();
        structs.put("IrohFogParameters", List.of(new Std140.Member("vec4", "color", 0), new Std140.Member("float", "density", 0),
                new Std140.Member("float", "start", 0), new Std140.Member("float", "end", 0), new Std140.Member("float", "scale", 0)));
        TreeMap<String, String[]> block = new TreeMap<>(); // name -> [type, arrayText, source]
        Map<String, Set<String>> privateUniforms = new HashMap<>();
        for (Unit u : units.values()) {
            for (Decl d : u.decls) {
                if (!d.kind.equals("uniform"))
                    continue;
                boolean blockable = arrayCount(d.array) >= 0 && (Std140.isScalarOrVector(d.type) || d.type.matches("mat[234](x[234])?"));
                if (!blockable) {
                    privateUniforms.computeIfAbsent(u.stage.key, k -> new TreeSet<>()).add(d.name);
                    r.notes.add(u.stage.key + ": uniform " + d.type + " " + d.name + d.array + " cannot live in the block: private "
                            + (d.init == null ? "zero" : "constant"));
                    continue;
                }
                String[] prev = block.get(d.name);
                if (prev != null && (!prev[0].equals(d.type) || !prev[1].equals(d.array)))
                    throw new TranslateException(u.stage, d.line, "uniform " + d.name + " declared as " + prev[0] + prev[1] + " and " + d.type + d.array);
                if (prev != null && prev[3] != null && d.init != null && !prev[3].equals(d.init.trim()))
                    r.notes.add("uniform " + d.name + " has two initializers: " + prev[3] + " and " + d.init.trim());
                // GL semantics: an initializer is the value until the engine sets one (Navidson: cloudHeight = 192.0,
                // an Iris uniform). It lives in the block like any other; the default goes to the reflection.
                block.put(d.name, new String[]{d.type, d.array, "pack", d.init == null ? (prev == null ? null : prev[3]) : d.init.trim()});
            }
            for (Map.Entry<String, String[]> b : UNIFORM_BUILTINS.entrySet()) {
                if (u.uses(b.getKey())) {
                    block.put(b.getValue()[0], new String[]{b.getValue()[1], b.getValue()[2].equals("0") ? "" : "[" + b.getValue()[2] + "]", "builtin:" + b.getKey(), null});
                    r.builtinsUsed.add(b.getKey());
                }
            }
            if (u.uses("ftransform") && u.stage == Stage.VERTEX) {
                block.put("iroh_ModelViewProjectionMatrix", new String[]{"mat4", "", "builtin:gl_ModelViewProjectionMatrix", null});
                r.builtinsUsed.add("ftransform");
            }
        }
        int off = 0;
        for (Map.Entry<String, String[]> e : block.entrySet()) {
            String type = e.getValue()[0];
            int arr = arrayCount(e.getValue()[1]);
            Std140.Layout l = Std140.of(type, arr, structs);
            off = Std140.roundUp(off, l.align());
            r.uniforms.add(new UniformMember(e.getKey(), type, arr, off, l.size(), e.getValue()[2],
                    e.getValue().length > 3 ? e.getValue()[3] : null));
            off += l.size();
        }
        r.blockSize = Std140.roundUp(off, 16);

        // ---- opaque bindings (pack-wide table)
        Map<String, Binding> samplerMap = new TreeMap<>();
        Map<String, Binding> imageMap = new TreeMap<>();
        for (Unit u : units.values()) {
            for (Decl d : u.decls) {
                if (d.kind.equals("sampler") || d.kind.equals("image")) {
                    Map<String, Binding> m = d.kind.equals("sampler") ? samplerMap : imageMap;
                    Binding prev = m.get(d.name);
                    String fullType = d.type + d.array;
                    if (prev != null && !prev.type().equals(fullType))
                        throw new TranslateException(u.stage, d.line, d.kind + " " + d.name + " declared as " + prev.type() + " and " + fullType);
                    Set<Stage> st = prev == null ? new TreeSet<>() : prev.stages();
                    st.add(u.stage);
                    String format = null;
                    List<String> access = new ArrayList<>();
                    if (d.kind.equals("image")) {
                        if (d.layout != null)
                            for (String p : d.layout.split(","))
                                if (!p.contains("=") && !p.isBlank())
                                    format = p.trim();
                        for (String q : d.quals)
                            if (MEMORY.contains(q))
                                access.add(q);
                        if (prev != null && prev.format() != null && format == null)
                            format = prev.format();
                        if (format == null && imageFormats.containsKey(d.name)
                                && IMAGE_FORMATS.contains(imageFormats.get(d.name).toLowerCase(java.util.Locale.ROOT)))
                            format = imageFormats.get(d.name).toLowerCase(java.util.Locale.ROOT);
                    }
                    m.put(d.name, new Binding(d.name, fullType, 0, bindings.binding(d.name), format, access, st));
                }
            }
        }
        r.samplers.addAll(samplerMap.values());
        r.images.addAll(imageMap.values());
        for (Binding b : r.images)
            if (b.format() == null)
                r.notes.add("image " + b.name() + " has no format qualifier: needs shaderStorageImage"
                        + (b.access().contains("writeonly") ? "WriteWithoutFormat" : "ReadWithoutFormat (and Write)"));

        // ---- buffers: SSBO set 1 at the pack's binding, UBO set 2
        Map<String, Binding> bufMap = new TreeMap<>();
        int nextSsbo = 100;
        for (Unit u : units.values())
            for (Decl d : u.decls)
                if (d.kind.equals("ssbo") || d.kind.equals("ubo")) {
                    int set = d.kind.equals("ssbo") ? 1 : 2;
                    if (d.binding == null) {
                        d.binding = nextSsbo++;
                        r.notes.add(u.stage.key + ": " + d.kind + " " + d.type + " has no binding: given " + d.binding);
                    }
                    String key = d.kind + ":" + d.type;
                    Binding prev = bufMap.get(key);
                    Set<Stage> st = prev == null ? new TreeSet<>() : prev.stages();
                    st.add(u.stage);
                    bufMap.put(key, new Binding(d.type, d.kind, set, d.binding, null, new ArrayList<>(d.quals), st));
                }
        r.buffers.addAll(bufMap.values());

        // ---- varyings
        Map<String, Decl> vOut = new TreeMap<>();
        Map<String, Decl> fIn = new TreeMap<>();
        if (vs != null)
            for (Decl d : vs.decls)
                if (d.kind.equals("out"))
                    vOut.put(d.name, d);
        if (fs != null)
            for (Decl d : fs.decls)
                if (d.kind.equals("in"))
                    fIn.put(d.name, d);
        // compatibility varyings
        int texCoords = 0;
        for (Unit u : new Unit[]{vs, fs})
            if (u != null) {
                if (u.uses("gl_TexCoord"))
                    texCoords = Math.max(texCoords, u.texCoordDynamic ? 8 : u.texCoordMax + 1);
            }
        Map<String, String[]> compatVaryings = new TreeMap<>(); // name -> [type, array]
        if (vs != null && (vs.uses("gl_FrontColor") || (fs != null && fs.uses("gl_Color"))))
            compatVaryings.put("iroh_FrontColor", new String[]{"vec4", ""});
        if ((vs != null && vs.uses("gl_FrontSecondaryColor")) || (fs != null && fs.uses("gl_SecondaryColor")))
            compatVaryings.put("iroh_FrontSecondaryColor", new String[]{"vec4", ""});
        if (texCoords > 0)
            compatVaryings.put("iroh_TexCoord", new String[]{"vec4", "[" + texCoords + "]"});
        if ((vs != null && vs.uses("gl_FogFragCoord")) || (fs != null && fs.uses("gl_FogFragCoord")))
            compatVaryings.put("iroh_FogFragCoord", new String[]{"float", ""});
        if (vs != null && vs.uses("gl_BackColor"))
            r.notes.add("gl_BackColor written: two-sided colour is not emulated (front colour used)");

        TreeMap<String, String[]> all = new TreeMap<>(); // name -> type, array, from
        for (Decl d : vOut.values())
            all.put(d.name, new String[]{d.type, d.array, "pack"});
        for (Decl d : fIn.values()) {
            String[] prev = all.get(d.name);
            if (prev != null && (!prev[0].equals(d.type) || !prev[1].equals(d.array)))
                throw new TranslateException(Stage.FRAGMENT, d.line, "varying " + d.name + " is " + prev[0] + prev[1] + " in the vertex stage and " + d.type + d.array + " here");
            if (prev == null)
                all.put(d.name, new String[]{d.type, d.array, vs == null ? "pack" : "owed-by-vertex"});
        }
        compatVaryings.forEach((n, t) -> all.put(n, new String[]{t[0], t[1], "compat"}));
        // struct varyings (Photon's fog parameters): one varying per member, the struct rebuilt around main
        // (Vitrail's approach): vertex copies members out after the pack's main, fragment fills the struct first
        Map<String, List<Std140.Member>> structVaryings = new TreeMap<>();
        // copy the pairs: TreeMap.remove moves a successor's key/value into the removed entry object
        List<Map.Entry<String, String[]>> pairs = new ArrayList<>();
        for (Map.Entry<String, String[]> x : all.entrySet())
            pairs.add(Map.entry(x.getKey(), x.getValue()));
        for (Map.Entry<String, String[]> e : pairs) {
            String type = e.getValue()[0];
            if (Std140.isScalarOrVector(type) || type.matches("d?mat[234](x[234])?") || type.matches("dvec[234]"))
                continue;
            List<Std140.Member> members = null;
            for (Unit u : units.values())
                if (u.structs.containsKey(type))
                    members = u.structs.get(type);
            if (members == null || !e.getValue()[1].isEmpty() || members.isEmpty())
                throw new TranslateException(Stage.VERTEX, 0, "varying " + e.getKey() + " of type " + type + e.getValue()[1] + " (only non-array structs of plain members are split)");
            for (Std140.Member m : members)
                if (m.array() != 0 || !(Std140.isScalarOrVector(m.type()) || m.type().matches("mat[234](x[234])?")))
                    throw new TranslateException(Stage.VERTEX, 0, "struct varying " + e.getKey() + " has member " + m.name() + " of type " + m.type() + " (not split)");
            structVaryings.put(e.getKey(), members);
            all.remove(e.getKey());
            for (Std140.Member m : members)
                all.put("iroh_sv_" + e.getKey() + "_" + m.name(), new String[]{m.type(), "", "struct:" + e.getKey()});
        }
        Map<String, Integer> varyingLoc = new HashMap<>();
        int loc = 0;
        for (Map.Entry<String, String[]> e : all.entrySet()) {
            String type = e.getValue()[0];
            int arr = arrayCount(e.getValue()[1]);
            if (arr < 0)
                throw new TranslateException(Stage.FRAGMENT, 0, "varying " + e.getKey() + " has an array size that is not a literal: " + e.getValue()[1]);
            int per = type.matches("d?mat[234](x[234])?") ? type.charAt(type.indexOf("mat") + 3) - '0' : type.matches("dvec[34]") ? 2 : 1;
            if (all.get(e.getKey())[0].matches("[A-Z_a-z]\\w*") && !Std140.isScalarOrVector(type) && !type.matches("mat[234](x[234])?"))
                throw new TranslateException(Stage.VERTEX, 0, "varying " + e.getKey() + " of type " + type + " (struct varyings are not supported)");
            int slots = per * Math.max(arr, 1);
            varyingLoc.put(e.getKey(), loc);
            r.varyings.add(new Varying(e.getKey(), type + e.getValue()[1], loc, slots, e.getValue()[2]));
            loc += slots;
        }
        if (loc > 32)
            r.notes.add("varyings use " + loc + " locations (Vulkan guarantees 16 vec4 on some devices; RDNA4 has 32)");

        // ---- vertex inputs
        Map<String, Integer> vInLoc = new HashMap<>();
        if (vs != null) {
            int nextUnknown = 24;
            List<String> unknown = new ArrayList<>();
            for (Decl d : vs.decls)
                if (d.kind.equals("in") && !ATTRIBUTES.containsKey(d.name))
                    unknown.add(d.name);
            java.util.Collections.sort(unknown);
            for (String n : unknown)
                vInLoc.put(n, nextUnknown++);
            for (Decl d : vs.decls)
                if (d.kind.equals("in")) {
                    int l = ATTRIBUTES.getOrDefault(d.name, vInLoc.getOrDefault(d.name, -1));
                    if (d.location != null && d.location != l)
                        r.notes.add("vertex input " + d.name + " asked for location " + d.location + ": Iroh's fixed table puts it at " + l);
                    vInLoc.put(d.name, l);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.name);
                    m.put("type", d.type + d.array);
                    m.put("location", l);
                    m.put("known", ATTRIBUTES.containsKey(d.name));
                    r.vertexInputs.add(m);
                }
            for (Map.Entry<String, String[]> e : VERTEX_BUILTIN_INPUTS.entrySet())
                if (vs.uses(e.getKey()) || (e.getKey().equals("gl_Vertex") && vs.uses("ftransform"))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getValue()[0]);
                    m.put("type", e.getValue()[1]);
                    m.put("location", ATTRIBUTES.get(e.getValue()[0]));
                    m.put("known", true);
                    m.put("compat", e.getKey());
                    r.vertexInputs.add(m);
                    r.builtinsUsed.add(e.getKey());
                }
            r.vertexInputs.sort((x, y) -> Integer.compare((Integer) x.get("location"), (Integer) y.get("location")));
        }

        // ---- fragment outputs
        int fragDataSize = 0;
        if (fs != null) {
            r.drawBuffers = fs.drawBuffers;
            r.drawBuffersSource = fs.drawBuffersSource;
            boolean fd = fs.uses("gl_FragData");
            boolean fc = fs.uses("gl_FragColor");
            if (fd && fc)
                throw new TranslateException(Stage.FRAGMENT, firstLine(fs, "gl_FragColor"), "both gl_FragColor and gl_FragData written");
            int declared = r.drawBuffers == null ? 8 : r.drawBuffers.size();
            if (fd) {
                fragDataSize = Math.max(fs.fragDataDynamic ? declared : 0, fs.fragDataMax + 1);
                fragDataSize = Math.max(fragDataSize, 1);
                for (int i = 0; i < fragDataSize; i++)
                    r.fragmentOutputs.add(output("gl_FragData[" + i + "]", "vec4", i, r.drawBuffers));
                if (r.drawBuffers != null && fs.fragDataMax >= r.drawBuffers.size())
                    r.notes.add("gl_FragData[" + fs.fragDataMax + "] written but the directive names " + r.drawBuffers.size() + " buffers: write dropped");
            }
            if (fc)
                r.fragmentOutputs.add(output("gl_FragColor", "vec4", 0, r.drawBuffers));
            Set<Integer> used = new HashSet<>();
            for (Decl d : fs.decls)
                if (d.kind.equals("out") && d.location != null)
                    used.add(d.location);
            int next = 0;
            for (Decl d : fs.decls)
                if (d.kind.equals("out")) {
                    if (d.location == null) {
                        while (used.contains(next))
                            next++;
                        d.location = next;
                        used.add(next);
                        r.notes.add("fragment output " + d.name + " has no location: given " + next + " in declaration order");
                    }
                    r.fragmentOutputs.add(output(d.name, d.type + d.array, d.location, r.drawBuffers));
                }
            if (r.drawBuffers == null) {
                r.drawBuffersSource = "default (first 8 attachments, OptiFine doc)";
            }
        }
        if (cs != null)
            r.localSize = cs.localSize;

        for (Unit u : units.values()) {
            Map<String, Integer> decls = new HashMap<>();
            for (Decl d : u.decls)
                if (d.stmt >= 0)
                    decls.merge(d.name, 1, Integer::sum);
            u.identCount.forEach((n, c) -> {
                if (c > decls.getOrDefault(n, 0))
                    r.referenced.add(n);
            });
        }
        for (UniformMember m : r.uniforms)
            if (m.source().startsWith("builtin:"))
                r.referenced.add(m.name());

        // ---- generate
        String blockText = blockCode(r);
        for (Unit u : units.values()) {
            Gen g = new Gen(u, r, blockText, varyingLoc, vInLoc, all, samplerMap, imageMap, bufMap, fragDataSize, privateUniforms);
            g.structVaryings = structVaryings;
            String out = g.emit();
            r.glsl.put(u.stage, out);
            r.headerLines.put(u.stage, g.headerLines);
        }
        return r;
    }

    private static Map<String, Object> output(String name, String type, int location, List<Integer> db) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("location", location);
        int count = db == null ? 8 : db.size();
        m.put("target", location < count ? (db == null ? location : db.get(location)) : null);
        return m;
    }

    static int firstLine(Unit u, String id) {
        for (Tok t : u.toks)
            if (t.is(id))
                return t.line();
        return 0;
    }

    private static String blockCode(Result r) {
        if (r.uniforms.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder("layout(std140, set = 0, binding = 0) uniform IrohPackUniforms {\n");
        for (UniformMember m : r.uniforms) {
            sb.append("    layout(offset = ").append(m.offset()).append(") ").append(m.type()).append(' ').append(m.name());
            if (m.array() > 0)
                sb.append('[').append(m.array()).append(']');
            sb.append(";\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    // =========================================================================================== emission

    private static final class Gen {
        final Unit u;
        final Result r;
        final String block;
        final Map<String, Integer> varyingLoc;
        final Map<String, Integer> vInLoc;
        final Map<String, String[]> allVaryings;
        final Map<String, Binding> samplers;
        final Map<String, Binding> images;
        final Map<String, Binding> buffers;
        final int fragDataSize;
        final Map<String, Set<String>> privateUniforms;
        final Map<String, String> renames = new HashMap<>();
        Map<String, List<Std140.Member>> structVaryings = Map.of();
        int headerLines;

        Gen(Unit u, Result r, String block, Map<String, Integer> varyingLoc, Map<String, Integer> vInLoc,
            Map<String, String[]> allVaryings, Map<String, Binding> samplers, Map<String, Binding> images,
            Map<String, Binding> buffers, int fragDataSize, Map<String, Set<String>> privateUniforms) {
            this.u = u;
            this.r = r;
            this.block = block;
            this.varyingLoc = varyingLoc;
            this.vInLoc = vInLoc;
            this.allVaryings = allVaryings;
            this.samplers = samplers;
            this.images = images;
            this.buffers = buffers;
            this.fragDataSize = fragDataSize;
            this.privateUniforms = privateUniforms;
        }

        String emit() {
            buildRenames();
            Map<Integer, String> replace = new HashMap<>(u.replace);
            Set<String> nonConst = nonConstNames();
            List<String> prologue = new ArrayList<>();

            // per statement rewrites
            Map<Integer, StringBuilder> stmtText = new HashMap<>();
            Set<Integer> touched = new HashSet<>();
            for (Decl d : u.decls)
                if (d.stmt >= 0)
                    touched.add(d.stmt);
            for (int si : touched) {
                StringBuilder sb = new StringBuilder();
                for (Decl d : u.decls)
                    if (d.stmt == si)
                        sb.append(declText(d, nonConst));
                replace.put(si, sb.toString());
            }

            StringBuilder body = new StringBuilder(u.text.length() + 1024);
            int pos = 0;
            List<Tok> toks = u.toks;
            Set<Integer> dropTok = new HashSet<>();
            for (int[] p : u.dropPreproc)
                dropTok.add(p[0]);
            int ti = 0;
            int si = 0;
            while (ti < toks.size()) {
                Tok t = toks.get(ti);
                // statement replacement?
                if (si < u.stmts.size() && u.stmts.get(si).firstTok() == ti) {
                    Stmt st = u.stmts.get(si);
                    String rep = replace.get(si);
                    if (rep != null) {
                        body.append(u.text, pos, st.start());
                        String orig = u.text.substring(st.start(), st.end());
                        body.append(rep.replace('\n', ' '));
                        int nl = 0;
                        for (int k = 0; k < orig.length(); k++)
                            if (orig.charAt(k) == '\n')
                                nl++;
                        body.append("\n".repeat(nl));
                        pos = st.end();
                        ti = st.lastTok() + 1;
                        si++;
                        continue;
                    }
                    si++;
                }
                while (si < u.stmts.size() && u.stmts.get(si).firstTok() < ti)
                    si++;
                body.append(u.text, pos, t.start());
                if (dropTok.contains(ti)) {
                    // #version / #extension / #line: dropped (the header writes its own)
                } else if (t.kind() == T.IDENT) {
                    body.append(rename(ti));
                } else {
                    body.append(t.text());
                }
                pos = t.end();
                ti++;
            }
            body.append(u.text, pos, u.text.length());

            StringBuilder head = new StringBuilder();
            head.append("#version 460\n");
            for (String e : u.extensions) {
                String name = e.replaceAll("extension\\s+([\\w]+).*", "$1").trim();
                if (name.startsWith("GL_ARB_") || name.startsWith("GL_EXT_gpu_shader4") || name.startsWith("GL_NV_") || name.startsWith("GL_AMD_") || name.equals("all"))
                    continue; // core in 4.60 (or vendor-only): the 4.60 core functions serve them
                head.append('#').append(e.replace(": require", ": enable")).append('\n');
            }
            if (block.contains("IrohFogParameters"))
                head.append("struct IrohFogParameters { vec4 color; float density; float start; float end; float scale; };\n");
            head.append(block);
            head.append(interfaceCode());
            head.append(helpers());
            String h = head.toString();
            headerLines = (int) h.chars().filter(c -> c == '\n').count();

            StringBuilder out = new StringBuilder(h.length() + body.length() + 512);
            out.append(h).append(body);
            out.append('\n').append(wrapper(prologue));
            return out.toString();
        }

        void buildRenames() {
            for (Map.Entry<String, String> e : FUNC_RENAMES.entrySet())
                if (!u.functions.contains(e.getKey()))
                    renames.put(e.getKey(), e.getValue());
            for (Map.Entry<String, String[]> e : UNIFORM_BUILTINS.entrySet())
                renames.put(e.getKey(), e.getValue()[0]);
            if (u.stage == Stage.VERTEX) {
                for (Map.Entry<String, String[]> e : VERTEX_BUILTIN_INPUTS.entrySet())
                    renames.put(e.getKey(), e.getValue()[0]);
                renames.put("gl_FrontColor", "iroh_FrontColor");
                renames.put("gl_BackColor", "iroh_BackColor");
                renames.put("gl_FrontSecondaryColor", "iroh_FrontSecondaryColor");
                renames.put("gl_BackSecondaryColor", "iroh_BackSecondaryColor");
                renames.put("gl_TexCoord", "iroh_TexCoord");
                renames.put("gl_FogFragCoord", "iroh_FogFragCoord");
                renames.put("gl_ClipVertex", "iroh_ClipVertex");
                renames.put("gl_VertexID", "gl_VertexIndex");
                renames.put("gl_InstanceID", "gl_InstanceIndex");
            } else if (u.stage == Stage.FRAGMENT) {
                renames.put("gl_Color", "iroh_FrontColor");
                renames.put("gl_SecondaryColor", "iroh_FrontSecondaryColor");
                renames.put("gl_TexCoord", "iroh_TexCoord");
                renames.put("gl_FogFragCoord", "iroh_FogFragCoord");
                renames.put("gl_FragData", "iroh_FragData");
                renames.put("gl_FragColor", "iroh_FragColor");
            }
            renames.put("main", u.stage == Stage.COMPUTE ? "main" : "iroh_pack_main");
            // a pack variable called "texture" (OptiFine's old gtexture alias) must not meet texture2D -> texture
            for (Decl d : u.decls)
                if (d.name.equals("texture"))
                    renames.put("texture", "iroh_u_texture");
                else if (NEW_KEYWORDS.contains(d.name))
                    renames.put(d.name, d.name + "_iroh"); // a global named like a 4.60 keyword ("varying float sample;")
        }

        String rename(int ti) {
            Tok t = u.toks.get(ti);
            String id = t.text();
            String r2 = renames.get(id);
            if (r2 != null) {
                // "texture" is renamed only where it is the pack's variable, never at a call of the 4.60 function
                if (id.equals("texture") && ti + 1 < u.toks.size() && u.toks.get(ti + 1).is("("))
                    return id;
                return r2;
            }
            if (NEW_KEYWORDS.contains(id) && usedAsName(ti) && !id.equals("texture"))
                return id + "_iroh";
            return id;
        }

        /** a keyword-like word used as a name: not followed by an identifier or '{' (qualifier position) */
        boolean usedAsName(int ti) {
            if (ti + 1 >= u.toks.size())
                return true;
            Tok n = u.toks.get(ti + 1);
            if (n.kind() == T.IDENT || n.is("{"))
                return false;
            String id = u.toks.get(ti).text();
            if (id.equals("layout") && n.is("("))
                return false;
            return !(id.equals("invariant") && n.kind() == T.IDENT);
        }

        Set<String> nonConstNames() {
            Set<String> s = new HashSet<>();
            for (Decl d : u.decls)
                if (!d.kind.equals("const") && !(d.kind.equals("global") && d.quals.contains("const")))
                    s.add(d.name);
            s.addAll(u.functions);
            for (Map.Entry<String, String[]> b : UNIFORM_BUILTINS.entrySet())
                s.add(b.getKey());
            s.addAll(VERTEX_BUILTIN_INPUTS.keySet());
            s.addAll(List.of("texture", "texture2D", "textureLod", "texelFetch", "texture2DLod", "imageLoad", "gl_FragCoord",
                    "gl_Position", "gl_VertexID", "gl_VertexIndex", "gl_InstanceID", "gl_GlobalInvocationID", "gl_LocalInvocationID",
                    "gl_WorkGroupID", "ftransform", "dFdx", "dFdy", "fwidth", "textureSize", "frameTimeCounter"));
            return s;
        }

        String r(String text) {
            // rename identifiers inside a piece of declaration text (initializers, layout args)
            if (text == null)
                return null;
            StringBuilder sb = new StringBuilder();
            int i = 0;
            int n = text.length();
            while (i < n) {
                char c = text.charAt(i);
                if (Character.isLetter(c) || c == '_') {
                    int j = i;
                    while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_'))
                        j++;
                    String id = text.substring(i, j);
                    sb.append(renames.getOrDefault(id, id));
                    i = j;
                } else if (Character.isDigit(c)) {
                    int j = i;
                    while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '.' || text.charAt(j) == '_'))
                        j++;
                    sb.append(text, i, j);
                    i = j;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }

        String interp(Decl d) {
            StringBuilder sb = new StringBuilder();
            for (String q : d.quals)
                if (INTERP.contains(q) && !q.equals("precise"))
                    sb.append(q).append(' ');
            return sb.toString();
        }

        String declText(Decl d, Set<String> nonConst) {
            String name = renames.getOrDefault(d.name, d.name);
            switch (d.kind) {
                case "uniform": {
                    boolean blockable = arrayCount(d.array) >= 0 && (Std140.isScalarOrVector(d.type) || d.type.matches("mat[234](x[234])?"));
                    if (blockable)
                        return "";
                    if (d.init != null)
                        return d.type + " " + name + d.array + " = " + r(d.init) + "; ";
                    return d.type + " " + name + d.array + " = " + zero(d.type, d.array) + "; ";
                }
                case "sampler":
                    return "layout(set = 0, binding = " + samplers.get(d.name).binding() + ") uniform " + d.type + " " + name + d.array + "; ";
                case "image": {
                    Binding b = images.get(d.name);
                    StringBuilder sb = new StringBuilder("layout(");
                    if (b.format() != null)
                        sb.append(b.format()).append(", ");
                    sb.append("set = 0, binding = ").append(b.binding()).append(") ");
                    for (String q : d.quals)
                        if (MEMORY.contains(q))
                            sb.append(q).append(' ');
                    return sb.append("uniform ").append(d.type).append(' ').append(name).append(d.array).append("; ").toString();
                }
                case "ssbo":
                case "ubo": {
                    Binding b = buffers.get(d.kind + ":" + d.type);
                    String lay = d.layout == null ? "" : d.layout.replaceAll("binding\\s*=\\s*\\d+", "").replaceAll(",\\s*,", ",").replaceAll("^\\s*,|,\\s*$", "").trim();
                    StringBuilder sb = new StringBuilder("layout(");
                    if (!lay.isEmpty())
                        sb.append(lay).append(", ");
                    else
                        sb.append(d.kind.equals("ssbo") ? "std430, " : "std140, ");
                    sb.append("set = ").append(b.set()).append(", binding = ").append(b.binding()).append(") ");
                    for (String q : d.quals)
                        if (MEMORY.contains(q))
                            sb.append(q).append(' ');
                    sb.append(d.kind.equals("ssbo") ? "buffer " : "uniform ").append(d.type).append(' ').append(r(d.init));
                    if (!d.name.isEmpty())
                        sb.append(' ').append(d.name).append(d.array);
                    return sb.append("; ").toString();
                }
                case "in":
                case "out":
                    if (structVaryings.containsKey(d.name) && u.stage != Stage.COMPUTE
                            && ((u.stage == Stage.FRAGMENT && d.kind.equals("in")) || (u.stage == Stage.VERTEX && d.kind.equals("out"))))
                        return d.type + " " + name + "; ";
                    if (d.kind.equals("out"))
                        return outText(d, name);
                    return inText(d, name);
                default:
                    return otherText(d, name, nonConst);
            }
        }

        String inText(Decl d, String name) {
            if (u.stage == Stage.VERTEX)
                return "layout(location = " + vInLoc.get(d.name) + ") in " + d.type + " " + name + d.array + "; ";
            if (u.stage == Stage.FRAGMENT) {
                String flat = isIntegerType(d.type) && !d.quals.contains("flat") ? "flat " : "";
                return "layout(location = " + varyingLoc.get(d.name) + ") " + flat + interp(d) + "in " + d.type + " " + name + d.array + "; ";
            }
            throw new TranslateException(u.stage, d.line, "'in' variable " + d.name + " in a " + u.stage.key + " stage");
        }

        String outText(Decl d, String name) {
            if (u.stage == Stage.VERTEX)
                return "layout(location = " + varyingLoc.get(d.name) + ") " + interp(d) + "out " + d.type + " " + name + d.array + "; ";
            if (u.stage == Stage.FRAGMENT)
                return "layout(location = " + d.location + ") out " + d.type + " " + name + d.array + "; ";
            throw new TranslateException(u.stage, d.line, "'out' variable " + d.name + " in a " + u.stage.key + " stage");
        }

        String otherText(Decl d, String name, Set<String> nonConst) {
            switch (d.kind) {
                case "shared":
                    return "shared " + d.type + " " + name + d.array + "; ";
                case "const": {
                    if (isDirectiveName(d.name) && d.init != null) {
                        u.constDirectives.put(d.name, d.init.trim());
                        r.constDirectives.put(d.name, d.init.trim());
                        for (String id : identsOf(d.init))
                            if (FORMAT_NAMES.contains(id))
                                return ""; // "const int colortex0Format = RGBA16F;" written live: metadata only
                    }
                    boolean demote = false;
                    for (String id : identsOf(d.init == null ? "" : d.init))
                        if (nonConst.contains(id)) {
                            demote = true;
                            break;
                        }
                    String kw = demote ? "" : "const ";
                    if (demote)
                        nonConst.add(d.name);
                    StringBuilder sb = new StringBuilder(kw);
                    for (String q : d.quals)
                        if (q.equals("precise"))
                            sb.append(q).append(' ');
                    sb.append(d.type).append(' ').append(name).append(d.array);
                    if (d.init != null)
                        sb.append(" = ").append(r(d.init));
                    return sb.append("; ").toString();
                }
                case "global": {
                    StringBuilder sb = new StringBuilder();
                    sb.append(d.type).append(' ').append(name).append(d.array);
                    if (d.init != null)
                        sb.append(" = ").append(r(d.init));
                    return sb.append("; ").toString();
                }
                default:
                    throw new TranslateException(u.stage, d.line, "unhandled declaration kind " + d.kind);
            }
        }

        List<String> identsOf(String s) {
            List<String> l = new ArrayList<>();
            Matcher m = Pattern.compile("[A-Za-z_]\\w*").matcher(s);
            while (m.find()) {
                int st = m.start();
                if (st > 0 && (Character.isDigit(s.charAt(st - 1)) || s.charAt(st - 1) == '.'))
                    continue; // swizzle or number suffix
                l.add(m.group());
            }
            return l;
        }

        String zero(String type, String array) {
            int n = arrayCount(array);
            String one = isSampler(type) || isImage(type) ? null : type + "(0)";
            if (one == null)
                throw new TranslateException(u.stage, 0, "cannot zero " + type);
            if (n <= 0)
                return one;
            return type + array + "(" + String.join(", ", java.util.Collections.nCopies(n, one)) + ")";
        }

        String interfaceCode() {
            StringBuilder sb = new StringBuilder();
            for (Decl d : u.decls)
                if (d.stmt < 0)
                    sb.append(declText(d, new HashSet<>())).append('\n');
            if (u.stage == Stage.VERTEX) {
                for (Map.Entry<String, String[]> e : VERTEX_BUILTIN_INPUTS.entrySet())
                    if (u.uses(e.getKey()) || (e.getKey().equals("gl_Vertex") && u.uses("ftransform")))
                        sb.append("layout(location = ").append(ATTRIBUTES.get(e.getValue()[0])).append(") in ")
                                .append(e.getValue()[1]).append(' ').append(e.getValue()[0]).append(";\n");
                // compat + owed varyings
                for (Map.Entry<String, String[]> e : allVaryings.entrySet()) {
                    String from = e.getValue()[2];
                    if (from.equals("compat") || from.equals("owed-by-vertex") || from.startsWith("struct:")) {
                        String flat = isIntegerType(e.getValue()[0]) ? "flat " : "";
                        sb.append("layout(location = ").append(varyingLoc.get(e.getKey())).append(") ").append(flat).append("out ")
                                .append(e.getValue()[0]).append(' ').append(e.getKey()).append(e.getValue()[1]).append(";\n");
                    }
                }
                if (u.uses("gl_BackColor"))
                    sb.append("vec4 iroh_BackColor;\n");
                if (u.uses("gl_BackSecondaryColor"))
                    sb.append("vec4 iroh_BackSecondaryColor;\n");
                if (u.uses("gl_ClipVertex"))
                    sb.append("vec4 iroh_ClipVertex;\n");
                sb.append("invariant gl_Position;\n");
            } else if (u.stage == Stage.FRAGMENT) {
                for (Map.Entry<String, String[]> e : allVaryings.entrySet())
                    if (e.getValue()[2].equals("compat") || e.getValue()[2].startsWith("struct:"))
                        sb.append("layout(location = ").append(varyingLoc.get(e.getKey())).append(") ")
                                .append(isIntegerType(e.getValue()[0]) ? "flat " : "").append("in ")
                                .append(e.getValue()[0]).append(' ').append(e.getKey()).append(e.getValue()[1]).append(";\n");
                if (u.uses("gl_FragData"))
                    sb.append("layout(location = 0) out vec4 iroh_FragData[").append(fragDataSize).append("];\n");
                if (u.uses("gl_FragColor"))
                    sb.append("layout(location = 0) out vec4 iroh_FragColor;\n");
            }
            return sb.toString();
        }

        String helpers() {
            StringBuilder sb = new StringBuilder();
            if (u.uses("shadow2D")) {
                sb.append("vec4 iroh_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }\n");
                sb.append("vec4 iroh_shadow2D(sampler2DShadow s, vec3 c, float b) { return vec4(texture(s, c, b)); }\n");
                sb.append("vec4 iroh_shadow2D(sampler2D s, vec3 c) { return vec4(step(c.z, texture(s, c.xy).r)); }\n");
            }
            if (u.uses("shadow2DLod")) {
                sb.append("vec4 iroh_shadow2DLod(sampler2DShadow s, vec3 c, float l) { return vec4(textureLod(s, c, l)); }\n");
                sb.append("vec4 iroh_shadow2DLod(sampler2D s, vec3 c, float l) { return vec4(step(c.z, textureLod(s, c.xy, l).r)); }\n");
            }
            if (u.uses("shadow2DProj"))
                sb.append("vec4 iroh_shadow2DProj(sampler2DShadow s, vec4 c) { return vec4(textureProj(s, c)); }\n");
            if (u.uses("shadow2DProjLod"))
                sb.append("vec4 iroh_shadow2DProjLod(sampler2DShadow s, vec4 c, float l) { return vec4(textureProjLod(s, c, l)); }\n");
            if (u.uses("shadow2DGrad"))
                sb.append("vec4 iroh_shadow2DGrad(sampler2DShadow s, vec3 c, vec2 dx, vec2 dy) { return vec4(textureGrad(s, c, dx, dy)); }\n");
            if (u.uses("ftransform") && u.stage == Stage.VERTEX)
                sb.append("vec4 iroh_ftransform() { return iroh_ModelViewProjectionMatrix * iris_Vertex; }\n");
            return sb.toString();
        }

        String wrapper(List<String> prologue) {
            if (u.stage == Stage.COMPUTE)
                return "";
            if (!u.hasMain)
                throw new TranslateException(u.stage, 0, "no main() defined");
            StringBuilder sb = new StringBuilder("void main() {\n");
            if (u.stage == Stage.VERTEX) {
                for (Map.Entry<String, String[]> e : allVaryings.entrySet())
                    if (e.getValue()[2].equals("owed-by-vertex"))
                        sb.append("    ").append(e.getKey()).append(" = ").append(zero(e.getValue()[0], e.getValue()[1])).append(";\n");
                if (allVaryings.containsKey("iroh_FrontColor") && !u.uses("gl_FrontColor"))
                    sb.append("    iroh_FrontColor = vec4(1.0);\n");
            }
            for (String p : prologue)
                sb.append("    ").append(p).append('\n');
            if (u.stage == Stage.FRAGMENT)
                structVaryings.forEach((n, ms) -> {
                    for (Std140.Member m : ms)
                        sb.append("    ").append(n).append('.').append(m.name()).append(" = iroh_sv_").append(n).append('_').append(m.name()).append(";\n");
                });
            sb.append("    iroh_pack_main();\n");
            if (u.stage == Stage.VERTEX) {
                structVaryings.forEach((n, ms) -> {
                    for (Std140.Member m : ms)
                        sb.append("    iroh_sv_").append(n).append('_').append(m.name()).append(" = ").append(n).append('.').append(m.name()).append(";\n");
                });
                sb.append("    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n");
            }
            sb.append("}\n");
            return sb.toString();
        }
    }
}
