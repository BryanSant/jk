// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.resolver.Provenance;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk why &lt;module&gt;} — explain why a module is in the dependency graph.
 */
@Command(name = "why", description = "Explain why a module is in the dependency graph.")
public final class WhyCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<module>",
            description = "group:artifact (without version)")
    String moduleArg;

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("build.jk");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            System.err.println("jk why: project must have build.jk and jk.lock (run `jk lock` first)");
            return 2;
        }

        String module = moduleOnly(moduleArg);
        BuildJk project = BuildJkParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);

        Lockfile.Package target = lock.packages().stream()
                .filter(p -> p.name().equals(module))
                .findFirst()
                .orElse(null);
        if (target == null) {
            System.err.println("jk why: " + module + " is not in jk.lock");
            return 1;
        }

        System.out.println(module + " v" + target.version() + " is pulled in by:");
        List<Provenance.Path> paths = Provenance.pathsTo(project, lock, module);
        if (paths.isEmpty()) {
            System.out.println("  (unreachable from declared dependencies — likely a stale lockfile entry)");
            return 0;
        }
        for (Provenance.Path path : paths) {
            System.out.println("  " + path.render());
        }
        return 0;
    }

    private static String moduleOnly(String arg) {
        int first = arg.indexOf(':');
        if (first < 0) {
            throw new IllegalArgumentException(
                    "expected group:artifact[:version], got: " + arg);
        }
        int second = arg.indexOf(':', first + 1);
        return second < 0 ? arg : arg.substring(0, second);
    }
}
