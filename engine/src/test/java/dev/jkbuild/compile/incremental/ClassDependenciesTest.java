// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClassDependenciesTest {

    @Test
    void captures_references_from_signatures_and_method_bodies(@TempDir Path dir) throws IOException {
        Map<String, byte[]> classes = JavacFixture.compile(dir, Map.of(
                "a.B", "package a; public class B { public C make() { return new C(); } }",
                "a.C", "package a; public class C { public void hi() {} }",
                "a.D", "package a; public class D { public static int v() { return 7; } }",
                "a.A", """
                        package a;
                        public class A {
                            private B field;                       // field type → B
                            public C use(D ignored) {              // param → D, return → C
                                D.v();                             // body call → D
                                return field.make();               // body call → C
                            }
                        }
                        """));

        Set<String> deps = ClassDependencies.referencedTypes(classes.get("a.A"));

        assertThat(deps).contains("a/B", "a/C", "a/D");   // internal names
        assertThat(deps).doesNotContain("a/A");           // excludes self
    }

    @Test
    void captures_a_dependency_used_only_inside_a_method_body(@TempDir Path dir) throws IOException {
        Map<String, byte[]> classes = JavacFixture.compile(dir, Map.of(
                "a.Helper", "package a; public class Helper { public static String greet() { return \"hi\"; } }",
                "a.OnlyBody", """
                        package a;
                        public class OnlyBody {
                            public String run() { return Helper.greet(); }  // sole reference is in the body
                        }
                        """));

        assertThat(ClassDependencies.referencedTypes(classes.get("a.OnlyBody")))
                .contains("a/Helper");
    }
}
