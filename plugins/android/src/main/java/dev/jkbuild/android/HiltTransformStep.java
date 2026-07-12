// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.StepExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The {@code android-hilt-transform} step ({@code [android] hilt = true}): Hilt's
 * unmodified-source contract — a class annotated {@code @AndroidEntryPoint} /
 * {@code @HiltAndroidApp} keeps its natural {@code extends} in source, and this transform
 * rewrites its superclass to the KSP-generated {@code Hilt_<Name>} base (which itself extends
 * the original superclass). Exactly what AGP's Hilt plugin does with its bytecode transform;
 * here it rides the generic classes-transform SPI: the output dir REPLACES the module's classes
 * dir, so dex/packaging consume the rewritten hierarchy.
 *
 * <p>Every class copies through; only annotated classes whose superclass isn't already a
 * {@code Hilt_*} base rewrite (the plugin-less spelling keeps working unchanged). The rewrite is
 * the superclass reference plus the {@code invokespecial <init>} owner in constructors — method
 * bodies otherwise keep their shape, so no frame recomputation is needed. Recorded gap:
 * {@code @AndroidEntryPoint} BroadcastReceivers additionally need an injected
 * {@code onReceive} super-call (AGP's transform special-cases them); receivers keep the
 * plugin-less spelling until demanded.
 */
final class HiltTransformStep {

    private static final java.util.Set<String> HILT_ANNOTATIONS = java.util.Set.of(
            "Ldagger/hilt/android/AndroidEntryPoint;", "Ldagger/hilt/android/HiltAndroidApp;");

    private HiltTransformStep() {}

    static void run(StepExec exec) throws Exception {
        Path in = exec.classesDir();
        Path out = exec.outputDir("classes");
        int[] rewritten = {0};
        try (Stream<Path> walk = Files.walk(in)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                Path target = out.resolve(in.relativize(file).toString());
                Files.createDirectories(target.getParent());
                if (file.getFileName().toString().endsWith(".class")) {
                    byte[] bytes = Files.readAllBytes(file);
                    byte[] transformed = maybeRewrite(bytes, in);
                    if (transformed != null) rewritten[0]++;
                    Files.write(target, transformed != null ? transformed : bytes);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        exec.label("hilt transform (" + rewritten[0] + (rewritten[0] == 1 ? " class)" : " classes)"));
    }

    /** The rewritten class bytes, or null when the class doesn't rewrite (the common case). */
    private static byte[] maybeRewrite(byte[] bytes, Path classesDir) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        Probe probe = new Probe();
        reader.accept(probe, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!probe.annotated) return null;
        String name = reader.getClassName();
        String oldSuper = reader.getSuperName();
        String simple = name.substring(name.lastIndexOf('/') + 1);
        if (simple.contains("$")) return null; // nested entry points keep the plugin-less spelling
        String pkg = name.lastIndexOf('/') >= 0 ? name.substring(0, name.lastIndexOf('/') + 1) : "";
        if (oldSuper.substring(oldSuper.lastIndexOf('/') + 1).startsWith("Hilt_")) {
            return null; // already extends the generated base (plugin-less mode)
        }
        String newSuper = pkg + "Hilt_" + simple;
        if (!Files.isRegularFile(classesDir.resolve(newSuper + ".class"))) {
            throw new IllegalStateException("Hilt transform: " + name.replace('/', '.')
                    + " is annotated for injection but the generated base " + newSuper.replace('/', '.')
                    + " is missing — did the Hilt KSP processor run? (declare hilt-android-compiler"
                    + " under [processor-dependencies])");
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new SuperclassRewriter(writer, oldSuper, newSuper), 0);
        return writer.toByteArray();
    }

    /** Pass 1: does the class carry a Hilt entry-point annotation? (CLASS retention → invisible.) */
    private static final class Probe extends ClassVisitor {
        boolean annotated;

        Probe() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (HILT_ANNOTATIONS.contains(descriptor)) annotated = true;
            return null;
        }
    }

    /** Pass 2: swap the superclass and re-own constructor {@code invokespecial <init>} calls. */
    private static final class SuperclassRewriter extends ClassVisitor {
        private final String oldSuper;
        private final String newSuper;

        SuperclassRewriter(ClassWriter writer, String oldSuper, String newSuper) {
            super(Opcodes.ASM9, writer);
            this.oldSuper = oldSuper;
            this.newSuper = newSuper;
        }

        @Override
        public void visit(int version, int access, String name, String sig, String superName, String[] ifaces) {
            super.visit(version, access, name, sig, newSuper, ifaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
            if (!"<init>".equals(name)) return mv;
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                    if (opcode == Opcodes.INVOKESPECIAL && owner.equals(oldSuper) && "<init>".equals(mName)) {
                        owner = newSuper;
                    }
                    super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                }
            };
        }
    }
}
