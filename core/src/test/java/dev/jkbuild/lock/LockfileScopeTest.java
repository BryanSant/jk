// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.lock;

import dev.jkbuild.model.Scope;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LockfileScopeTest {

    @Test
    void scopes_round_trip() {
        Lockfile original = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Package(
                        "com.example:widget",
                        "1.2.3",
                        "central+https://repo.maven.apache.org/maven2/",
                        "sha256:0123abcd",
                        null,
                        List.of(Scope.MAIN, Scope.TEST),
                        List.of())));

        String rendered = LockfileWriter.render(original);
        Lockfile parsed = LockfileReader.parse(rendered);

        assertThat(parsed.packages().getFirst().scopes())
                .containsExactlyInAnyOrder(Scope.MAIN, Scope.TEST);
    }

    @Test
    void scopes_render_in_canonical_order() {
        // Constructor canonicalizes (de-duped, declaration-order via EnumSet).
        Lockfile.Package pkg = new Lockfile.Package(
                "com.example:widget", "1.0", "central+x",
                "sha256:abcd", null,
                List.of(Scope.TEST, Scope.MAIN, Scope.MAIN),
                List.of());
        // EnumSet returns elements in enum declaration order: MAIN comes first.
        assertThat(pkg.scopes()).containsExactly(Scope.MAIN, Scope.TEST);
    }

    @Test
    void package_without_scopes_field_defaults_to_main() {
        String content = """
                version = 1
                generated-by = "jk 0.1.0"
                resolution-algorithm = "pubgrub-v1"

                [[package]]
                name = "com.foo:bar"
                version = "1.0"
                source = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:dead"
                """;
        Lockfile parsed = LockfileReader.parse(content);
        assertThat(parsed.packages().getFirst().scopes()).containsExactly(Scope.MAIN);
    }

    @Test
    void in_any_scope_filters_correctly() {
        Lockfile.Package mainOnly = new Lockfile.Package(
                "com.foo:a", "1.0", "central+x", "sha256:a", null,
                List.of(Scope.MAIN), List.of());
        Lockfile.Package testOnly = new Lockfile.Package(
                "com.foo:b", "1.0", "central+x", "sha256:b", null,
                List.of(Scope.TEST), List.of());
        Lockfile.Package both = new Lockfile.Package(
                "com.foo:c", "1.0", "central+x", "sha256:c", null,
                List.of(Scope.MAIN, Scope.TEST), List.of());

        EnumSet<Scope> mainSet = EnumSet.of(Scope.MAIN);
        assertThat(mainOnly.inAnyScope(mainSet)).isTrue();
        assertThat(testOnly.inAnyScope(mainSet)).isFalse();
        assertThat(both.inAnyScope(mainSet)).isTrue();
    }
}
