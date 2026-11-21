package dev.elemore.iroh.compat;

import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * rc3r2 (td-c8876c): exact link check of one mod jar against the built Iroh jar, from constant pools / bytecode, the way
 * the JVM resolves at run time. Collects every reference the mod's classes make to an owner matching {@code ownerPrefix}
 * (method calls with their opcode, field accesses, NEW, supertypes, invokedynamic method handles) and every Mixin aimed
 * at such a class (@Shadow members, @Accessor / @Invoker, @Inject / @Redirect target methods, @At INVOKE / FIELD targets).
 * Each is resolved in the Iroh jar: exact owner / name / descriptor, searched up the superclass and interface chain inside
 * the jar, static-ness matching the opcode, public access (or protected from a subclass), NEW only on non-abstract classes.
 */
public final class LinkSurface {
    public record Problem(String where, String what) {}

    public final List<Problem> problems = new ArrayList<>();
    public int checked;

    private final Map<String, Cls> iroh = new HashMap<>();
    private final Predicate<String> owned;

    private static final class Cls {
        String name, sup;
        int access;
        String[] itfs;
        final Map<String, Integer> methods = new HashMap<>(); // name+desc -> access
        final Map<String, Integer> fields = new HashMap<>();  // name+":"+desc -> access
    }

    public LinkSurface(Path irohJar, Predicate<String> owned) throws IOException {
        this.owned = owned;
        try (ZipFile z = new ZipFile(irohJar.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = z.getInputStream(e)) {
                    Cls c = new Cls();
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itfs) {
                            c.name = name; c.sup = sup; c.access = acc; c.itfs = itfs == null ? new String[0] : itfs;
                        }
                        @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                            c.methods.put(name + desc, acc); return null;
                        }
                        @Override public FieldVisitor visitField(int acc, String name, String desc, String sig, Object v) {
                            c.fields.put(name + ":" + desc, acc); return null;
                        }
                    }, ClassReader.SKIP_CODE);
                    iroh.put(c.name, c);
                }
            }
        }
    }

    /** Checks every class of {@code modJar} whose entry name passes {@code include}. */
    public void check(Path modJar, Predicate<String> include) throws IOException {
        Map<String, String> modSupers = new HashMap<>();
        List<byte[]> classes = new ArrayList<>();
        try (ZipFile z = new ZipFile(modJar.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class") || !include.test(e.getName())) continue;
                try (InputStream in = z.getInputStream(e)) {
                    byte[] b = in.readAllBytes();
                    classes.add(b);
                    ClassReader r = new ClassReader(b);
                    modSupers.put(r.getClassName(), r.getSuperName());
                }
            }
        }
        for (byte[] b : classes) checkClass(b, modSupers);
    }

    private void checkClass(byte[] bytes, Map<String, String> modSupers) {
        ClassReader r = new ClassReader(bytes);
        String self = r.getClassName();
        List<String> mixinTargets = new ArrayList<>();
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itfs) {
                if (sup != null && owned.test(sup)) {
                    checked++;
                    Cls c = iroh.get(sup);
                    if (c == null) problem(self, "extends missing class " + sup);
                    else if ((c.access & Opcodes.ACC_FINAL) != 0) problem(self, "extends final " + sup);
                }
                if (itfs != null) for (String i : itfs) if (owned.test(i)) { checked++; if (!iroh.containsKey(i)) problem(self, "implements missing " + i); }
            }

            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitArray(String n) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public void visit(String nn, Object v) {
                                String t = v instanceof Type ty ? ty.getInternalName() : String.valueOf(v).replace('.', '/');
                                if (owned.test(t)) mixinTargets.add(t);
                            }
                        };
                    }
                };
            }

            @Override public FieldVisitor visitField(int acc, String name, String desc, String sig, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        if (ad.equals("Lorg/spongepowered/asm/mixin/Shadow;"))
                            for (String t : mixinTargets) field(self + " @Shadow", Opcodes.GETFIELD, t, name, desc, true, modSupers);
                        return null;
                    }
                };
            }

            @Override public MethodVisitor visitMethod(int acc, String mname, String mdesc, String sig, String[] ex) {
                String where = self + "." + mname;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        if (mixinTargets.isEmpty()) return null;
                        if (ad.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                            for (String t : mixinTargets) method(where + " @Shadow", (acc & Opcodes.ACC_STATIC) != 0 ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, t, mname, mdesc, true, modSupers);
                            return null;
                        }
                        if (ad.endsWith("/Accessor;") || ad.endsWith("/Invoker;")) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                String value;
                                @Override public void visit(String n, Object v) { if (n.equals("value")) value = (String) v; }
                                @Override public void visitEnd() {
                                    for (String t : mixinTargets) accessorOrInvoker(where, ad.endsWith("/Accessor;"), t, mname, mdesc, value);
                                }
                            };
                        }
                        // @Inject / @Redirect / @ModifyArg ...: method = "name" or "name(desc)", at target = "Lowner;name(desc)"
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override public AnnotationVisitor visitArray(String n) {
                                if (n.equals("method")) return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override public void visit(String nn, Object v) { for (String t : mixinTargets) injectTarget(where, t, (String) v); }
                                };
                                if (n.equals("at")) return atVisitor(where);
                                return null;
                            }
                            @Override public AnnotationVisitor visitAnnotation(String n, String d) { return n.equals("at") ? atVisitor(where).visitAnnotation(null, d) : null; }
                        };
                    }

                    @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        if (owned.test(owner)) method(where, op, owner, name, desc, false, modSupers);
                    }

                    @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
                        if (owned.test(owner)) field(where, op, owner, name, desc, false, modSupers);
                    }

                    @Override public void visitTypeInsn(int op, String type) {
                        if (!owned.test(type)) return;
                        checked++;
                        Cls c = iroh.get(type);
                        if (c == null) problem(where, "uses missing class " + type);
                        else if (op == Opcodes.NEW && (c.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0)
                            problem(where, "NEW of abstract " + type);
                    }

                    @Override public void visitLdcInsn(Object v) {
                        if (v instanceof Type t && t.getSort() == Type.OBJECT && owned.test(t.getInternalName())) {
                            checked++;
                            if (!iroh.containsKey(t.getInternalName())) problem(where, "class literal of missing " + t.getInternalName());
                        }
                    }

                    @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                        for (Object a : args) if (a instanceof Handle h && owned.test(h.getOwner())) {
                            int op = switch (h.getTag()) {
                                case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                                case Opcodes.H_NEWINVOKESPECIAL, Opcodes.H_INVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                                case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                                default -> Opcodes.INVOKEVIRTUAL;
                            };
                            method(where + " (method ref)", op, h.getOwner(), h.getName(), h.getDesc(), false, modSupers);
                        }
                    }
                };
            }
        }, 0);
    }

    private AnnotationVisitor atVisitor(String where) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String n, String d) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    String value, target;
                    @Override public void visit(String nn, Object v) { if (nn.equals("value")) value = (String) v; if (nn.equals("target")) target = (String) v; }
                    @Override public void visitEnd() {
                        if (target == null || !target.startsWith("L")) return;
                        int semi = target.indexOf(';');
                        String owner = target.substring(1, semi), rest = target.substring(semi + 1);
                        if (!owned.test(owner)) return;
                        if (rest.contains("(")) {
                            int p = rest.indexOf('(');
                            method(where + " @At", Opcodes.INVOKEVIRTUAL, owner, rest.substring(0, p), rest.substring(p), true, Map.of());
                        } else {
                            int c = rest.indexOf(':');
                            field(where + " @At", Opcodes.GETFIELD, owner, rest.substring(0, c), rest.substring(c + 1), true, Map.of());
                        }
                    }
                };
            }
        };
    }

    private void injectTarget(String where, String target, String spec) {
        checked++;
        Cls c = iroh.get(target);
        if (c == null) { problem(where, "mixin target missing " + target); return; }
        int p = spec.indexOf('(');
        String name = p < 0 ? spec : spec.substring(0, p);
        boolean found = c.methods.keySet().stream().anyMatch(k -> p < 0 ? k.startsWith(name + "(") : k.equals(spec));
        if (!found) problem(where, "inject target " + target + "." + spec + " not in Iroh");
    }

    private void accessorOrInvoker(String where, boolean accessor, String target, String mname, String mdesc, String value) {
        checked++;
        Cls c = iroh.get(target);
        if (c == null) { problem(where, "mixin target missing " + target); return; }
        Type mt = Type.getMethodType(mdesc);
        if (accessor) {
            String field = value != null ? value : decap(mname.replaceFirst("^(get|set|is)", ""));
            String fdesc = mt.getArgumentTypes().length == 1 ? mt.getArgumentTypes()[0].getDescriptor() : mt.getReturnType().getDescriptor();
            if (findField(target, field, fdesc) == null) problem(where, "@Accessor field " + target + "." + field + ":" + fdesc + " not in Iroh");
        } else {
            String m = value != null ? value : decap(mname.replaceFirst("^(call|invoke)", ""));
            if (findMethod(target, m, mdesc) == null) problem(where, "@Invoker method " + target + "." + m + mdesc + " not in Iroh");
        }
    }

    private static String decap(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }

    private void method(String where, int op, String owner, String name, String desc, boolean mixinSide, Map<String, String> modSupers) {
        checked++;
        if (!iroh.containsKey(owner)) { problem(where, "class missing " + owner + " (for " + name + desc + ")"); return; }
        Object[] found = findMethod(owner, name, desc);
        if (found == null) { problem(where, "method missing " + owner + "." + name + desc); return; }
        int acc = (Integer) found[1];
        boolean isStatic = (acc & Opcodes.ACC_STATIC) != 0;
        if (op == Opcodes.INVOKESTATIC && !isStatic) problem(where, "invokestatic of instance method " + owner + "." + name + desc);
        if (op != Opcodes.INVOKESTATIC && isStatic && !mixinSide) problem(where, "instance call of static method " + owner + "." + name + desc);
        if (!mixinSide) access(where, acc, (String) found[0], name + desc, modSupers);
    }

    private void field(String where, int op, String owner, String name, String desc, boolean mixinSide, Map<String, String> modSupers) {
        checked++;
        if (!iroh.containsKey(owner)) { problem(where, "class missing " + owner + " (for field " + name + ")"); return; }
        Object[] found = findField(owner, name, desc);
        if (found == null) { problem(where, "field missing " + owner + "." + name + ":" + desc); return; }
        int acc = (Integer) found[1];
        boolean isStatic = (acc & Opcodes.ACC_STATIC) != 0;
        boolean wantStatic = op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC;
        if (!mixinSide && wantStatic != isStatic) problem(where, "static mismatch on field " + owner + "." + name);
        if (!mixinSide && (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC) && (acc & Opcodes.ACC_FINAL) != 0)
            problem(where, "writes final field " + owner + "." + name);
        if (!mixinSide) access(where, acc, (String) found[0], name, modSupers);
    }

    private void access(String where, int acc, String declaring, String member, Map<String, String> modSupers) {
        if ((acc & Opcodes.ACC_PUBLIC) != 0) return;
        if ((acc & Opcodes.ACC_PROTECTED) != 0) {
            String cls = where.contains(".") ? where.substring(0, where.indexOf('.')) : where;
            for (String s = modSupers.get(cls); s != null; s = modSupers.getOrDefault(s, iroh.containsKey(s) ? iroh.get(s).sup : null))
                if (s.equals(declaring)) return;
        }
        problem(where, "not accessible: " + declaring + "." + member + " (access 0x" + Integer.toHexString(acc) + ")");
    }

    /** {declaringClass, access} or null; superclasses first, then interfaces, as JVM resolution. */
    private Object[] findMethod(String owner, String name, String desc) {
        for (String c = owner; c != null && iroh.containsKey(c); c = iroh.get(c).sup) {
            Integer a = iroh.get(c).methods.get(name + desc);
            if (a != null) return new Object[]{c, a};
        }
        Deque<String> q = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            Cls c = iroh.get(q.poll());
            if (c == null || !seen.add(c.name)) continue;
            Integer a = c.methods.get(name + desc);
            if (a != null && (c.access & Opcodes.ACC_INTERFACE) != 0) return new Object[]{c.name, a};
            q.addAll(Arrays.asList(c.itfs));
            if (c.sup != null) q.add(c.sup);
        }
        return null;
    }

    private Object[] findField(String owner, String name, String desc) {
        for (String c = owner; c != null && iroh.containsKey(c); c = iroh.get(c).sup) {
            Integer a = iroh.get(c).fields.get(name + ":" + desc);
            if (a != null) return new Object[]{c, a};
        }
        return null;
    }

    private void problem(String where, String what) {
        problems.add(new Problem(where, what));
    }
}
