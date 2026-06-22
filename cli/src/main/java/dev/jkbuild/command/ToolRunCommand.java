// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.CompileToolchain;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.tool.ToolEnv;
import dev.jkbuild.tool.ToolLauncher;
import dev.jkbuild.tool.ToolResolver;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk tool run <coord|file> -- <args>} — ephemerally run a tool or a
 * standalone file (PRD §20.3), forwarding {@code <args>} to the program.
 *
 * <p>The target may be either:
 * <ul>
 *   <li>a Maven coordinate ({@code group:artifact:version}) — resolved,
 *       cached under {@code $JK_CACHE_DIR}, and exec'd via its
 *       {@code Main-Class}; or</li>
 *   <li>a {@code .java}/{@code .kt}/{@code .kts}/{@code .jar} file — compiled
 *       (if needed) and run via {@link ScriptRunner}.</li>
 * </ul>
 *
 * <p>{@code jk activate} scripts also expose this on the path as a
 * {@code jkx} alias for uvx-style muscle memory.
 */
public final class ToolRunCommand implements CliCommand {

    @Override public String name() { return "run"; }
    @Override public String description() { return "Run a tool from a Maven coord or .java/.kt/.kts/.jar file"; }
    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<class>", "Override the Main-Class to exec (coordinate targets only).", "--main"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                Opt.value("<dir>", "Override the jk state directory.", "--state-dir").hide(),
                Opt.value("<url>", "Override the Maven repository URL (for tests).", "--repo-url").hide(),
                Opt.flag("Ignore cached classes and recompile (file targets only).", "--force-recompile"));
    }
    @Override public List<Param> parameters() {
        // The tool args after the target are captured as trailing positionals via ZERO_OR_MORE
        return List.of(
                Param.of("coord|file", Arity.ONE, "Maven coordinate or .java/.kt/.kts/.jar file."),
                Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to the program."));
    }

    String target;
    String mainClass;
    Path cacheDirOverride;
    Path stateDirOverride;
    URI repoUrl;
    boolean forceRecompile;
    List<String> toolArgs = new ArrayList<>();
    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        List<String> positionals = in.positionals();
        this.target = positionals.isEmpty() ? "" : positionals.get(0);
        this.toolArgs = positionals.size() > 1 ? positionals.subList(1, positionals.size()) : List.of();
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.stateDirOverride = in.value("state-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.forceRecompile = in.isSet("force-recompile");
        this.global = GlobalOptions.from(in);
        // A file target (by extension) is compiled/run by ScriptRunner; the
        // extension is the signal even when the file is missing, so the user
        // gets a proper "not found" error from the matching mode handler.
        if (ScriptRunner.isRunnableFile(target)) {
            return new ScriptRunner(global, cacheDirOverride, stateDirOverride,
                    repoUrl, forceRecompile)
                    .run(Path.of(target), toolArgs);
        }

        Coordinate primary = Coordinate.parse(target);
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Files.createDirectories(cacheDir);

        Cas cas = new Cas(cacheDir);
        Http http = new Http();
        URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", url, http, cas));
        ToolResolver toolResolver = new ToolResolver(repos);

        ToolEnv env = toolResolver.resolve(primary, primary.artifact(), mainClass);
        Path javaHome = CompileToolchain.runningJavaHome();
        return ToolLauncher.execEphemeral(javaHome, env, toolArgs);
    }
}
