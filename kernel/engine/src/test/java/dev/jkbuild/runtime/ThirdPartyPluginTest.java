// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.plugin.manifest.PluginContributions;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.tool.TrustedPlugins;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P5 acceptance (build-plugins plan §4): a third-party hello-world table plugin, published to a
 * (file://) repo, declared under {@code [plugins]} — resolved, SHA-pinned, manifest-extracted,
 * schema-validated, contribution-applied, and its worker code trust-gated end to end.
 */
class ThirdPartyPluginTest {

    private static final String GROUP = "com.example";
    private static final String ARTIFACT = "hello-jk-plugin";
    private static final String VERSION = "1.0.0";

    private static final String MANIFEST = """
            [plugin]
            id      = "hello"
            table   = "hello"
            version = "1.0.0"

            [schema]
            greeting = { type = "string", default = "hi" }

            [[contribute.compiler-args]]
            javac = ["-Averbose.greeting=${config.greeting}"]

            [code]
            protocol-prefix = "##HELLO:"
            """;

    private static final String MAIN = """
            import java.nio.file.*;
            public final class HelloPluginMain {
                public static void main(String[] args) throws Exception {
                    String spec = Files.readString(Path.of(args[0]));
                    if (spec.contains("\\"op\\":\\"describe\\"")) {
                        System.out.println("##HELLO:{\\"t\\":\\"verb\\",\\"name\\":\\"hello\\",\\"description\\":\\"Say hello\\"}");
                    } else if (spec.contains("\\"op\\":\\"verb\\"")) {
                        System.out.println("##HELLO:{\\"t\\":\\"verb-out\\",\\"line\\":\\"hello from the plugin worker\\"}");
                    }
                }
            }
            """;

    @AfterEach
    void clearSeam() {
        System.clearProperty("jk.trust.state.dir");
    }

    @Test
    void hello_world_plugin_runs_from_a_published_coordinate(@TempDir Path tmp) throws Exception {
        Path repo = publishFixture(tmp.resolve("repo"));
        Path cache = tmp.resolve("cache");
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path stateDir = Files.createDirectories(tmp.resolve("state"));
        System.setProperty("jk.trust.state.dir", stateDir.toString());

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name = "demo"
                group = "com.demo"
                version = "0.1.0"

                [repositories]
                local = "%s"

                [plugins]
                hello = { group = "%s", name = "%s", version = "%s" }

                [hello]
                greeting = "yo"
                """
                .formatted(repo.toUri(), GROUP, ARTIFACT, VERSION));

        // 1. Pre-lock: the declaration is unresolved — the parse stays soft, no config yet.
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("hello")).isEmpty();

        // 2. Lock: resolve the coordinate exactly as lock-plugins does — fetch, SHA-pin, extract.
        Cas cas = new Cas(cache);
        RepoGroup repos = RepoGroupBuilder.buildFor(build, null, cas);
        var fetched = repos.tryFetchArtifact(Coordinate.of(GROUP, ARTIFACT, VERSION)).orElseThrow();
        var entry = new Lockfile.PluginEntry(
                GROUP + ":" + ARTIFACT, VERSION, "sha256:" + fetched.fetched().sha256());
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION, "test", Lockfile.RESOLUTION_ALGORITHM, null, null,
                        List.of(), List.of(entry)),
                project.resolve("jk.lock"));
        assertThat(PluginDescriptorOps.ensureMaterialized(project, cache)).isTrue();

        // 3. Re-parse: the table validates against the extracted schema; the contribution applies.
        build = JkBuildParser.reparse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("hello")).isPresent();
        assertThat(build.pluginConfig("hello").orElseThrow().string("greeting")).isEqualTo("yo");
        assertThat(PluginContributions.javacArgs(build, project, Set.of()))
                .contains("-Averbose.greeting=yo");

        // 3b. With the plugin resolved, a genuinely unowned table is the plan's hard error.
        Files.writeString(
                project.resolve("jk.toml"),
                Files.readString(project.resolve("jk.toml")) + "\n[bogus]\nx = 1\n");
        assertThatThrownBy(() -> JkBuildParser.reparse(project.resolve("jk.toml")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[bogus] is not owned by any installed plugin");
        Files.writeString(
                project.resolve("jk.toml"),
                Files.readString(project.resolve("jk.toml")).replace("\n[bogus]\nx = 1\n", ""));
        build = JkBuildParser.reparse(project.resolve("jk.toml"));

        // 4. Untrusted: the engine refuses to fork the worker, naming the remedy.
        var active = PluginBuild.activeCodePlugin(build, project).orElseThrow();
        Path spec = Files.writeString(tmp.resolve("noop.spec"), "{\"t\":\"op\",\"op\":\"describe\"}\n");
        assertThatThrownBy(() -> PluginBuild.runWorker(active, cache, spec, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not trusted")
                .hasMessageContaining("jk trust plugin com.example:hello-jk-plugin");

        // 5. Trusted: describe answers, and the verb runs through the real verb machinery.
        TrustedPlugins.load(stateDir).add(GROUP + ":" + ARTIFACT);
        var report = PluginVerbs.run(project, cache, "hello", List.of());
        assertThat(report.error()).isNull();
        assertThat(report.exit()).isZero();
        assertThat(report.output()).containsExactly("hello from the plugin worker");
    }

    /** Compile the fixture main, jar it with the manifest, publish to a Maven-layout dir. */
    private static Path publishFixture(Path repo) throws Exception {
        Path src = Files.createTempDirectory("hello-plugin-src");
        Path srcFile = src.resolve("HelloPluginMain.java");
        Files.writeString(srcFile, MAIN);
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", src.toString(), srcFile.toString());
        if (rc != 0) throw new IllegalStateException("fixture compile failed");

        Path dir = Files.createDirectories(
                repo.resolve(GROUP.replace('.', '/')).resolve(ARTIFACT).resolve(VERSION));
        Path jar = dir.resolve(ARTIFACT + "-" + VERSION + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "HelloPluginMain");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, mf)) {
            jos.putNextEntry(new JarEntry("jk-plugin.toml"));
            jos.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("HelloPluginMain.class"));
            jos.write(Files.readAllBytes(src.resolve("HelloPluginMain.class")));
            jos.closeEntry();
        }
        Files.writeString(dir.resolve(ARTIFACT + "-" + VERSION + ".pom"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                </project>
                """
                .formatted(GROUP, ARTIFACT, VERSION));
        return repo;
    }
}
