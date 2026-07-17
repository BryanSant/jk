// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolCoordSpecTest {

    @Test
    void pinned_gav_parses_via_the_coordinate_grammar() {
        var spec = ToolCoordSpec.parse("com.example:widget-cli:1.0.0");
        assertThat(spec).isInstanceOf(ToolCoordSpec.Pinned.class);
        assertThat(((ToolCoordSpec.Pinned) spec).coordinate().toGav()).isEqualTo("com.example:widget-cli:1.0.0");
        assertThat(spec.module()).isEqualTo("com.example:widget-cli");
    }

    @Test
    void pinned_gav_keeps_the_packaging_type_meaning_of_at() {
        // Three coordinate segments before `@` → `@pom` is a packaging type, not a selector.
        var spec = ToolCoordSpec.parse("com.example:widget-cli:1.0.0@pom");
        assertThat(spec).isInstanceOf(ToolCoordSpec.Pinned.class);
        assertThat(((ToolCoordSpec.Pinned) spec).coordinate().type()).isEqualTo("pom");
    }

    @Test
    void version_less_module_floats_to_latest() {
        var spec = ToolCoordSpec.parse("com.example:widget-cli");
        assertThat(spec).isInstanceOf(ToolCoordSpec.Floating.class);
        var f = (ToolCoordSpec.Floating) spec;
        assertThat(f.group()).isEqualTo("com.example");
        assertThat(f.artifact()).isEqualTo("widget-cli");
        assertThat(f.selector()).isInstanceOf(VersionSelector.Latest.class);
    }

    @Test
    void one_colon_before_at_reads_the_suffix_as_a_floating_selector() {
        var caret = (ToolCoordSpec.Floating) ToolCoordSpec.parse("com.example:widget-cli@1.2");
        assertThat(caret.selector()).isInstanceOf(VersionSelector.Caret.class);

        var exact = (ToolCoordSpec.Floating) ToolCoordSpec.parse("com.example:widget-cli@=1.2.3");
        assertThat(exact.selector()).isInstanceOf(VersionSelector.Exact.class);

        var latest = (ToolCoordSpec.Floating) ToolCoordSpec.parse("com.example:widget-cli@latest");
        assertThat(latest.selector()).isInstanceOf(VersionSelector.Latest.class);
    }

    @Test
    void malformed_specs_are_rejected() {
        assertThatThrownBy(() -> ToolCoordSpec.parse("no-colons-here")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolCoordSpec.parse(":artifact")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolCoordSpec.parse("group:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolCoordSpec.parse("g:a@")).isInstanceOf(IllegalArgumentException.class);
    }
}
