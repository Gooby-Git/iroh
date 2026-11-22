package dev.elemore.iroh.glshim;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.elemore.iroh.shader.GlslTranslator;
import dev.elemore.iroh.shader.ShaderConversion;
import net.vulkanmod.gl.VkGlBuffer;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlProgram;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.elemore.iroh.glshim.GlNet.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * GLSL programs created through raw GL (P1-2 slice 2, item 2): FancyMenu {@code GlslShaderRuntime} (GLSL layouts),
 * Elementa/UniversalCraft legacy {@code Shader} (Resourcify), satin {@code ValidatingShaderLoader}, Replay Mod
 * {@code Program} (ARB names). Vanilla's ShaderInstance path never gets here (GlStateManagerM / ShaderInstanceM).
 *
 * <ul>
 *   <li>Shader objects keep their source ({@code glShaderSource}, ARB too). Compiling only records success: GL reports
 *       errors at compile time, Iroh at link time, where both stages are known.</li>
 *   <li>{@code glLinkProgram} runs {@link LinkResult#link}: the same {@link GlslTranslator} + shaderc path as mod core
 *       shaders. Attribute locations: {@code glBindAttribLocation}, else declaration order in the free slots. Uniforms
 *       of block types live in one CPU std140 block (written by {@code glUniform*}, read by the UBO suppliers at draw
 *       time); sampler uniforms hold a texture unit. A failure becomes LINK_STATUS false with the message as the log.
 *       Legacy fixed-function built-ins (gl_Vertex, gl_ModelViewProjectionMatrix, ftransform, gl_Fog) fail to link,
 *       as they would in Minecraft's core profile.</li>
 *   <li>{@code glDrawArrays / glDrawElements} with a linked program current: vertices are gathered on the CPU from the
 *       VAO state ({@code glVertexAttribPointer} into GL buffers or client memory, disabled arrays read the current
 *       generic value), converted to vec4 floats, and drawn with Iroh's drawer under a pipeline made from the program
 *       and the current GL state (blend, depth, cull, colour mask through PipelineState).</li>
 *   <li>{@code -Diroh.glshim.dumpPrograms=<dir>} writes every linked program's sources, translation and log.</li>
 * </ul>
 * Render thread only (GlNet calls handlers only there).
 */
public final class GlPrograms {
    private GlPrograms() {}

    static final int GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30;

    // ---------------------------------------------------------------- objects

    static final class ShaderObj {
        final int id, type;
        String source = "";
        boolean deleted;

        ShaderObj(int id, int type) {
            this.id = id;
            this.type = type;
        }
    }

    static final class ProgramObj {
        final int id;
        final List<ShaderObj> shaders = new ArrayList<>();
        final Map<String, Integer> boundAttribs = new HashMap<>();
        boolean linked, deleted;
        String log = "";
        LinkResult link;
        GraphicsPipeline pipeline;
        VertexFormat format;
        ByteBuffer block;
        int[] samplerUnits = new int[0];

        ProgramObj(int id) {
            this.id = id;
        }
    }

    private static final Map<Integer, ShaderObj> SHADERS = new HashMap<>();
    private static final Map<Integer, ProgramObj> PROGRAMS = new HashMap<>();
    private static int nextShaderId = 0x40000000; // apart from VkGlProgram ids (programs share VkGlProgram ids)

    // ---------------------------------------------------------------- link (pure: offline-testable)

    /** Result of translating and compiling one program. Pure function of the sources: used by the harness too. */
    public static final class LinkResult {
        public final boolean ok;
        public final String log;
        public final GlslTranslator.Output output;
        public final SPIRVUtils.SPIRV vertex, fragment;
        /** attribute name per location (index = location); null entries are unused slots */
        public final List<String> attributes;

        LinkResult(boolean ok, String log, GlslTranslator.Output output, SPIRVUtils.SPIRV v, SPIRVUtils.SPIRV f, List<String> attributes) {
            this.ok = ok;
            this.log = log;
            this.output = output;
            this.vertex = v;
            this.fragment = f;
            this.attributes = attributes;
        }

        private static final Pattern LEGACY = Pattern.compile(
                "\\b(gl_Vertex|gl_Normal|gl_Color|gl_MultiTexCoord\\d|gl_ModelView\\w*|gl_Projection\\w*|gl_NormalMatrix|gl_TextureMatrix|ftransform|gl_Fog\\w*|gl_TexCoord|gl_FrontColor|gl_LightSource)\\b");
        private static final Pattern ATTRIB = Pattern.compile(
                "(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|smooth|noperspective|highp|mediump|lowp)\\s+)*(?:in|attribute)\\s+\\w+\\s+(\\w+)\\s*(?:\\[[^]]*])?\\s*;");

        /**
         * Translates and compiles. {@code bound}: glBindAttribLocation calls. {@code compile=false} stops after the
         * translation (harness without shaderc natives).
         */
        public static LinkResult link(String vertexSrc, String fragmentSrc, Map<String, Integer> bound, boolean compile) {
            Matcher legacy = LEGACY.matcher(vertexSrc + "\n" + fragmentSrc);
            if (legacy.find())
                return new LinkResult(false, "Iroh: legacy fixed-function built-in '" + legacy.group(1)
                        + "' is not available (core profile)", null, null, null, null);

            // attribute locations: bound ones first, the rest in declaration order into free slots
            List<String> declared = new ArrayList<>();
            Matcher m = ATTRIB.matcher(stripComments(vertexSrc));
            while (m.find()) if (!declared.contains(m.group(1))) declared.add(m.group(1));
            List<String> slots = new ArrayList<>();
            for (Map.Entry<String, Integer> e : bound.entrySet()) {
                int loc = e.getValue();
                if (loc < 0 || loc >= 16) continue;
                while (slots.size() <= loc) slots.add(null);
                slots.set(loc, e.getKey());
            }
            for (String a : declared) {
                if (slots.contains(a)) continue;
                int free = slots.indexOf(null);
                if (free < 0) slots.add(a);
                else slots.set(free, a);
            }

            GlslTranslator.Output out;
            try {
                out = new GlslTranslator(slots, null, null, LinkResult::offsets).translate(vertexSrc, fragmentSrc);
            } catch (GlslTranslator.TranslationException e) {
                return new LinkResult(false, (e.stage == GlslTranslator.Stage.VERTEX ? "vertex: " : "fragment: ") + e.getMessage(), null, null, null, slots);
            } catch (Throwable t) {
                return new LinkResult(false, "translate: " + t, null, null, null, slots);
            }
            if (!compile)
                return new LinkResult(true, "", out, null, null, slots);
            SPIRVUtils.SPIRV v, f;
            try {
                v = SPIRVUtils.compileShader("glprogram.vsh", out.vertex(), SPIRVUtils.ShaderKind.VERTEX_SHADER);
            } catch (Throwable t) {
                return new LinkResult(false, "vertex: " + t.getMessage(), out, null, null, slots);
            }
            try {
                f = SPIRVUtils.compileShader("glprogram.fsh", out.fragment(), SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
            } catch (Throwable t) {
                v.free();
                return new LinkResult(false, "fragment: " + t.getMessage(), out, null, null, slots);
            }
            return new LinkResult(true, "", out, v, f, slots);
        }

        private static int[] offsets(List<String[]> typeAndName) {
            // same builder as ShaderConversion (the runtime UBO)
            AlignedHelper h = new AlignedHelper(typeAndName);
            return h.offsets;
        }

        private static String stripComments(String s) {
            return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", "");
        }
    }

    /** std140 offsets from the runtime builder (net.vulkanmod AlignedStruct), as ShaderConversion does. */
    private static final class AlignedHelper {
        final int[] offsets;

        AlignedHelper(List<String[]> typeAndName) {
            net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder b = new net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder();
            for (String[] tn : typeAndName) b.addUniformInfo(Uniform.createUniformInfo(tn[0], tn[1]));
            UBO ubo = b.buildUBO(0, org.lwjgl.vulkan.VK11.VK_SHADER_STAGE_ALL);
            offsets = new int[typeAndName.size()];
            for (int i = 0; i < offsets.length; i++) offsets[i] = ubo.getUniforms().get(i).getOffset() * 4;
        }
    }

    // ---------------------------------------------------------------- GL entry points

    static int createShader(int type) {
        int id = nextShaderId++;
        SHADERS.put(id, new ShaderObj(id, type));
        return id;
    }

    static void shaderSource(int shader, int count, long strings, long lengths) {
        ShaderObj s = SHADERS.get(shader);
        if (s == null) return;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < count; k++) {
            long str = memGetAddress(strings + (long) k * org.lwjgl.system.Pointer.POINTER_SIZE);
            if (str == NULL) continue;
            int len = lengths != NULL ? memGetInt(lengths + 4L * k) : -1;
            sb.append(len < 0 ? memUTF8(str) : memUTF8(str, len));
        }
        s.source = sb.toString();
    }

    static int createProgram() {
        int id = VkGlProgram.genProgramId();
        PROGRAMS.put(id, new ProgramObj(id));
        return id;
    }

    /** Source captured from glShaderSource (tests, dumps). */
    public static String sourceOf(int shader) {
        ShaderObj s = SHADERS.get(shader);
        return s != null ? s.source : null;
    }

    /** glBindAttribLocation calls recorded for a program (tests). */
    public static Map<String, Integer> boundAttribsOf(int program) {
        ProgramObj p = PROGRAMS.get(program);
        return p != null ? p.boundAttribs : Map.of();
    }

    static boolean isShader(int id) {
        return SHADERS.containsKey(id);
    }

    static void attach(int program, int shader) {
        ProgramObj p = PROGRAMS.get(program);
        ShaderObj s = SHADERS.get(shader);
        if (p != null && s != null && !p.shaders.contains(s)) p.shaders.add(s);
    }

    static void detach(int program, int shader) {
        ProgramObj p = PROGRAMS.get(program);
        if (p != null) p.shaders.removeIf(s -> s.id == shader);
    }

    static void deleteObject(int id) {
        ShaderObj s = SHADERS.remove(id);
        if (s != null) return;
        ProgramObj p = PROGRAMS.remove(id);
        if (p != null) {
            p.deleted = true;
            if (p.pipeline != null) p.pipeline.cleanUp();
            if (p.block != null) memFree(p.block);
        }
    }

    static void bindAttribLocation(int program, int index, String name) {
        ProgramObj p = PROGRAMS.get(program);
        if (p != null && name != null) p.boundAttribs.put(name, index);
    }

    static void link(int program) {
        ProgramObj p = PROGRAMS.get(program);
        if (p == null) return;
        String vs = null, fs = null;
        for (ShaderObj s : p.shaders) {
            if (s.type == GL_VERTEX_SHADER) vs = s.source;
            else if (s.type == GL_FRAGMENT_SHADER) fs = s.source;
        }
        if (vs == null || fs == null) {
            fail(p, "Iroh: a program needs a vertex and a fragment shader");
            return;
        }
        LinkResult r = LinkResult.link(vs, fs, p.boundAttribs, true);
        dump(p, vs, fs, r);
        if (!r.ok) {
            fail(p, r.log);
            return;
        }
        try {
            build(p, r);
            p.linked = true;
            p.log = "";
            GlShim.note("glLinkProgram(ok, " + r.attributes.size() + " attributes, " + r.output.uniforms().size() + " uniforms)", p.id);
        } catch (Throwable t) {
            fail(p, "Iroh: pipeline: " + t);
        }
    }

    private static void fail(ProgramObj p, String log) {
        p.linked = false;
        p.log = log;
        GlShim.LOGGER.warn("Iroh GL shim: program {} from {} did not link: {}", p.id, GlShim.modOf(GlShim.callerClass()), log);
    }

    private static void build(ProgramObj p, LinkResult r) {
        if (p.pipeline != null) p.pipeline.cleanUp();
        if (p.block != null) memFree(p.block);

        UBO ubo = ShaderConversion.buildUbo(r.output);
        int blockBytes = 16;
        for (Uniform u : ubo.getUniforms()) blockBytes = Math.max(blockBytes, (u.getOffset() + u.getSize()) * 4);
        p.block = memCalloc(blockBytes);
        for (Uniform u : ubo.getUniforms()) {
            MappedBuffer slice = MappedBuffer.createFromBuffer(memByteBuffer(memAddress(p.block) + u.getOffset() * 4L, u.getSize() * 4));
            u.setSupplier(() -> slice);
        }
        List<ImageDescriptor> images = new ArrayList<>();
        for (GlslTranslator.SamplerDecl s : r.output.samplers())
            images.add(new ImageDescriptor(s.binding(), "sampler2D", s.name(), s.imageIdx()));
        p.samplerUnits = new int[r.output.samplers().size()];

        p.format = vertexFormat(r.attributes);
        Pipeline.Builder b = new Pipeline.Builder(p.format, "iroh_glprogram_" + p.id);
        List<UBO> ubos = new ArrayList<>();
        ubos.add(ubo);
        b.setUniforms(ubos, images);
        b.setSPIRVs(r.vertex, r.fragment);
        p.pipeline = b.createGraphicsPipeline();
        p.link = r;
    }

    // ---- vertex formats: one vec4-float element per attribute location (registered once in free element ids)

    private static final VertexFormatElement[] ATTR = new VertexFormatElement[16];

    private static VertexFormatElement attrElement(int loc) {
        if (ATTR[loc] == null) {
            for (int id = VertexFormatElement.MAX_COUNT - 1; id >= 0; id--) {
                if (VertexFormatElement.byId(id) == null) {
                    ATTR[loc] = VertexFormatElement.register(id, 0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 4);
                    break;
                }
            }
            if (ATTR[loc] == null) throw new IllegalStateException("no free VertexFormatElement id for GL attribute " + loc);
        }
        return ATTR[loc];
    }

    private static final Map<Integer, VertexFormat> FORMATS = new HashMap<>();

    static VertexFormat vertexFormat(List<String> attributes) {
        int n = Math.max(1, attributes.size());
        return FORMATS.computeIfAbsent(n, k -> {
            VertexFormat.Builder b = VertexFormat.builder();
            for (int i = 0; i < k; i++) b.add("iroh_attr" + i, attrElement(i));
            return b.build();
        });
    }

    // ---- queries

    static int getProgramiv(int program, int pname) {
        ProgramObj p = PROGRAMS.get(program);
        if (p == null) return pname == 0x8B82 || pname == 0x8B83 ? 1 : 0; // unknown (vanilla-made) program: OK
        return switch (pname) {
            case 0x8B82, 0x8B83 -> p.linked ? 1 : 0;          // LINK_STATUS, VALIDATE_STATUS
            case 0x8B84 -> p.log.isEmpty() ? 0 : p.log.length() + 1; // INFO_LOG_LENGTH
            case 0x8B85 -> p.shaders.size();                    // ATTACHED_SHADERS
            case 0x8B80 -> p.deleted ? 1 : 0;                   // DELETE_STATUS
            case 0x8B86 -> p.link != null ? p.link.output.uniforms().size() + p.link.output.samplers().size() : 0;
            case 0x8B89 -> p.link != null ? (int) p.link.attributes.stream().filter(a -> a != null).count() : 0;
            case 0x8B4F -> 0;
            default -> 0;
        };
    }

    static int getShaderiv(int shader, int pname) {
        ShaderObj s = SHADERS.get(shader);
        if (s == null) return pname == 0x8B81 ? 1 : 0;
        return switch (pname) {
            case 0x8B81 -> 1;                // COMPILE_STATUS: errors are reported at link (both stages known)
            case 0x8B4F -> s.type;           // SHADER_TYPE
            case 0x8B88 -> s.source.length() + 1; // SHADER_SOURCE_LENGTH
            case 0x8B80 -> s.deleted ? 1 : 0;
            default -> 0;                    // INFO_LOG_LENGTH 0
        };
    }

    static void infoLog(int obj, int bufSize, long length, long out) {
        ProgramObj p = PROGRAMS.get(obj);
        String log = p != null ? p.log : "";
        byte[] b = log.getBytes(StandardCharsets.UTF_8);
        int n = Math.max(0, Math.min(b.length, bufSize - 1));
        if (out != NULL && bufSize > 0) {
            for (int k = 0; k < n; k++) memPutByte(out + k, b[k]);
            memPutByte(out + n, (byte) 0);
        }
        if (length != NULL) memPutInt(length, n);
    }

    // ---- uniforms: location = member index; samplers 1000 + j

    static int uniformLocation(int program, String name) {
        ProgramObj p = PROGRAMS.get(program);
        if (p == null || p.link == null || name == null) return -1;
        String n = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;
        List<GlslTranslator.UniformDecl> us = p.link.output.uniforms();
        for (int i = 0; i < us.size(); i++) if (us.get(i).name().equals(n)) return i;
        List<GlslTranslator.SamplerDecl> ss = p.link.output.samplers();
        for (int j = 0; j < ss.size(); j++) if (ss.get(j).name().equals(n)) return 1000 + j;
        return -1;
    }

    static int attribLocation(int program, String name) {
        ProgramObj p = PROGRAMS.get(program);
        if (p == null || p.link == null) return -1;
        return p.link.attributes.indexOf(name);
    }

    private static ProgramObj current() {
        return PROGRAMS.get(GlState.currentProgram());
    }

    /** glUniform*: {@code vals} at {@code src} (floats or ints, {@code comps} per element, {@code count} elements). */
    static void uniform(int loc, int comps, int count, boolean ints, long src) {
        ProgramObj p = current();
        if (p == null || p.link == null || loc < 0) return;
        if (loc >= 1000) {
            int j = loc - 1000;
            if (j < p.samplerUnits.length && ints) p.samplerUnits[j] = memGetInt(src);
            return;
        }
        List<GlslTranslator.UniformDecl> us = p.link.output.uniforms();
        if (loc >= us.size()) return;
        GlslTranslator.UniformDecl u = us.get(loc);
        long dst = memAddress(p.block) + u.offsetBytes();
        boolean memberInt = u.type().startsWith("i") || u.type().equals("int");
        int n = Math.min(comps, componentsOf(u.type()));
        for (int k = 0; k < n; k++) {
            long s = src + 4L * k;
            if (memberInt == ints) memPutInt(dst + 4L * k, memGetInt(s));
            else if (memberInt) memPutInt(dst + 4L * k, (int) memGetFloat(s));
            else memPutFloat(dst + 4L * k, memGetInt(s));
        }
    }

    static void uniformMatrix(int loc, int dim, int count, boolean transpose, long src) {
        ProgramObj p = current();
        if (p == null || p.link == null || loc < 0 || loc >= 1000) return;
        List<GlslTranslator.UniformDecl> us = p.link.output.uniforms();
        if (loc >= us.size()) return;
        GlslTranslator.UniformDecl u = us.get(loc);
        int mdim = u.type().equals("mat4") ? 4 : u.type().equals("mat3") ? 3 : 0;
        if (mdim != dim) return;
        long dst = memAddress(p.block) + u.offsetBytes();
        // std140: each column padded to vec4
        for (int c = 0; c < dim; c++)
            for (int r = 0; r < dim; r++) {
                float v = memGetFloat(src + 4L * (transpose ? r * dim + c : c * dim + r));
                memPutFloat(dst + 16L * c + 4L * r, v);
            }
    }

    private static int componentsOf(String type) {
        return switch (type) {
            case "float", "int" -> 1;
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- vertex arrays

    static final class Attrib {
        boolean enabled;
        int size = 4, type = 0x1406, stride, buffer;
        boolean normalized;
        long pointer;
    }

    private static final Map<Integer, Attrib[]> VAOS = new HashMap<>();
    private static final float[][] CURRENT = new float[16][];

    static {
        for (int i = 0; i < 16; i++) CURRENT[i] = new float[]{0, 0, 0, 1};
    }

    private static Attrib attrib(int index) {
        Attrib[] a = VAOS.computeIfAbsent(GlState.currentVertexArray(), k -> {
            Attrib[] arr = new Attrib[16];
            for (int i = 0; i < 16; i++) arr[i] = new Attrib();
            return arr;
        });
        return index >= 0 && index < 16 ? a[index] : null;
    }

    static void vertexAttribPointer(int index, int size, int type, boolean norm, int stride, long ptr) {
        Attrib a = attrib(index);
        if (a == null) return;
        a.size = size == 0x80E1 ? 4 : size; // GL_BGRA size
        a.type = type;
        a.normalized = norm;
        a.stride = stride;
        a.pointer = ptr;
        a.buffer = VkGlBuffer.getBinding(0x8892);
    }

    static void enableAttrib(int index, boolean on) {
        Attrib a = attrib(index);
        if (a != null) a.enabled = on;
    }

    static void vertexAttrib(int index, float x, float y, float z, float w) {
        if (index >= 0 && index < 16) CURRENT[index] = new float[]{x, y, z, w};
    }

    static void deleteVertexArray(int id) {
        VAOS.remove(id);
    }

    // ---------------------------------------------------------------- draws

    /** False when no emulated program is current (the call is then reported as unhandled by the caller). */
    static boolean drawArrays(int mode, int first, int count) {
        ProgramObj p = current();
        if (p == null) return false;
        if (!p.linked || count <= 0) return true;
        int[] idx = new int[count];
        for (int k = 0; k < count; k++) idx[k] = first + k;
        submit(p, mode, idx);
        return true;
    }

    static boolean drawElements(int mode, int count, int type, long indices) {
        ProgramObj p = current();
        if (p == null) return false;
        if (!p.linked || count <= 0) return true;
        int eab = VkGlBuffer.getBinding(0x8893);
        long base = eab != 0 ? VkGlBuffer.dataAddress(eab) + indices : indices;
        if (base == NULL) return true;
        int[] idx = new int[count];
        for (int k = 0; k < count; k++)
            idx[k] = switch (type) {
                case 0x1401 -> memGetByte(base + k) & 0xFF;
                case 0x1403 -> memGetShort(base + 2L * k) & 0xFFFF;
                default -> memGetInt(base + 4L * k);
            };
        submit(p, mode, idx);
        return true;
    }

    private static VertexFormat.Mode mode(int gl) {
        return switch (gl) {
            case 0x0004 -> VertexFormat.Mode.TRIANGLES;
            case 0x0005 -> VertexFormat.Mode.TRIANGLE_STRIP;
            case 0x0006 -> VertexFormat.Mode.TRIANGLE_FAN;
            case 0x0007 -> VertexFormat.Mode.QUADS;
            case 0x0001 -> VertexFormat.Mode.DEBUG_LINES;
            case 0x0003 -> VertexFormat.Mode.DEBUG_LINE_STRIP;
            default -> null;
        };
    }

    private static void submit(ProgramObj p, int glMode, int[] idx) {
        VertexFormat.Mode m = mode(glMode);
        if (m == null) {
            GlShim.unhandled("glDraw*(mode 0x" + Integer.toHexString(glMode) + ")");
            return;
        }
        if (!Renderer.isRecording()) return;
        int n = Math.max(1, p.link.attributes.size());
        int vsize = n * 16;
        ByteBuffer vb = memAlloc(idx.length * vsize);
        try {
            long out = memAddress(vb);
            for (int loc = 0; loc < n; loc++) {
                Attrib a = attrib(loc);
                long col = out + 16L * loc;
                if (a == null || !a.enabled) {
                    float[] c = CURRENT[loc];
                    for (int v = 0; v < idx.length; v++) {
                        long d = col + (long) v * vsize;
                        for (int k = 0; k < 4; k++) memPutFloat(d + 4L * k, c[k]);
                    }
                    continue;
                }
                int es = PixelTransfer.elementSize(a.type);
                if (es <= 0) es = 4;
                long stride = a.stride != 0 ? a.stride : (long) a.size * es;
                long base = a.buffer != 0 ? VkGlBuffer.dataAddress(a.buffer) + a.pointer : a.pointer;
                long limit = a.buffer != 0 ? VkGlBuffer.dataAddress(a.buffer) + VkGlBuffer.dataSize(a.buffer) : Long.MAX_VALUE;
                for (int v = 0; v < idx.length; v++) {
                    long s = base + (long) idx[v] * stride;
                    long d = col + (long) v * vsize;
                    for (int k = 0; k < 4; k++) {
                        float val = k == 3 ? 1f : 0f;
                        if (k < a.size && base != NULL && s + (long) (k + 1) * es <= limit)
                            val = component(s + (long) k * es, a.type, a.normalized);
                        memPutFloat(d + 4L * k, val);
                    }
                }
            }
            draw(p, m, vb, idx.length);
        } finally {
            memFree(vb);
        }
    }

    private static float component(long p, int type, boolean norm) {
        return switch (type) {
            case 0x1406 -> memGetFloat(p);
            case 0x1401 -> norm ? (memGetByte(p) & 0xFF) / 255f : memGetByte(p) & 0xFF;
            case 0x1400 -> norm ? Math.max(memGetByte(p) / 127f, -1f) : memGetByte(p);
            case 0x1403 -> norm ? (memGetShort(p) & 0xFFFF) / 65535f : memGetShort(p) & 0xFFFF;
            case 0x1402 -> norm ? Math.max(memGetShort(p) / 32767f, -1f) : memGetShort(p);
            case 0x1405 -> norm ? (float) ((memGetInt(p) & 0xFFFFFFFFL) / 4294967295.0) : (float) (memGetInt(p) & 0xFFFFFFFFL);
            case 0x1404 -> norm ? Math.max(memGetInt(p) / 2147483647f, -1f) : memGetInt(p);
            case 0x140B -> PixelTransfer.halfToFloat(memGetShort(p));
            default -> 0f;
        };
    }

    private static void draw(ProgramObj p, VertexFormat.Mode m, ByteBuffer vb, int count) {
        if (!dev.elemore.iroh.diag.SlowFrames.ON) {
            draw0(p, m, vb, count);
            return;
        }
        dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.EMU_DRAWS, 1); // perf/frame-attrib
        dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.EMU_DRAWS);
        try {
            draw0(p, m, vb, count);
        } finally {
            dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.EMU_DRAWS);
        }
    }

    private static void draw0(ProgramObj p, VertexFormat.Mode m, ByteBuffer vb, int count) {
        Renderer r = Renderer.getInstance();
        if (r.getBoundRenderPass() == null) {
            if (VkGlFramebuffer.getBoundFramebuffer() == null) r.getMainPass().rebindMainTarget();
            else return;
        }
        VRenderSystem.setPrimitiveTopologyGL(m.asGLMode);
        // sampler j reads GL unit samplerUnits[j]: point Iroh's slot j (the descriptor's imageIdx) at that unit's image
        int ns = p.samplerUnits.length;
        VulkanImage[] saved = new VulkanImage[ns];
        List<GlslTranslator.SamplerDecl> ss = p.link.output.samplers();
        for (int j = 0; j < ns; j++) {
            int slot = ss.get(j).imageIdx();
            if (slot < 0 || slot >= VTextureSelector.SIZE) continue;
            saved[j] = VTextureSelector.getImage(slot);
            VkGlTexture t = VkGlTexture.getTexture(VkGlTexture.getUnitBinding(p.samplerUnits[j]));
            VulkanImage img = t != null ? t.getVulkanImage() : null;
            VTextureSelector.bindTexture(slot, img != null ? img : VTextureSelector.getWhiteTexture());
        }
        try {
            r.bindGraphicsPipeline(p.pipeline);
            r.uploadAndBindUBOs(p.pipeline);
            Renderer.getDrawer().draw(vb, m, p.format, count);
        } finally {
            for (int j = 0; j < ns; j++) {
                int slot = ss.get(j).imageIdx();
                if (slot >= 0 && slot < VTextureSelector.SIZE && saved[j] != null) VTextureSelector.bindTexture(slot, saved[j]);
            }
        }
    }

    // ---------------------------------------------------------------- dump

    private static final String DUMP = System.getProperty("iroh.glshim.dumpPrograms");

    private static void dump(ProgramObj p, String vs, String fs, LinkResult r) {
        if (DUMP == null || DUMP.isEmpty()) return;
        try {
            Path dir = Path.of(DUMP);
            Files.createDirectories(dir);
            String mod = GlShim.modOf(GlShim.callerClass()).replaceAll("[^A-Za-z0-9_.-]", "_");
            String base = mod + "_program" + p.id;
            Files.writeString(dir.resolve(base + ".vsh"), vs);
            Files.writeString(dir.resolve(base + ".fsh"), fs);
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("caller", GlShim.callerFrame());
            meta.put("ok", String.valueOf(r.ok));
            meta.put("log", r.log);
            meta.put("bound", p.boundAttribs.toString());
            meta.put("attributes", String.valueOf(r.attributes));
            StringBuilder sb = new StringBuilder();
            meta.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
            if (r.output != null) sb.append("\n--- vertex (translated)\n").append(r.output.vertex())
                    .append("\n--- fragment (translated)\n").append(r.output.fragment());
            Files.writeString(dir.resolve(base + ".txt"), sb.toString());
        } catch (Throwable t) {
            GlShim.LOGGER.warn("Iroh GL shim: program dump failed", t);
        }
    }

    // ---------------------------------------------------------------- registration

    static void register() {
        on("glCreateShader", "I:I", a -> createShader(i(a, 0)));
        on("glCreateShaderObjectARB", "I:I", a -> createShader(i(a, 0)));
        on("glShaderSource", "V:IIPP", a -> { shaderSource(i(a, 0), i(a, 1), p(a, 2), p(a, 3)); return 0; });
        on("glShaderSourceARB", "V:IIPP", a -> { shaderSource(i(a, 0), i(a, 1), p(a, 2), p(a, 3)); return 0; });
        on("glCompileShader", "V:I", a -> 0);
        on("glCompileShaderARB", "V:I", a -> 0);
        on("glIsShader", "Z:I", a -> isShader(i(a, 0)) ? 1 : 0);
        on("glDeleteShader", "V:I", a -> { deleteObject(i(a, 0)); return 0; });
        on("glGetShaderiv", "V:IIP", a -> { memPutInt(p(a, 2), getShaderiv(i(a, 0), i(a, 1))); return 0; });
        on("glGetShaderInfoLog", "V:IIPP", a -> { infoLog(-1, i(a, 1), p(a, 2), p(a, 3)); return 0; });

        on("glCreateProgram", "I:", a -> createProgram());
        on("glCreateProgramObjectARB", "I:", a -> createProgram());
        on("glAttachShader", "V:II", a -> { attach(i(a, 0), i(a, 1)); return 0; });
        on("glAttachObjectARB", "V:II", a -> { attach(i(a, 0), i(a, 1)); return 0; });
        on("glDetachShader", "V:II", a -> { detach(i(a, 0), i(a, 1)); return 0; });
        on("glDetachObjectARB", "V:II", a -> { detach(i(a, 0), i(a, 1)); return 0; });
        on("glBindAttribLocation", "V:IIP", a -> { bindAttribLocation(i(a, 0), i(a, 1), memUTF8Safe(p(a, 2))); return 0; });
        on("glBindAttribLocationARB", "V:IIP", a -> { bindAttribLocation(i(a, 0), i(a, 1), memUTF8Safe(p(a, 2))); return 0; });
        on("glLinkProgram", "V:I", a -> { link(i(a, 0)); return 0; });
        on("glLinkProgramARB", "V:I", a -> { link(i(a, 0)); return 0; });
        on("glValidateProgram", "V:I", a -> 0);
        on("glValidateProgramARB", "V:I", a -> 0);
        on("glIsProgram", "Z:I", a -> PROGRAMS.containsKey(i(a, 0)) ? 1 : 0);
        on("glDeleteProgram", "V:I", a -> { deleteObject(i(a, 0)); return 0; });
        on("glDeleteObjectARB", "V:I", a -> { deleteObject(i(a, 0)); return 0; });
        on("glGetProgramiv", "V:IIP", a -> { memPutInt(p(a, 2), getProgramiv(i(a, 0), i(a, 1))); return 0; });
        on("glGetObjectParameterivARB", "V:IIP", a -> {
            int obj = i(a, 0);
            memPutInt(p(a, 2), SHADERS.containsKey(obj) ? getShaderiv(obj, i(a, 1)) : getProgramiv(obj, i(a, 1)));
            return 0;
        });
        on("glGetProgramInfoLog", "V:IIPP", a -> { infoLog(i(a, 0), i(a, 1), p(a, 2), p(a, 3)); return 0; });
        on("glGetInfoLogARB", "V:IIPP", a -> { infoLog(i(a, 0), i(a, 1), p(a, 2), p(a, 3)); return 0; });
        on("glUseProgramObjectARB", "V:I", a -> { GlState.useProgram(i(a, 0)); return 0; });
        on("glGetUniformLocation", "I:IP", a -> uniformLocation(i(a, 0), memUTF8Safe(p(a, 1))));
        on("glGetUniformLocationARB", "I:IP", a -> uniformLocation(i(a, 0), memUTF8Safe(p(a, 1))));
        on("glGetAttribLocation", "I:IP", a -> attribLocation(i(a, 0), memUTF8Safe(p(a, 1))));
        on("glGetAttribLocationARB", "I:IP", a -> attribLocation(i(a, 0), memUTF8Safe(p(a, 1))));

        // glUniform{1..4}{f,i}(ARB) and the v forms; matrices
        String[] sfx = {"", "ARB"};
        for (String x : sfx) {
            for (int c = 1; c <= 4; c++) {
                final int comps = c;
                on("glUniform" + c + "f" + x, "V:I" + "F".repeat(c), a -> {
                    try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
                        long buf = st.nmalloc(16);
                        for (int k = 0; k < comps; k++) memPutFloat(buf + 4L * k, f(a, 1 + k));
                        uniform(i(a, 0), comps, 1, false, buf);
                    }
                    return 0;
                });
                on("glUniform" + c + "i" + x, "V:I" + "I".repeat(c), a -> {
                    try (org.lwjgl.system.MemoryStack st = org.lwjgl.system.MemoryStack.stackPush()) {
                        long buf = st.nmalloc(16);
                        for (int k = 0; k < comps; k++) memPutInt(buf + 4L * k, i(a, 1 + k));
                        uniform(i(a, 0), comps, 1, true, buf);
                    }
                    return 0;
                });
                on("glUniform" + c + "fv" + x, "V:IIP", a -> { uniform(i(a, 0), comps, i(a, 1), false, p(a, 2)); return 0; });
                on("glUniform" + c + "iv" + x, "V:IIP", a -> { uniform(i(a, 0), comps, i(a, 1), true, p(a, 2)); return 0; });
            }
            for (int d = 2; d <= 4; d++) {
                final int dim = d;
                on("glUniformMatrix" + d + "fv" + x, "V:IIZP", a -> { uniformMatrix(i(a, 0), dim, i(a, 1), z(a, 2), p(a, 3)); return 0; });
            }
        }

        on("glVertexAttribPointer", "V:IIIZIP", a -> { vertexAttribPointer(i(a, 0), i(a, 1), i(a, 2), z(a, 3), i(a, 4), p(a, 5)); return 0; });
        on("glVertexAttribPointerARB", "V:IIIZIP", a -> { vertexAttribPointer(i(a, 0), i(a, 1), i(a, 2), z(a, 3), i(a, 4), p(a, 5)); return 0; });
        on("glEnableVertexAttribArray", "V:I", a -> { enableAttrib(i(a, 0), true); return 0; });
        on("glDisableVertexAttribArray", "V:I", a -> { enableAttrib(i(a, 0), false); return 0; });
        on("glVertexAttrib4f", "V:IFFFF", a -> { vertexAttrib(i(a, 0), f(a, 1), f(a, 2), f(a, 3), f(a, 4)); return 0; });
        on("glVertexAttrib3f", "V:IFFF", a -> { vertexAttrib(i(a, 0), f(a, 1), f(a, 2), f(a, 3), 1); return 0; });
        on("glVertexAttrib2f", "V:IFF", a -> { vertexAttrib(i(a, 0), f(a, 1), f(a, 2), 0, 1); return 0; });
        on("glVertexAttrib1f", "V:IF", a -> { vertexAttrib(i(a, 0), f(a, 1), 0, 0, 1); return 0; });

        on("glDrawArrays", "V:III", a -> {
            if (!drawArrays(i(a, 0), i(a, 1), i(a, 2))) GlShim.unhandled("glDrawArrays(no GL program current)");
            return 0;
        });
        on("glDrawElements", "V:IIIP", a -> {
            if (!drawElements(i(a, 0), i(a, 1), i(a, 2), p(a, 3))) GlShim.unhandled("glDrawElements(no GL program current)");
            return 0;
        });

        on("glBufferSubData", "V:IJJP", a -> { VkGlBuffer.bufferSubData(i(a, 0), j(a, 1), j(a, 2), p(a, 3)); return 0; });
        on("glMapBufferRange", "P:IJJI", a -> VkGlBuffer.mapRange(i(a, 0), j(a, 1), j(a, 2)));
    }
}
