// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.config.JkBuildParser;
import build.jumpkick.model.JkBuild;
import build.jumpkick.runtime.BuildGraph;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link BuildGraph#orderModules} ordering, with focus on the {@code [build].order-after}
 * build-order-only edges: they must affect order without being dependencies.
 */
class BuildCommandTopoSortTest {

    private static JkBuild module(String name, String orderAfterToml) {
        return JkBuildParser.parse("""
                [project]
                group   = "g"
                name    = "%s"
                version = "1.0.0"
                %s
                """.formatted(name, orderAfterToml));
    }

    @Test
    void order_after_forces_a_module_to_build_after_its_named_sibling() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        // Declare "b" first so declaration order alone would put b before a;
        // order-after must override that.
        modules.put(Path.of("b"), module("b", "[build]\norder-after = [\"a\"]"));
        modules.put(Path.of("a"), module("a", ""));

        List<Path> sorted = BuildGraph.orderModules(modules);

        assertThat(sorted.indexOf(Path.of("a"))).as("a builds before b").isLessThan(sorted.indexOf(Path.of("b")));
        assertThat(sorted).containsExactlyInAnyOrder(Path.of("a"), Path.of("b"));
    }

    @Test
    void order_after_resolves_by_group_artifact_coordinate_too() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("b"), module("b", "[build]\norder-after = [\"g:a\"]"));
        modules.put(Path.of("a"), module("a", ""));

        List<Path> sorted = BuildGraph.orderModules(modules);

        assertThat(sorted.indexOf(Path.of("a"))).isLessThan(sorted.indexOf(Path.of("b")));
    }

    @Test
    void test_worker_jar_source_is_a_build_order_prerequisite() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        // engine hands a's worker jar to its test JVM → a must build first, with no
        // explicit order-after. test-worker-jars feeds allOrderAfter() as a build-order edge.
        modules.put(Path.of("engine"), module("engine", "[build]\ntest-worker-jars = [\"a\"]"));
        modules.put(Path.of("a"), module("a", ""));

        List<Path> sorted = BuildGraph.orderModules(modules);

        assertThat(sorted.indexOf(Path.of("a")))
                .as("a builds before the module that consumes its worker jar")
                .isLessThan(sorted.indexOf(Path.of("engine")));
    }

    @Test
    void unknown_order_after_name_is_ignored() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("a"), module("a", "[build]\norder-after = [\"nope\"]"));

        List<Path> sorted = BuildGraph.orderModules(modules);

        assertThat(sorted).containsExactly(Path.of("a"));
    }

    @Test
    void mutual_order_after_cycle_falls_back_without_dropping_modules() {
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(Path.of("a"), module("a", "[build]\norder-after = [\"b\"]"));
        modules.put(Path.of("b"), module("b", "[build]\norder-after = [\"a\"]"));

        List<Path> sorted = BuildGraph.orderModules(modules);

        // Cycle → no valid order, but every module still appears (build still tries).
        assertThat(sorted).containsExactlyInAnyOrder(Path.of("a"), Path.of("b"));
    }
}
