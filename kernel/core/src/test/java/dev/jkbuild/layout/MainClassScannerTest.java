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

    @Test
    void non_public_or_wrong_signature_mains_are_ignored(@TempDir Path dir) throws Exception {
        compile(dir, "NotIt", "public class NotIt { static void main(String[] a) {} }"); // not public
        compile(dir, "AlsoNot", "public class AlsoNot { public void main(String[] a) {} }"); // not static
        compile(dir, "WrongSig", "public class WrongSig { public static void main(String a) {} }");
        assertThat(MainClassScanner.scan(dir)).isEmpty();
        assertThatThrownBy(() -> MainClassScanner.scanUnique(dir))
                .hasMessageContaining("[application] main");
    }

    @Test
    void several_mains_error_listing_the_candidates(@TempDir Path dir) throws Exception {
        compile(dir, "A", "public class A { public static void main(String[] a) {} }");
        compile(dir, "B", "public class B { public static void main(String[] a) {} }");
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
