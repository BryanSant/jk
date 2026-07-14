// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainClassScannerTest {

    private static void compile(Path dir, String name, String source) throws Exception {
        Path src = dir.resolve(name + ".java");
        Files.writeString(src, source);
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, "-d", dir.toString(), src.toString());
        if (rc != 0) throw new IllegalStateException("compile failed for " + name);
    }

    @Test
    void finds_the_single_main_class(@TempDir Path dir) throws Exception {
        compile(dir, "App", "public class App { public static void main(String[] a) {} }");
        compile(dir, "Helper", "public class Helper { static int x() { return 1; } }");
        assertThat(MainClassScanner.scanUnique(dir)).isEqualTo("App");
    }

    // --- JEP 512 forms the launcher accepts (JLS §12.1.4) --------------------------------------

    @Test
    void accepts_instance_main_with_args(@TempDir Path dir) throws Exception {
        // Not static — legal since Java 25; the default ctor makes App instantiable.
        compile(dir, "App", "public class App { public void main(String[] a) {} }");
        assertThat(MainClassScanner.scanUnique(dir)).isEqualTo("App");
    }

    @Test
    void accepts_instance_main_no_args(@TempDir Path dir) throws Exception {
        compile(dir, "App", "public class App { void main() {} }"); // no args, package access, instance
        assertThat(MainClassScanner.scanUnique(dir)).isEqualTo("App");
    }

    @Test
    void accepts_static_main_no_args(@TempDir Path dir) throws Exception {
        compile(dir, "App", "public class App { static void main() {} }");
        assertThat(MainClassScanner.scanUnique(dir)).isEqualTo("App");
    }

    @Test
    void accepts_package_private_and_protected_and_varargs_mains(@TempDir Path dir) throws Exception {
        compile(dir, "Pkg", "public class Pkg { static void main(String[] a) {} }"); // package access
        compile(dir, "Var", "class Var { public static void main(String... a) {} }"); // varargs == String[]
        assertThat(MainClassScanner.scan(dir)).containsExactlyInAnyOrder("Pkg", "Var");
    }

    @Test
    void accepts_compact_source_file(@TempDir Path dir) throws Exception {
        // A compact source file: no class header, an instance `void main()`. javac emits an
        // implicitly-declared class named after the file with a generated no-arg constructor.
        compile(dir, "Script", "void main() { IO.println(\"hi\"); }");
        assertThat(MainClassScanner.scanUnique(dir)).isEqualTo("Script");
    }

    // --- forms that are still NOT launchable ---------------------------------------------------

    @Test
    void rejects_private_wrong_return_and_wrong_param_mains(@TempDir Path dir) throws Exception {
        compile(dir, "Priv", "public class Priv { private static void main(String[] a) {} }"); // private
        compile(dir, "IntRet", "public class IntRet { public static int main(String[] a) { return 0; } }"); // non-void
        compile(dir, "WrongSig", "public class WrongSig { public static void main(String a) {} }"); // String, not String[]
        assertThat(MainClassScanner.scan(dir)).isEmpty();
        assertThatThrownBy(() -> MainClassScanner.scanUnique(dir))
                .hasMessageContaining("[application] main");
    }

    @Test
    void rejects_instance_main_when_class_is_not_instantiable(@TempDir Path dir) throws Exception {
        // Only a private constructor → the launcher cannot `new` it, so an instance main doesn't count.
        compile(dir, "NoCtor", "public class NoCtor { private NoCtor() {} public void main(String[] a) {} }");
        // Abstract class → cannot be instantiated for an instance main.
        compile(dir, "Abstr", "public abstract class Abstr { public void main(String[] a) {} }");
        assertThat(MainClassScanner.scan(dir)).isEmpty();
    }

    @Test
    void static_main_counts_even_when_ctor_is_private(@TempDir Path dir) throws Exception {
        // A static main needs no instance, so the private ctor is irrelevant.
        compile(dir, "Util", "public class Util { private Util() {} public static void main(String[] a) {} }");
        assertThat(MainClassScanner.scanUnique(dir)).isEqualTo("Util");
    }

    @Test
    void several_mains_error_listing_the_candidates(@TempDir Path dir) throws Exception {
        compile(dir, "A", "public class A { public static void main(String[] a) {} }");
        compile(dir, "B", "public class B { void main() {} }"); // different form, still a candidate
        assertThatThrownBy(() -> MainClassScanner.scanUnique(dir))
                .hasMessageContaining("multiple main classes")
                .hasMessageContaining("A")
                .hasMessageContaining("B");
    }

    @Test
    void packaged_classes_report_dotted_names(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("com/example"));
        Path src = dir.resolve("com/example/Main.java");
        Files.writeString(src, "package com.example; public class Main { public static void main(String[] a) {} }");
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        compiler.run(null, null, null, "-d", dir.toString(), src.toString());
        assertThat(MainClassScanner.scanUnique(dir)).isEqualTo("com.example.Main");
    }
}
