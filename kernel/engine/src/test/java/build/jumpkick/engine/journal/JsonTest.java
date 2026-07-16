// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundtrips_a_fully_populated_record() {
        BuildRecord original = new BuildRecord(
                "20260710T143022417-3f9a", 412, 2, "build", "/proj \"quoted\"", "com.example:app",
                1000, 4000, 3000, false, false, 1, "9.9-test",
                new BuildRecord.Tests(42, 40, 1, 1),
                List.of(new BuildRecord.Module("com.example:app", "/proj", false, 1, 3000,
                        List.of(new BuildRecord.Step("compile", "compile", "SUCCESS", 800),
                                new BuildRecord.Step("test", "test", "FAIL", 1200)))),
                List.of(new BuildRecord.Step("compile", "compile", "SUCCESS", 800),
                        new BuildRecord.Step("test", "test", "FAIL", 1200)),
                List.of(new BuildRecord.Diag("error", "/proj", "test", "JUNIT", "expected 1 but was 2\nline two",
                        "com.example.AppTest#adds", "org.opentest4j.AssertionFailedError")),
                "web", "abc1234",
                new BuildRecord.CacheBenefit(9000, 6000, 3, 4));

        BuildRecord back = Json.read(Json.write(original));

        assertThat(back.id()).isEqualTo(original.id());
        assertThat(back.buildNumber()).isEqualTo(412);
        assertThat(back.trigger()).isEqualTo("web");
        assertThat(back.commit()).isEqualTo("abc1234");
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
        assertThat(back.modules().get(0).steps()).extracting(BuildRecord.Step::name).containsExactly("compile", "test");
        assertThat(back.steps()).extracting(BuildRecord.Step::status).containsExactly("SUCCESS", "FAIL");
        assertThat(back.steps()).extracting(BuildRecord.Step::phase).containsExactly("compile", "test");
        assertThat(back.diagnostics()).hasSize(1);
        assertThat(back.diagnostics().get(0).dir()).isEqualTo("/proj");
        assertThat(back.diagnostics().get(0).message()).isEqualTo("expected 1 but was 2\nline two");
        assertThat(back.diagnostics().get(0).exceptionClass()).isEqualTo("org.opentest4j.AssertionFailedError");
        assertThat(back.benefit()).isNotNull();
        assertThat(back.benefit().estimatedUncachedMillis()).isEqualTo(9000);
        assertThat(back.benefit().savedMillis()).isEqualTo(6000);
        assertThat(back.benefit().coveredSkips()).isEqualTo(3);
        assertThat(back.benefit().totalSkips()).isEqualTo(4);
    }

    @Test
    void roundtrips_a_minimal_record_with_null_coord_and_no_tests() {
        BuildRecord original = new BuildRecord(
                "20260710T143022417-0000", 0, 1, "test", "/p", null,
                0, 5, 5, true, true, 0, "9.9",
                null, List.of(), List.of(), List.of(), null, null, null);
        BuildRecord back = Json.read(Json.write(original));
        assertThat(back.coord()).isNull();
        assertThat(back.tests()).isNull();
        assertThat(back.cancelled()).isTrue();
        assertThat(back.modules()).isEmpty();
        assertThat(back.steps()).isEmpty();
        assertThat(back.diagnostics()).isEmpty();
        assertThat(back.benefit()).isNull();
    }

    @Test
    void reads_an_older_record_that_predates_the_benefit_field() {
        // A pre-benefit record.json (no "benefit" key) must still parse, with benefit == null.
        String legacy = "{\"id\":\"old-1\",\"buildNumber\":7,\"schema\":2,\"kind\":\"build\","
                + "\"dir\":\"/p\",\"coord\":\"g:a\",\"startedAt\":0,\"finishedAt\":10,\"millis\":10,"
                + "\"success\":true,\"cancelled\":false,\"exitCode\":0,\"jkVersion\":\"9\","
                + "\"tests\":null,\"modules\":[],\"steps\":[],\"diagnostics\":[],"
                + "\"trigger\":\"cli\",\"commit\":\"deadbee\"}";
        BuildRecord back = Json.read(legacy);
        assertThat(back.id()).isEqualTo("old-1");
        assertThat(back.success()).isTrue();
        assertThat(back.benefit()).isNull();
    }
}
