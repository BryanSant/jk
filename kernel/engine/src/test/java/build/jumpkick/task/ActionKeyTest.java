// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.compile.CompileRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionKeyTest {

    @Test
    void same_inputs_produce_same_key(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();

        String a = ActionKey.forJavac("compile-main", request, "0.1.0");
        String b = ActionKey.forJavac("compile-main", request, "0.1.0");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void editing_a_source_changes_the_key(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
        String before = ActionKey.forJavac("compile-main", request, "0.1.0");

        Files.writeString(src, "class Hello { void f() {} }");
        String after = ActionKey.forJavac("compile-main", request, "0.1.0");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void task_id_part_of_key(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
        String a = ActionKey.forJavac("compile-main", request, "0.1.0");
        String b = ActionKey.forJavac("compile-test", request, "0.1.0");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void release_part_of_key(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.java");
        Files.writeString(src, "class Hello {}");
        CompileRequest base = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(25)
                .build();
        CompileRequest other = CompileRequest.builder()
                .sources(List.of(src))
                .outputDir(tempDir.resolve("out"))
                .release(21)
                .build();
        assertThat(ActionKey.forJavac("compile-main", base, "0.1.0"))
                .isNotEqualTo(ActionKey.forJavac("compile-main", other, "0.1.0"));
    }

    @Test
    void qualified_task_id_differs_per_module_and_is_stable() {
        Path a = Path.of("/work/projA/target/build/classes/main");
        Path b = Path.of("/work/projB/target/build/classes/main");
        String qa = ActionKey.qualifiedTaskId("compile-main", a);
        String qb = ActionKey.qualifiedTaskId("compile-main", b);

        assertThat(qa).startsWith("compile-main@");
        assertThat(qa).isNotEqualTo(qb); // different modules → no pointer collision
        assertThat(qa).isEqualTo(ActionKey.qualifiedTaskId("compile-main", a)); // stable
        // compile-main vs compile-test in the same module stay distinct too.
        assertThat(qa).isNotEqualTo(ActionKey.qualifiedTaskId("compile-test", a));
    }
}
