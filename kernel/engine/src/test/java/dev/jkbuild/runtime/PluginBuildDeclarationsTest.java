// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The describe-reply decode — exactly what the content-keyed cache file replays. */
class PluginBuildDeclarationsTest {

    @Test
    void decodes_steps_packager_and_verbs() {
        var decls = PluginBuild.decode(List.of(
                "{\"t\":\"step\",\"name\":\"gen\",\"after\":\"compile\",\"before\":\"package\","
                        + "\"inputs\":[\"classes\"],\"outputs\":[\"out\"],\"contributesClasses\":[\"out\"],"
                        + "\"contributesResources\":[],\"contributesSources\":[]}",
                "{\"t\":\"packager\",\"name\":\"pkg\",\"inputs\":[\"classes\"]}",
                "{\"t\":\"verb\",\"name\":\"devices\",\"description\":\"list attached devices\"}",
                "{\"t\":\"label\",\"text\":\"noise\"}"));
        assertThat(decls.steps()).hasSize(1);
        assertThat(decls.step("gen").contributesClasses()).containsExactly("out");
        assertThat(decls.packager().name()).isEqualTo("pkg");
        assertThat(decls.verbs()).hasSize(1);
        assertThat(decls.verb("devices").description()).isEqualTo("list attached devices");
        assertThat(decls.verb("nope")).isNull();
    }
}
