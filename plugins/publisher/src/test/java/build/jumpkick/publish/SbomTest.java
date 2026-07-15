// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.publish;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.lock.Lockfile;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.Scope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SbomTest {

    private static final JkBuild PROJECT =
            new JkBuild(new JkBuild.Project("com.example", "widget", "1.0.0", 21), JkBuild.Dependencies.empty());

    @Test
    void cyclonedx_without_lockfile_lists_only_the_root() {
        String json = new String(Sbom.cyclonedx(PROJECT, null), StandardCharsets.UTF_8);
        assertThat(json).contains("\"bomFormat\":\"CycloneDX\"");
        assertThat(json).contains("\"specVersion\":\"1.6\"");
        assertThat(json).contains("\"purl\":\"pkg:maven/com.example/widget@1.0.0\"");
        assertThat(json).contains("\"components\":[]");
    }

    @Test
    void cyclonedx_with_lockfile_lists_resolved_deps() {
        Lockfile lock = new Lockfile(
                5,
                "jk test",
                "pubgrub-v1",
                List.of(new Lockfile.Artifact(
                        "com.example:lib",
                        "1.2.3",
                        "central+https://repo.maven.apache.org/maven2/",
                        "sha256:abcdef",
                        null,
                        List.of(Scope.MAIN),
                        List.of())));

        String json = new String(Sbom.cyclonedx(PROJECT, lock), StandardCharsets.UTF_8);
        assertThat(json).contains("\"purl\":\"pkg:maven/com.example/lib@1.2.3\"");
        assertThat(json).contains("\"alg\":\"SHA-256\"");
        assertThat(json).contains("\"content\":\"abcdef\"");
    }

    @Test
    void spdx_emits_root_package_and_describe_relation() {
        String json = new String(Sbom.spdx(PROJECT, null), StandardCharsets.UTF_8);
        assertThat(json).contains("\"spdxVersion\":\"SPDX-2.3\"");
        assertThat(json).contains("\"dataLicense\":\"CC0-1.0\"");
        assertThat(json).contains("\"SPDXID\":\"SPDXRef-DOCUMENT\"");
        assertThat(json).contains("\"documentDescribes\":[\"SPDXRef-Package-Root\"]");
        assertThat(json).contains("pkg:maven/com.example/widget@1.0.0");
    }

    @Test
    void spdx_with_lockfile_emits_one_package_per_dep() {
        Lockfile lock = new Lockfile(
                5,
                "jk test",
                "pubgrub-v1",
                List.of(
                        new Lockfile.Artifact(
                                "com.example:a",
                                "1.0.0",
                                "central+https://repo.maven.apache.org/maven2/",
                                "sha256:aaaa",
                                null,
                                List.of(Scope.MAIN),
                                List.of()),
                        new Lockfile.Artifact(
                                "com.example:b",
                                "2.0.0",
                                "central+https://repo.maven.apache.org/maven2/",
                                "sha256:bbbb",
                                null,
                                List.of(Scope.MAIN),
                                List.of())));

        String json = new String(Sbom.spdx(PROJECT, lock), StandardCharsets.UTF_8);
        assertThat(json).contains("pkg:maven/com.example/a@1.0.0");
        assertThat(json).contains("pkg:maven/com.example/b@2.0.0");
        assertThat(json).contains("\"algorithm\":\"SHA256\"");
        assertThat(json).contains("\"checksumValue\":\"aaaa\"");
        assertThat(json).contains("\"checksumValue\":\"bbbb\"");
    }
}
