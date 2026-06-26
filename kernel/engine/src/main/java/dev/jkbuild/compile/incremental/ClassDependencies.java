// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

/**
 * Extracts the set of other classes a compiled class references — the forward
 * edges of the type-dependency graph the incremental planner walks (in reverse)
 * to find what a change affects.
 *
 * <p>Implemented by running the class through {@link ClassRemapper} with a
 * {@link Remapper} that records every internal class name ASM resolves during a
 * full traversal — superclass/interfaces, field/method descriptors, exceptions,
 * generic signatures, annotations, and every type/field/method reference in
 * method bodies. This is comprehensive by construction (over-approximating is
 * safe; <em>missing</em> an edge would cause a stale build), so we don't
 * hand-roll the visitor surface.
 *
 * <p>Returns raw <em>internal</em> names (e.g. {@code com/acme/Foo$Bar}),
 * excluding the class's own name. Callers filter to the project's own classes;
 * JDK/dependency references pass through and are simply ignored downstream.
 */
public final class ClassDependencies {

    private ClassDependencies() {}

    public static Set<String> referencedTypes(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        Set<String> names = new TreeSet<>();
        Remapper recorder = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                if (internalName != null) names.add(internalName);
                return internalName;
            }
        };
        // Full traversal (no SKIP_CODE) so method-body references are captured.
        // The downstream must be a visitor that returns NON-null sub-visitors,
        // or ClassReader skips method bodies — a ClassNode does (its visitMethod
        // returns a MethodNode), so ClassRemapper drives instruction remapping
        // and the recorder sees every body reference.
        reader.accept(new ClassRemapper(new ClassNode(), recorder), 0);
        names.remove(reader.getClassName());
        return names;
    }
}
