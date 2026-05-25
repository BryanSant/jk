// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.lock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LockfileRoundTripTest {

    @Test
    void empty_lockfile_renders_deterministically() {
        Lockfile lock = Lockfile.empty("0.1.0-SNAPSHOT");
        String rendered = LockfileWriter.render(lock);

        assertThat(rendered).isEqualTo("""
                version = 1
                generated-by = "jk 0.1.0-SNAPSHOT"
                resolution-algorithm = "pubgrub-v1"
                """);
    }

    @Test
    void packages_render_sorted_by_name_then_version() {
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk 0.1.0-SNAPSHOT",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        new Lockfile.Package(
                                "com.b:beta",
                                "1.0.0",
                                "central+https://repo.maven.apache.org/maven2/",
                                "sha256:bbbb",
                                null,
                                List.of()),
                        new Lockfile.Package(
                                "com.a:alpha",
                                "2.0.0",
                                "central+https://repo.maven.apache.org/maven2/",
                                "sha256:aaaa",
                                null,
                                List.of("com.b:beta@1.0.0"))));

        String rendered = LockfileWriter.render(lock);
        int alphaIdx = rendered.indexOf("com.a:alpha");
        int betaIdx = rendered.indexOf("com.b:beta");
        assertThat(alphaIdx).isPositive();
        assertThat(betaIdx).isGreaterThan(alphaIdx);
    }

    @Test
    void round_trip_preserves_content() {
        Lockfile original = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk 0.1.0-SNAPSHOT",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Package(
                        "com.example:widget",
                        "1.2.3",
                        "central+https://repo.maven.apache.org/maven2/",
                        "sha256:0123abcd",
                        null,
                        List.of("com.example:dep@2.0.0"))));

        String rendered = LockfileWriter.render(original);
        Lockfile parsed = LockfileReader.parse(rendered);

        assertThat(parsed.version()).isEqualTo(original.version());
        assertThat(parsed.generatedBy()).isEqualTo(original.generatedBy());
        assertThat(parsed.resolutionAlgorithm()).isEqualTo(original.resolutionAlgorithm());
        assertThat(parsed.packages()).hasSize(1);
        assertThat(parsed.packages().getFirst().name()).isEqualTo("com.example:widget");
        assertThat(parsed.packages().getFirst().deps()).containsExactly("com.example:dep@2.0.0");
    }
}
