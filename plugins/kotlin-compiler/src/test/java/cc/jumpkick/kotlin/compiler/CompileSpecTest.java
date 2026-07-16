// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The kotlin plugin's spec decode ({@link CompileSpec#from}) + argument building over the unified
 * NDJSON plugin wire — no Build Tools API runtime required.
 */
class CompileSpecTest {

    @Test
    void parses_all_keys_and_repeatables(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of(
                                "classesDir",
                                Path.of("/tmp/out"),
                                "workdir",
                                Path.of("/tmp/work"),
                                "snapshotDir",
                                Path.of("/tmp/snaps")))
                        .configString("jvmTarget", "21")
                        .configString("moduleName", "main")
                        .configString("languageVersion", "2.4")
                        .configString("apiVersion", "2.4")
                        .source(Path.of("/src/A.kt"))
                        .source(Path.of("/src/B.kt"))
                        .cp(Path.of("/libs/stdlib.jar"), PluginProtocol.ROLE_COMPILE)
                        .cp(Path.of("/libs/dep.jar"), PluginProtocol.ROLE_COMPILE)
                        .cp(Path.of("/build/classes/other"), PluginProtocol.ROLE_FRIEND)
                        .arg("-no-stdlib")
                        .arg("-Xfoo"));

        assertThat(s.outputDir).isEqualTo(new File("/tmp/out"));
        assertThat(s.workingDir).isEqualTo(new File("/tmp/work"));
        assertThat(s.snapshotDir).isEqualTo(new File("/tmp/snaps"));
        assertThat(s.incremental()).isTrue();
        assertThat(s.jvmTarget).isEqualTo("21");
        assertThat(s.moduleName).isEqualTo("main");
        assertThat(s.languageVersion).isEqualTo("2.4");
        assertThat(s.apiVersion).isEqualTo("2.4");
        assertThat(s.sources).containsExactly(new File("/src/A.kt"), new File("/src/B.kt"));
        assertThat(s.classpath).containsExactly(new File("/libs/stdlib.jar"), new File("/libs/dep.jar"));
        assertThat(s.friendPaths).containsExactly(new File("/build/classes/other"));
        assertThat(s.extraArgs).containsExactly("-no-stdlib", "-Xfoo");
    }

    @Test
    void absent_workdir_means_non_incremental(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of("classesDir", Path.of("/tmp/out")))
                        .configString("jvmTarget", "21")
                        .source(Path.of("/src/A.kt")));
        assertThat(s.incremental()).isFalse();
    }

    @Test
    void value_may_contain_spaces(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of("classesDir", Path.of("/tmp/with space/out")))
                        .configString("jvmTarget", "21")
                        .source(Path.of("/tmp/with space/A.kt")));
        assertThat(s.outputDir).isEqualTo(new File("/tmp/with space/out"));
        assertThat(s.sources).containsExactly(new File("/tmp/with space/A.kt"));
    }

    @Test
    void rejects_missing_required_keys(@TempDir Path dir) throws IOException {
        assertThatThrownBy(() -> parse(
                        dir,
                        new SpecWriter()
                                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                                .configString("jvmTarget", "21")
                                .source(Path.of("/a.kt"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classesDir");

        assertThatThrownBy(() -> parse(
                        dir,
                        new SpecWriter()
                                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                                .layout(Map.of("classesDir", Path.of("/o")))
                                .configString("jvmTarget", "21")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void build_args_for_incremental_carry_fir_flag_and_options(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of("classesDir", Path.of("/o"), "workdir", Path.of("/w")))
                        .configString("jvmTarget", "21")
                        .configString("moduleName", "main")
                        .configString("languageVersion", "2.4")
                        .source(Path.of("/a.kt"))
                        .cp(Path.of("/libs/x.jar"), PluginProtocol.ROLE_COMPILE)
                        .cp(Path.of("/f1"), PluginProtocol.ROLE_FRIEND)
                        .cp(Path.of("/f2"), PluginProtocol.ROLE_FRIEND)
                        .arg("-no-stdlib"));

        List<String> args = KotlinCompiler.buildArgs(s);

        assertThat(args).doesNotContain("-d"); // destination is a builder parameter, never a raw arg
        assertThat(args).containsSequence("-jvm-target", "21");
        assertThat(args).containsSequence("-module-name", "main");
        assertThat(args).containsSequence("-language-version", "2.4");
        assertThat(args).containsSequence("-classpath", "/libs/x.jar");
        assertThat(args).contains("-Xfriend-paths=/f1,/f2");
        assertThat(args).contains("-Xuse-fir-ic"); // required by the FIR IC runner
        assertThat(args).endsWith("-no-stdlib"); // free ARGs appended verbatim
    }

    @Test
    void build_args_for_full_compile_omit_fir_flag(@TempDir Path dir) throws IOException {
        CompileSpec s = parse(
                dir,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                        .layout(Map.of("classesDir", Path.of("/o")))
                        .configString("jvmTarget", "21")
                        .source(Path.of("/a.kt")));
        assertThat(KotlinCompiler.buildArgs(s)).doesNotContain("-Xuse-fir-ic");
    }

    private static CompileSpec parse(Path dir, SpecWriter sw) throws IOException {
        Path f = dir.resolve("spec-" + System.nanoTime() + ".spec");
        Files.write(f, sw.lines());
        return CompileSpec.from(PluginSpec.read(f));
    }
}
