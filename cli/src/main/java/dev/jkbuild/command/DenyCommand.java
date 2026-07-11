// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.engine.InProcessEngine;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.engine.protocol.DenyReport;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code jk deny} — apply the jk.toml {@code deny} policy against the locked dependencies (PRD
 * §23.6). Exits non-zero on any violation. The policy parse, lock read, and check run engine-side
 * (thin-client contract): the {@code [deny]} block is user-authored jk.toml, and a fail-soft
 * client-side scan that misread exotic TOML would degrade to a silently permissive policy — the
 * one wrong answer a gate must never give.
 */
public final class DenyCommand implements CliCommand {

    @Override
    public String name() {
        return "deny";
    }

    @Override
    public String description() {
        return "Apply the project's license / source / yanked policy";
    }

    private static final GoalKey<DenyReport> REPORT = GoalKey.of("deny-report", DenyReport.class);

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path projectDir = global.workingDir();
        Path jkBuild = projectDir.resolve("jk.toml");
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(jkBuild)) {
            CliOutput.err("jk deny: " + jkBuild + " not found.");
            return Exit.NO_INPUT;
        }
        if (!Files.exists(lockPath)) {
            CliOutput.err("jk deny: no jk.lock in "
                    + dev.jkbuild.cli.PathDisplay.styledRaw(projectDir)
                    + " (run `jk lock` first).");
            return Exit.CONFIG;
        }
        Path cache = JkDirs.cache();

        Phase check = Phase.builder("check")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("check policy against lock");
                    DenyReport report = engineDisabledForTests()
                            ? InProcessEngine.require().denyCheck(projectDir)
                            : EngineClient.denyCheck(EnginePaths.current(), projectDir);
                    if (report.error() != null) throw new IOException(report.error());
                    ctx.put(REPORT, report);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("deny").addPhase(check).build();
        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) return 1;

        DenyReport report = goal.get(REPORT).orElseThrow();
        if (report.violationCount() == 0) {
            if (!global.outputIsJson())
                CliOutput.out("jk deny: " + report.checked() + " package(s) checked — no violations.");
            return 0;
        }
        CliOutput.err("jk deny: " + report.violationCount() + " violation(s):");
        for (int i = 0; i < report.violationCount(); i++) {
            CliOutput.err("  " + Coords.module(report.modules().get(i), report.versions().get(i)) + " — "
                    + report.reasons().get(i));
        }
        return 1;
    }

    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }
}
