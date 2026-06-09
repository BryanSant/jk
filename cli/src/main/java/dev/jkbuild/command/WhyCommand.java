// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.resolver.Provenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code jk why &lt;module&gt;} — explain why a module is in the dependency graph.
 */
public final class WhyCommand implements CliCommand {

    @Override
    public String name() {
        return "why";
    }

    @Override
    public String description() {
        return "Explain why a module is in the dependency graph";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("dependency", Arity.ONE, "group or artifact or substring"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path dir = new GlobalOptions().workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            System.err.println("jk why: project must have jk.toml and jk.lock (run `jk lock` first)");
            return 2;
        }

        String query = moduleOnly(in.positionals().get(0));
        JkBuild project = JkBuildParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);

        List<Lockfile.Package> matches = lock.packages().stream()
                .filter(p -> matchesQuery(p.name(), query))
                .toList();
        if (matches.isEmpty()) {
            System.err.println("jk why: " + query + " is not in jk.lock");
            return 1;
        }

        System.out.println(Theme.active().gradientHeaderAnsi("Jk - Dependency Lookup"));
        for (Lockfile.Package target : matches) {
            System.out.println(Coords.module(target.name(), target.version()) + " is pulled in by:");
            List<Provenance.Path> paths = Provenance.pathsTo(project, lock, target.name());
            if (paths.isEmpty()) {
                System.out.println("  (unreachable from declared dependencies — likely a stale lockfile entry)");
            } else {
                for (Provenance.Path path : paths) {
                    System.out.println("  " + renderPath(path));
                }
            }
            if (matches.size() > 1) System.out.println();
        }
        return 0;
    }

    /** Format a dependency path with colored coordinates. */
    private static String renderPath(Provenance.Path path) {
        return path.steps().stream()
                .map(s -> Coords.module(s.module(), s.version()))
                .collect(Collectors.joining(Theme.colorize(" -> ", Theme.active().darkGray())));
    }

    /** Strip the version component if present; return arg unchanged when no colon. */
    private static String moduleOnly(String arg) {
        int first = arg.indexOf(':');
        if (first < 0) return arg;
        int second = arg.indexOf(':', first + 1);
        return second < 0 ? arg : arg.substring(0, second);
    }

    /**
     * Match a lockfile {@code group:artifact} name against a user query.
     * Exact match, artifact-only match (query has no colon), or substring.
     */
    private static boolean matchesQuery(String name, String query) {
        if (name.equals(query)) return true;
        if (!query.contains(":")) {
            if (name.endsWith(":" + query)) return true;
            return name.contains(query);
        }
        return false;
    }
}
