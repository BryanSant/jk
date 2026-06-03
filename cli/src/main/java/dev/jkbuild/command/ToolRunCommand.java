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
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

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
@Command(name = "run",
        description = "Ephemerally run a tool from a Maven coord, or a .java/.kt/.kts/.jar file")
public final class ToolRunCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "1", paramLabel = "<coord|file>",
            description = "Maven coordinate (group:artifact:version), or a "
                    + ".java/.kt/.kts/.jar file to execute.")
    String target;

    @Option(names = "--main",
            description = "Override the Main-Class to exec (coordinate targets only).")
    String mainClass;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @Option(names = "--state-dir", hidden = true,
            description = "Override the jk state directory. Default: $JK_STATE_DIR.")
    Path stateDirOverride;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @Option(names = "--force-recompile",
            description = "Ignore cached classes and recompile (file targets only).")
    boolean forceRecompile;

    @Parameters(index = "1..*", arity = "0..*", paramLabel = "<args>",
            description = "Arguments forwarded to the program (separate from jk's own flags with `--`).")
    List<String> toolArgs = new ArrayList<>();

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException, InterruptedException {
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
