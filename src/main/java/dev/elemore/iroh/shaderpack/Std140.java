package dev.elemore.iroh.shaderpack;

import java.util.List;
import java.util.Map;

/** std140 base alignment and size of GLSL types (GL 4.6 spec 7.6.2.2), structs from a definition table. */
final class Std140 {

    /** A struct member: type name, array count (0 = not an array). */
    record Member(String type, String name, int array) {}

    record Layout(int align, int size) {}

    private Std140() {}

    static int roundUp(int v, int a) {
        return (v + a - 1) / a * a;
    }

    static int components(String t) {
        char last = t.charAt(t.length() - 1);
        return Character.isDigit(last) ? last - '0' : 1;
    }

    static boolean isScalarOrVector(String t) {
        return t.matches("(float|int|uint|bool)|([biu]?vec[234])");
    }

    static Layout of(String type, int array, Map<String, List<Member>> structs) {
        Layout base = base(type, structs);
        if (array <= 0)
            return base;
        int stride = roundUp(base.size, Math.max(base.align, 16));
        return new Layout(Math.max(base.align, 16), stride * array);
    }

    static int arrayStride(String type, Map<String, List<Member>> structs) {
        Layout b = base(type, structs);
        return roundUp(b.size, Math.max(b.align, 16));
    }

    private static Layout base(String t, Map<String, List<Member>> structs) {
        if (t.matches("float|int|uint|bool"))
            return new Layout(4, 4);
        if (t.matches("[biu]?vec[234]")) {
            int n = components(t);
            return new Layout(n == 2 ? 8 : 16, 4 * n);
        }
        if (t.matches("mat[234](x[234])?")) {
            int cols = t.charAt(3) - '0';
            return new Layout(16, 16 * cols);
        }
        List<Member> members = structs.get(t);
        if (members != null) {
            int off = 0;
            int align = 16;
            for (Member m : members) {
                Layout l = of(m.type(), m.array(), structs);
                off = roundUp(off, l.align);
                off += l.size;
                align = Math.max(align, l.align);
            }
            return new Layout(align, roundUp(off, align));
        }
        throw new IllegalArgumentException("type " + t + " cannot live in a uniform block");
    }
}
