package dev.elemore.iroh.shaderpack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal SPIR-V reader: interface variables (Input/Output with Location) and descriptor resources
 * (DescriptorSet/Binding), with names and a type string. Used to check the compiled program, not the text:
 * every fragment input must meet a vertex output of the same type at the same location, and every resource the
 * module really declares must match the reflection record the translator wrote. (Vitrail lesson: a per-unit
 * compile cannot see interface defects; only pairing the two reflected interfaces does.)
 */
public final class SpirvReflect {

    public record Var(String name, String storage, int location, int set, int binding, String type, boolean builtin) {}

    private SpirvReflect() {}

    private static final int OP_NAME = 5, OP_TYPE_BOOL = 20, OP_TYPE_INT = 21, OP_TYPE_FLOAT = 22, OP_TYPE_VECTOR = 23,
            OP_TYPE_MATRIX = 24, OP_TYPE_IMAGE = 25, OP_TYPE_SAMPLER = 26, OP_TYPE_SAMPLED_IMAGE = 27, OP_TYPE_ARRAY = 28,
            OP_TYPE_STRUCT = 30, OP_TYPE_POINTER = 32, OP_CONSTANT = 43, OP_VARIABLE = 59, OP_DECORATE = 71;
    private static final int DEC_BUILTIN = 11, DEC_LOCATION = 30, DEC_BINDING = 33, DEC_SET = 34;
    private static final int SC_INPUT = 1, SC_UNIFORM = 2, SC_OUTPUT = 3, SC_UNIFORM_CONSTANT = 0, SC_STORAGE_BUFFER = 12;

    public static List<Var> read(byte[] spv) {
        ByteBuffer b = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN);
        int[] w = new int[spv.length / 4];
        b.asIntBuffer().get(w);
        if (w.length < 5 || w[0] != 0x07230203)
            throw new IllegalArgumentException("not SPIR-V");
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, String> types = new HashMap<>();
        Map<Integer, Long> constants = new HashMap<>();
        Map<Integer, int[]> pointers = new HashMap<>(); // id -> [storage, pointee]
        Map<Integer, Integer> location = new HashMap<>(), set = new HashMap<>(), binding = new HashMap<>();
        Map<Integer, Boolean> builtin = new HashMap<>();
        List<int[]> vars = new ArrayList<>(); // [resultType, id, storage]
        int i = 5;
        while (i < w.length) {
            int op = w[i] & 0xffff;
            int len = w[i] >>> 16;
            if (len == 0)
                break;
            switch (op) {
                case OP_NAME -> names.put(w[i + 1], string(w, i + 2, i + len));
                case OP_TYPE_BOOL -> types.put(w[i + 1], "bool");
                case OP_TYPE_INT -> types.put(w[i + 1], (w[i + 3] == 1 ? "int" : "uint") + w[i + 2]);
                case OP_TYPE_FLOAT -> types.put(w[i + 1], "float" + w[i + 2]);
                case OP_TYPE_VECTOR -> types.put(w[i + 1], types.get(w[i + 2]) + "x" + w[i + 3]);
                case OP_TYPE_MATRIX -> types.put(w[i + 1], "mat" + w[i + 3] + "(" + types.get(w[i + 2]) + ")");
                case OP_TYPE_IMAGE -> types.put(w[i + 1], "image" + (w[i + 3] + 1) + "d" + (w[i + 7] == 2 ? "storage" : "") + "(" + types.get(w[i + 2]) + ")");
                case OP_TYPE_SAMPLER -> types.put(w[i + 1], "sampler");
                case OP_TYPE_SAMPLED_IMAGE -> types.put(w[i + 1], "sampled(" + types.get(w[i + 2]) + ")");
                case OP_TYPE_ARRAY -> types.put(w[i + 1], types.get(w[i + 2]) + "[" + constants.getOrDefault(w[i + 3], -1L) + "]");
                case OP_TYPE_STRUCT -> types.put(w[i + 1], "struct " + w[i + 1]);
                case OP_TYPE_POINTER -> pointers.put(w[i + 1], new int[]{w[i + 2], w[i + 3]});
                case OP_CONSTANT -> constants.put(w[i + 2], (long) w[i + 3]);
                case OP_VARIABLE -> vars.add(new int[]{w[i + 1], w[i + 2], w[i + 3]});
                case OP_DECORATE -> {
                    int target = w[i + 1];
                    switch (w[i + 2]) {
                        case DEC_LOCATION -> location.put(target, w[i + 3]);
                        case DEC_SET -> set.put(target, w[i + 3]);
                        case DEC_BINDING -> binding.put(target, w[i + 3]);
                        case DEC_BUILTIN -> builtin.put(target, true);
                        default -> {
                        }
                    }
                }
                default -> {
                }
            }
            i += len;
        }
        List<Var> out = new ArrayList<>();
        for (int[] v : vars) {
            int[] ptr = pointers.get(v[0]);
            String type = ptr == null ? "?" : types.getOrDefault(ptr[1], "?");
            boolean bi = builtin.getOrDefault(v[1], false) || (ptr != null && builtinStruct(ptr[1], names));
            String storage = switch (v[2]) {
                case SC_INPUT -> "in";
                case SC_OUTPUT -> "out";
                case SC_UNIFORM -> "uniform";
                case SC_UNIFORM_CONSTANT -> "uniform_constant";
                case SC_STORAGE_BUFFER -> "storage_buffer";
                default -> "sc" + v[2];
            };
            out.add(new Var(names.getOrDefault(v[1], "%" + v[1]), storage, location.getOrDefault(v[1], -1),
                    set.getOrDefault(v[1], -1), binding.getOrDefault(v[1], -1), type, bi));
        }
        return out;
    }

    private static boolean builtinStruct(int typeId, Map<Integer, String> names) {
        String n = names.get(typeId);
        return n != null && n.startsWith("gl_");
    }

    private static String string(int[] w, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < to; k++)
            for (int s = 0; s < 4; s++) {
                int c = (w[k] >>> (8 * s)) & 0xff;
                if (c == 0)
                    return sb.toString();
                sb.append((char) c);
            }
        return sb.toString();
    }
}
