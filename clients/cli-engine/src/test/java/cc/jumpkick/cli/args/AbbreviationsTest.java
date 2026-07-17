// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.args;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AbbreviationsTest {

    private static Map<String, String> named(String... names) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String n : names) m.put(n, n);
        return m;
    }

    @Test
    void exactMatchWins() {
        var r = Abbreviations.resolve("init", named("init", "install"));
        assertThat(r.kind()).isEqualTo(Abbreviations.Kind.EXACT);
        assertThat(r.value()).isEqualTo("init");
    }

    @Test
    void uniquePrefixResolves() {
        var r = Abbreviations.resolve("b", named("build", "test", "clean"));
        assertThat(r.kind()).isEqualTo(Abbreviations.Kind.UNIQUE_PREFIX);
        assertThat(r.value()).isEqualTo("build");
    }

    @Test
    void ambiguousPrefixListsCandidatesSorted() {
        var r = Abbreviations.resolve("ex", named("explain", "export", "build"));
        assertThat(r.kind()).isEqualTo(Abbreviations.Kind.AMBIGUOUS);
        assertThat(r.value()).isNull();
        assertThat(r.candidates()).containsExactly("explain", "export");
    }

    @Test
    void noMatchIsNone() {
        var r = Abbreviations.resolve("zzz", named("build", "test"));
        assertThat(r.kind()).isEqualTo(Abbreviations.Kind.NONE);
    }

    @Test
    void aliasesOfSameTargetAreNotAmbiguous() {
        // Two names bound to the SAME target (e.g. jdk + jdks) collapse to one unique match.
        Map<String, String> m = new LinkedHashMap<>();
        String jdk = "jdk";
        m.put("jdk", jdk);
        m.put("jdks", jdk); // same instance
        var r = Abbreviations.resolve("j", m);
        assertThat(r.kind()).isEqualTo(Abbreviations.Kind.UNIQUE_PREFIX);
        assertThat(r.value()).isSameAs(jdk);
    }
}
