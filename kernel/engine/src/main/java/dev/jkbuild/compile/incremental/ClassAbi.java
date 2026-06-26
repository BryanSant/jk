// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import dev.jkbuild.util.Hashing;
import java.util.Arrays;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Computes a stable hash of a compiled class's <em>ABI</em> — the surface a dependent compiles
 * against: class modifiers/super/interfaces and every non-private field and method signature (plus
 * {@code static final} constant values, which javac inlines into dependents). Method bodies,
 * private members, debug info, and the class-file version are excluded, so a body-only edit leaves
 * the ABI hash unchanged and dependents need not recompile.
 *
 * <p>Annotations are intentionally <em>not</em> part of the ABI for v1: an annotation-only change
 * on a type doesn't change how a caller compiles against it. (Revisit if/when annotation-driven
 * dependents need finer tracking.)
 */
public final class ClassAbi {

    private static final int ASM = Opcodes.ASM9;

    private ClassAbi() {}

    /** Hex SHA-256 of the class's ABI. Two classes with the same public surface hash equally. */
    public static String hash(byte[] classBytes) {
        StringBuilder sb = new StringBuilder();
        new ClassReader(classBytes)
                .accept(new AbiVisitor(sb), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * True if the class exposes a non-private {@code static final} field with a constant value. javac
     * inlines such constants into dependents, leaving no bytecode edge, so a change to one must be
     * handled conservatively.
     */
    public static boolean definesInlinableConstant(byte[] classBytes) {
        boolean[] found = {false};
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(ASM) {
                            @Override
                            public FieldVisitor visitField(
                                    int access, String name, String descriptor, String signature, Object value) {
                                if (value != null
                                        && (access & Opcodes.ACC_STATIC) != 0
                                        && (access & Opcodes.ACC_FINAL) != 0
                                        && (access & Opcodes.ACC_PRIVATE) == 0) {
                                    found[0] = true;
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /** Collects an order-independent canonical rendering of the ABI into {@code sb}. */
    private static final class AbiVisitor extends ClassVisitor {
        // API-relevant class modifiers (exclude ACC_SUPER and the like, which aren't ABI).
        private static final int CLASS_ACCESS_MASK = Opcodes.ACC_PUBLIC
                | Opcodes.ACC_PROTECTED
                | Opcodes.ACC_PRIVATE
                | Opcodes.ACC_FINAL
                | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_INTERFACE
                | Opcodes.ACC_ENUM
                | Opcodes.ACC_ANNOTATION
                | Opcodes.ACC_STATIC;

        private final StringBuilder out;
        private final TreeSet<String> members = new TreeSet<>();

        AbiVisitor(StringBuilder out) {
            super(ASM);
            this.out = out;
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            String ifaces = interfaces == null ? "" : String.join(",", sorted(interfaces));
            out.append("C ")
                    .append(access & CLASS_ACCESS_MASK)
                    .append(' ')
                    .append(name)
                    .append(' ')
                    .append(nz(signature))
                    .append(' ')
                    .append(nz(superName))
                    .append(" [")
                    .append(ifaces)
                    .append("]\n");
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if ((access & Opcodes.ACC_PRIVATE) != 0) return null;
            members.add("F "
                    + access
                    + ' '
                    + name
                    + ' '
                    + descriptor
                    + ' '
                    + nz(signature)
                    + ' '
                    + (value == null ? "" : value));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_PRIVATE) != 0) return null;
            String ex = exceptions == null ? "" : String.join(",", sorted(exceptions));
            members.add("M " + access + ' ' + name + ' ' + descriptor + ' ' + nz(signature) + " [" + ex + "]");
            return null;
        }

        @Override
        public void visitEnd() {
            for (String m : members) out.append(m).append('\n');
        }

        private static String[] sorted(String[] a) {
            String[] c = a.clone();
            Arrays.sort(c);
            return c;
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }
}
