// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.deny;

import dev.jkbuild.config.DenyPolicyParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.DenyPolicy;
import dev.jkbuild.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCheckerTest {

    @Test
    void no_policy_yields_no_violations() {
        Lockfile lock = new Lockfile(5, "jk test", "pubgrub-v1", List.of(
                pkg("g:a", "1.0", "central+https://repo.maven.apache.org/maven2/")));
        var violations = new PolicyChecker(DenyPolicy.permissive()).check(lock);
        assertThat(violations).isEmpty();
    }

    @Test
    void denied_source_host_flags_packages_from_that_host() {
        Lockfile lock = new Lockfile(5, "jk test", "pubgrub-v1", List.of(
                pkg("g:a", "1.0", "central+https://repo.maven.apache.org/maven2/"),
                pkg("g:b", "1.0", "jcenter+https://jcenter.bintray.com/"),
                pkg("g:c", "1.0", "nexus+https://nexus.example.com/repository/maven-public/")));

        DenyPolicy policy = new DenyPolicy(
                List.of("jcenter.bintray.com"), List.of(), List.of(),
                DenyPolicy.YankedPolicy.DENY);
        var violations = new PolicyChecker(policy).check(lock);
        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().module()).isEqualTo("g:b");
        assertThat(violations.getFirst().reason()).contains("jcenter.bintray.com");
    }

    @Test
    void suffix_match_flags_subdomain() {
        Lockfile lock = new Lockfile(5, "jk test", "pubgrub-v1", List.of(
                pkg("g:a", "1.0", "shady+https://artifacts.shady-corp.example/")));
        DenyPolicy policy = new DenyPolicy(
                List.of("shady-corp.example"), List.of(), List.of(),
                DenyPolicy.YankedPolicy.DENY);
        var violations = new PolicyChecker(policy).check(lock);
        assertThat(violations).hasSize(1);
    }

    @Test
    void toml_parser_extracts_the_deny_block() {
        DenyPolicy policy = DenyPolicyParser.parse("""
                [project]
                group    = "g"
                name     = "a"
                version  = "1"
                jdk      = 21

                [deny]
                yanked = "warn"

                [deny.licenses]
                deny  = ["GPL-3.0", "AGPL-3.0"]
                allow = ["Apache-2.0", "MIT"]

                [deny.sources]
                deny = ["jcenter.bintray.com"]
                """);
        assertThat(policy.deniedLicenses()).containsExactly("GPL-3.0", "AGPL-3.0");
        assertThat(policy.allowedLicenses()).containsExactly("Apache-2.0", "MIT");
        assertThat(policy.deniedSources()).containsExactly("jcenter.bintray.com");
        assertThat(policy.yanked()).isEqualTo(DenyPolicy.YankedPolicy.WARN);
    }

    @Test
    void toml_parser_returns_permissive_when_block_absent() {
        DenyPolicy policy = DenyPolicyParser.parse("""
                [project]
                group    = "g"
                name     = "a"
                version  = "1"
                jdk      = 21
                """);
        assertThat(policy.isEmpty()).isTrue();
    }

    private static Lockfile.Artifact pkg(String name, String version, String source) {
        return new Lockfile.Artifact(name, version, source, "sha256:0", null,
                List.of(Scope.MAIN), List.of());
    }
}
