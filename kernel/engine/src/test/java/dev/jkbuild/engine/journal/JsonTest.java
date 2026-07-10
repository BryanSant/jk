// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundtrips_a_fully_populated_record() {
        BuildRecord original = new BuildRecord(
                "20260710T143022417-3f9a", 1, "build", "/proj \"quoted\"", "com.example:app",
                1000, 4000, 3000, false, false, 1, "9.9-test",
                new BuildRecord.Tests(42, 40, 1, 1),
                List.of(new BuildRecord.Module("com.example:app", "/proj", false, 1, 3000)),
                List.of(new BuildRecord.Phase("compile", "SUCCESS", 800),
                        new BuildRecord.Phase("test", "FAIL", 1200)),
                List.of(new BuildRecord.Diag("error", "test", "JUNIT", "expected 1 but was 2\nline two",
                        "com.example.AppTest#adds", "org.opentest4j.AssertionFailedError")));

        BuildRecord back = Json.read(Json.write(original));

        assertThat(back.id()).isEqualTo(original.id());
        assertThat(back.kind()).isEqualTo("build");
        assertThat(back.dir()).isEqualTo("/proj \"quoted\"");
        assertThat(back.coord()).isEqualTo("com.example:app");
        assertThat(back.finishedAt()).isEqualTo(4000);
        assertThat(back.success()).isFalse();
        assertThat(back.exitCode()).isEqualTo(1);
        assertThat(back.tests().total()).isEqualTo(42);
        assertThat(back.tests().failed()).isEqualTo(1);
        assertThat(back.modules()).hasSize(1);
        assertThat(back.modules().get(0).coord()).isEqualTo("com.example:app");
        assertThat(back.phases()).extracting(BuildRecord.Phase::status).containsExactly("SUCCESS", "FAIL");
        assertThat(back.diagnostics()).hasSize(1);
        assertThat(back.diagnostics().get(0).message()).isEqualTo("expected 1 but was 2\nline two");
        assertThat(back.diagnostics().get(0).exceptionClass()).isEqualTo("org.opentest4j.AssertionFailedError");
    }

    @Test
    void roundtrips_a_minimal_record_with_null_coord_and_no_tests() {
        BuildRecord original = new BuildRecord(
                "20260710T143022417-0000", 1, "test", "/p", null,
                0, 5, 5, true, true, 0, "9.9",
                null, List.of(), List.of(), List.of());
        BuildRecord back = Json.read(Json.write(original));
        assertThat(back.coord()).isNull();
        assertThat(back.tests()).isNull();
        assertThat(back.cancelled()).isTrue();
        assertThat(back.modules()).isEmpty();
        assertThat(back.phases()).isEmpty();
        assertThat(back.diagnostics()).isEmpty();
    }
}
