// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.compat.PassthroughEnv;
import build.jumpkick.jdk.InstalledJdk;
import build.jumpkick.jdk.JdkResolver;
import build.jumpkick.model.command.Arity;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.model.command.Param;
import build.jumpkick.runtime.HostedEvents;
import build.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk mvn ...} — passthrough to Maven (PRD §24.1). Provisioning (link a discovered install or
 * download a distribution via the {@code jk-compat-runner} worker) is <b>engine-hosted</b> (Wave 2
 * of the slim-client migration, a one-shot request → {@code {bin, version, source}} result); the
 * <em>exec</em> of the provisioned {@code bin/mvn} deliberately stays in this client process with
 * inherited stdio, so Maven's own TTY output, prompts, and Ctrl-C semantics are untouched — hosting
 * a foreign build's interactive run would be wrong, hosting its download is not.
 */
public final class MvnCommand implements CliCommand {

    @Override
    public String name() {
        return "mvn";
    }

    @Override
    public String description() {
        return "Passthrough to Maven (jk manages the install)";
    }

    @Override
    public boolean passthrough() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root.", "--tools-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.flag("Skip tool discovery.", "--no-discover"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to Maven."));
    }

    Path directory;
    Path toolsDir;
    Path jdksDir;
    boolean noDiscover;
    List<String> args = new ArrayList<>();

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; real {@code jk mvn}/{@code gradle} provisioning
     * always engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.directory = in.value("directory").map(Path::of).orElse(null);
        this.toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.noDiscover = in.isSet("no-discover");
        this.args = in.positionals();

        Path projectDir = directory != null
                ? directory.toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();
        Path toolsRoot = toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools");
        Path cache = JkDirs.cache();

        // Provision Maven via the compat-runner (engine-hosted), get back the bin path.
        Path mvnBin = provision(cache, projectDir, toolsRoot, noDiscover, false);
        if (mvnBin == null) return 1;

        // Exec Maven directly so stdio is inherited cleanly.
        Optional<InstalledJdk> jdk = JdkResolver.forProject(projectDir, jdksDir);
        List<String> command = new ArrayList<>();
        command.add(mvnBin.toString());
        command.addAll(args);
        ProcessBuilder pb =
                new ProcessBuilder(command).directory(projectDir.toFile()).inheritIO();
        PassthroughEnv.apply(pb.environment(), jdk.map(InstalledJdk::home).orElse(null));
        return pb.start().waitFor();
    }

    /**
     * Provision a Maven/Gradle distribution and return its launcher path, or {@code null} (with the
     * error already rendered) on failure. Engine-hosted; the test-only in-process path runs the
     * identical {@code CompatPipelines.provision} code.
     */
    static Path provision(Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean isGradle)
            throws IOException, InterruptedException {
        String tool = isGradle ? "gradle" : "mvn";
        HostedEvents.Provision p;
        if (engineDisabledForTests()) {
            p = build.jumpkick.cli.engine.InProcessEngine.require()
                    .provision(cache, projectDir, toolsRoot, noDiscover, isGradle);
        } else {
            try {
                p = build.jumpkick.cli.engine.EngineClient.provision(
                        build.jumpkick.engine.EnginePaths.current(), cache, projectDir, toolsRoot, noDiscover, isGradle);
            } catch (IOException e) {
                CliOutput.err("jk " + tool + ": " + e.getMessage());
                return null;
            }
        }
        if (p.error() != null) CliOutput.err("jk " + tool + ": " + p.error());
        if ("LINKED".equals(p.source()) || "DOWNLOADED".equals(p.source())) {
            CliOutput.err((isGradle ? "Gradle " : "Maven ") + p.version() + " "
                    + p.source().toLowerCase());
        }
        if (p.exit() != 0) {
            if (p.diag() != null && !p.diag().isBlank()) CliOutput.err(p.diag());
            return null;
        }
        return p.bin() != null ? Path.of(p.bin()) : null;
    }
}
