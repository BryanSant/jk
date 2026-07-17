// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.script;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import java.net.URI;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ScriptHeaderParserTest {

    @Test
    void jk_style_directives_are_recognised() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                ///usr/bin/env jk run "$0" "$@"; exit $?

                //jk jdk 21
                //jk dep com.squareup.okhttp3:okhttp:4.12.0
                //jk dep com.fasterxml.jackson.core:jackson-databind:2.18.2
                //jk repo https://nexus.example/repository/maven-public/
                //jk feature postgres
                //jk javac-options -parameters --enable-preview
                //jk java-options -Xmx512m

                public class Foo {
                    public static void main(String[] a) {}
                }
                """);

        assertThat(h.release()).isEqualTo(21);
        assertThat(h.deps())
                .extracting(Dependency::module)
                .containsExactly("com.squareup.okhttp3:okhttp", "com.fasterxml.jackson.core:jackson-databind");
        assertThat(h.deps())
                .extracting(d -> ((VersionSelector.Exact) d.version()).version())
                .containsExactly("4.12.0", "2.18.2");
        assertThat(h.repos())
                .extracting(uri -> uri.toString())
                .containsExactly("https://nexus.example/repository/maven-public/");
        assertThat(h.features()).containsExactly("postgres");
        assertThat(h.javacOptions()).containsExactly("-parameters", "--enable-preview");
        assertThat(h.javaOptions()).containsExactly("-Xmx512m");
    }

    @Test
    void jbang_style_directives_are_recognised() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //DEPS com.squareup.okhttp3:okhttp:4.12.0 org.slf4j:slf4j-simple:2.0.16
                //JAVA 17
                //JAVAC_OPTIONS -parameters
                //JAVA_OPTIONS -Xmx256m

                public class Bar { public static void main(String[] a) {} }
                """);

        assertThat(h.release()).isEqualTo(17);
        assertThat(h.deps())
                .extracting(Dependency::module)
                .containsExactly("com.squareup.okhttp3:okhttp", "org.slf4j:slf4j-simple");
        assertThat(h.javacOptions()).containsExactly("-parameters");
        assertThat(h.javaOptions()).containsExactly("-Xmx256m");
    }

    @Test
    void mixed_jk_and_jbang_lines_coexist() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //DEPS com.squareup.okhttp3:okhttp:4.12.0
                //jk dep org.slf4j:slf4j-simple:2.0.16
                //JAVA 21

                public class Mix { public static void main(String[] a) {} }
                """);

        assertThat(h.release()).isEqualTo(21);
        assertThat(h.deps())
                .extracting(Dependency::module)
                .containsExactly("com.squareup.okhttp3:okhttp", "org.slf4j:slf4j-simple");
    }

    @Test
    void shebang_shim_is_skipped() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                ///usr/bin/env jk run "$0" "$@"; exit $?
                //jk jdk 21
                //jk dep com.example:lib:1.0.0
                public class Foo {}
                """);
        assertThat(h.release()).isEqualTo(21);
        assertThat(h.deps()).hasSize(1);
    }

    @Test
    void parsing_stops_at_first_code_line() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //jk dep com.example:a:1.0
                public class Foo {}
                //jk dep com.example:b:2.0
                """);
        // Only the dep before the class body is recognised.
        assertThat(h.deps()).extracting(Dependency::module).containsExactly("com.example:a");
    }

    @Test
    void java_8_style_version_is_normalised() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //JAVA 1.8
                """);
        // Legacy `1.8` form is normalised to the major component (8).
        assertThat(h.release()).isEqualTo(8);
    }

    @Test
    void vendor_suffix_is_stripped_from_jdk_version() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //jk jdk 21-tem
                """);
        assertThat(h.release()).isEqualTo(21);
    }

    @Test
    void invalid_dep_is_rejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ScriptHeaderParser.parse("//jk dep not-a-coord"));
    }

    @Test
    void at_form_is_floating_with_caret_default() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //jk dep com.example:lib@1.2.3
                """);
        Dependency d = h.deps().getFirst();
        assertThat(d.module()).isEqualTo("com.example:lib");
        assertThat(d.pinned()).isFalse();
        assertThat(d.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void at_form_with_explicit_decoration() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //jk dep com.example:lib@~1.2.3
                //jk dep com.example:tool@latest
                """);
        assertThat(h.deps().get(0).version()).isInstanceOf(VersionSelector.Tilde.class);
        assertThat(h.deps().get(1).version()).isInstanceOf(VersionSelector.Latest.class);
    }

    @Test
    void deps_directive_accepts_mixed_forms() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //DEPS com.example:pinned:1.0.0 com.example:floating@^2.0.0
                """);
        assertThat(h.deps()).hasSize(2);
        assertThat(h.deps().get(0).pinned()).isTrue();
        assertThat(h.deps().get(1).pinned()).isFalse();
    }

    @Test
    void colon_form_with_decorations_is_rejected() {
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> ScriptHeaderParser.parse("//jk dep com.example:lib:^1.0"));
    }

    @Test
    void kotlin_directive_records_version() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //KOTLIN 2.1.0
                fun main() {}
                """);
        assertThat(h.kotlinVersion()).isEqualTo("2.1.0");
    }

    @Test
    void jk_kotlin_directive_also_recognized() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //jk kotlin 2.2.0
                fun main() {}
                """);
        assertThat(h.kotlinVersion()).isEqualTo("2.2.0");
    }

    @Test
    void absent_kotlin_directive_leaves_version_null() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //jk dep com.example:lib:1.0
                """);
        assertThat(h.kotlinVersion()).isNull();
    }

    @Test
    void repos_directive_accepts_named_and_bare_urls() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //REPOS central=https://repo.maven.apache.org/maven2,https://jitpack.io
                class X {}
                """);
        assertThat(h.repos())
                .containsExactly(URI.create("https://repo.maven.apache.org/maven2"), URI.create("https://jitpack.io"));
    }

    @Test
    void runtime_and_compile_options_are_aliases() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //COMPILE_OPTIONS -parameters
                //RUNTIME_OPTIONS -Xmx64m
                class X {}
                """);
        assertThat(h.javacOptions()).containsExactly("-parameters");
        assertThat(h.javaOptions()).containsExactly("-Xmx64m");
    }

    @Test
    void preview_folds_enable_preview_into_both_option_lists() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //PREVIEW
                class X {}
                """);
        assertThat(h.javacOptions()).containsExactly("--enable-preview");
        assertThat(h.javaOptions()).containsExactly("--enable-preview");
    }

    @Test
    void main_files_gav_description_are_recorded() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //MAIN com.acme.Entry
                //FILES config.yml logging.properties=cfg/logging.properties
                //GAV com.acme:tool:1.0
                //DESCRIPTION Does the thing
                class X {}
                """);
        assertThat(h.main()).isEqualTo("com.acme.Entry");
        assertThat(h.files()).containsExactly("config.yml", "logging.properties=cfg/logging.properties");
        assertThat(h.gav()).isEqualTo("com.acme:tool:1.0");
        assertThat(h.description()).isEqualTo("Does the thing");
    }

    @Test
    void java_plus_suffix_means_minimum_release() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //JAVA 17+
                class X {}
                """);
        assertThat(h.release()).isEqualTo(17);
    }

    @Test
    void parse_kotlin_collects_file_annotations_anywhere() {
        ScriptHeader h = ScriptHeaderParser.parseKotlin("""
                //DEPS com.example:first:1.0
                @file:DependsOn("com.example:second:2.0", "com.example:third:3.0")
                @file:Repository("https://jitpack.io")

                import com.example.Thing
                fun main() {}
                """);
        assertThat(h.deps()).hasSize(3);
        assertThat(h.deps().get(1).module()).isEqualTo("com.example:second");
        assertThat(h.repos()).containsExactly(URI.create("https://jitpack.io"));
    }

    @Test
    void unknown_directives_stay_ignored() {
        ScriptHeader h = ScriptHeaderParser.parse("""
                //MODULE com.acme
                //MANIFEST Key=Value
                //CDS
                class X {}
                """);
        assertThat(h.deps()).isEmpty();
    }
}
