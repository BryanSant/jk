// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The capability layer ({@link BuildExtension}/{@link PackageExtension}/…) translates to the same
 * {@code describe} declarations {@link BuildPlugin#register} would emit — the engine sees no
 * difference, so its whole driver is untouched. Terminal-goal capabilities are not wired yet and
 * fail loudly rather than declaring a silent no-op.
 */
class CapabilityHarnessTest {

    /** A capability plugin: a BuildExtension source-gen step + a PackageExtension packager. */
    static final class FixturePlugin implements Plugin, BuildExtension, PackageExtension {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("jk-fixture", "##FX:");
        }

        @Override
        public int run(List<String> args, ProtocolWriter out) throws Exception {
            return BuildPluginHarness.run(this, args, out);
        }

        @Override
        public void build(BuildContext ctx) {
            ctx.named("gen-thing")
                    .after(Phase.RESOLVE)
                    .before(Phase.COMPILE)
                    .inputs(In.projectFiles("src"), In.config())
                    .outputs("gen")
                    .contributesSources("gen")
                    .run(exec -> exec.label("generating"));
        }

        @Override
        public void pack(PackageContext ctx) {
            ctx.inputs(In.classes(), In.runtimeEntries(), In.config())
                    .produce("fixture-jar", io -> io.label("packaging"));
        }
    }

    @Test
    void capability_plugin_describes_step_and_packager(@TempDir Path dir) throws Exception {
        var out = capture();
        int exit = BuildPluginHarness.run(new FixturePlugin(), List.of(describeSpec(dir).toString()), out.writer);
        assertThat(exit).isZero();
        List<String> lines = out.lines();
        // The BuildExtension's implicit step, translated to the same StepSpec describe line register() would emit.
        assertThat(lines).anyMatch(l -> l.contains("\"t\":\"step\"")
                && l.contains("\"name\":\"gen-thing\"")
                && l.contains("\"after\":\"resolve\"")
                && l.contains("\"before\":\"compile\"")
                && l.contains("\"contributesSources\":[\"gen\"]")
                && l.contains("\"outputs\":[\"gen\"]"));
        // The PackageExtension's packager.
        assertThat(lines).anyMatch(l -> l.contains("\"t\":\"packager\"") && l.contains("\"name\":\"fixture-jar\""));
    }

    @Test
    void terminal_goal_capability_is_not_wired_yet(@TempDir Path dir) throws Exception {
        Plugin imagePlugin = new ImageFixture();
        assertThatThrownBy(() ->
                        BuildPluginHarness.run(imagePlugin, List.of(describeSpec(dir).toString()), capture().writer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stream 6");
    }

    /** A plugin claiming a terminal capability whose execution is not wired yet. */
    static final class ImageFixture implements Plugin, ImageExtension {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("jk-image-fixture", "##IX:");
        }

        @Override
        public int run(List<String> args, ProtocolWriter out) throws Exception {
            return BuildPluginHarness.run(this, args, out);
        }

        @Override
        public void image(ImageContext ctx) {
            /* unreachable until Stream 6 wires terminal-goal execution */
        }
    }

    private static Path describeSpec(Path dir) throws Exception {
        Path spec = dir.resolve("describe.spec");
        Files.write(spec, List.of(
                "{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"jk-fixture\"}",
                "{\"t\":\"config\",\"key\":\"version\",\"kind\":\"string\",\"value\":\"1.0\"}",
                "{\"t\":\"project\",\"group\":\"g\",\"name\":\"n\",\"version\":\"1\",\"javaRelease\":25,"
                        + "\"nativeDeclared\":false,\"kotlin\":false}"));
        return spec;
    }

    private static Captured capture() {
        var buffer = new ByteArrayOutputStream();
        return new Captured(buffer, new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##T:"));
    }

    private record Captured(ByteArrayOutputStream buffer, ProtocolWriter writer) {
        List<String> lines() {
            return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n"));
        }
    }
}
