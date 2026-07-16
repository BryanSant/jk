// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.engine.protocol.WhyReport;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** {@code jk why &lt;module&gt;} — explain why a module is in the dependency graph. */
public final class WhyCommand implements CliCommand {

    @Override
    public String name() {
        return "why";
    }

    @Override
    public String description() {
        return "Explain why an item is in the dependency graph";
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
            CliOutput.err("jk why: project must have jk.toml and jk.lock (run `jk lock` first)");
            return Exit.CONFIG;
        }

        String query = moduleOnly(in.positionals().get(0));
        // The graph reasoning is engine-side (thin client): matching + provenance ride WHY_ACK.
        WhyReport report = engineDisabledForTests()
                ? cc.jumpkick.cli.engine.InProcessEngine.require().why(dir, query)
                : cc.jumpkick.cli.engine.EngineClient.why(cc.jumpkick.engine.EnginePaths.current(), dir, query);
        if (report.error() != null) {
            CliOutput.err("jk why: " + report.error());
            return Exit.CONFIG;
        }
        if (report.matchNames().isEmpty()) {
            CliOutput.err("jk why: " + query + " is not in jk.lock");
            return 1;
        }

        CliOutput.out(Theme.active().gradientHeaderAnsi("Jk - Dependency Lookup"));
        for (int i = 0; i < report.matchNames().size(); i++) {
            CliOutput.out(Coords.module(report.matchNames().get(i), report.matchVersions().get(i))
                    + " is pulled in by:");
            boolean any = false;
            for (int j = 0; j < report.paths().size(); j++) {
                if (!report.pathOwners().get(j).equals(Integer.toString(i))) continue;
                any = true;
                CliOutput.out("  " + renderPath(report.paths().get(j)));
            }
            if (!any) {
                CliOutput.out("  (unreachable from declared dependencies — likely a stale lockfile entry)");
            }
            if (report.matchNames().size() > 1) CliOutput.out();
        }
        return 0;
    }

    /** Format a wire path ({@code module@version>module@version}) with colored coordinates. */
    private static String renderPath(String path) {
        return java.util.Arrays.stream(path.split(">"))
                .map(step -> {
                    int at = step.lastIndexOf('@');
                    return at > 0 ? Coords.module(step.substring(0, at), step.substring(at + 1)) : step;
                })
                .collect(
                        Collectors.joining(Theme.colorize(" -> ", Theme.active().darkGray())));
    }

    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "cc.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    /** Strip the version component if present; return arg unchanged when no colon. */
    private static String moduleOnly(String arg) {
        int first = arg.indexOf(':');
        if (first < 0) return arg;
        int second = arg.indexOf(':', first + 1);
        return second < 0 ? arg : arg.substring(0, second);
    }

}
