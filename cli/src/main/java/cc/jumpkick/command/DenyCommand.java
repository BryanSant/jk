// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.InProcessEngine;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.DenyReport;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.Step;
import cc.jumpkick.util.JkDirs;
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

    private static final PipelineKey<DenyReport> REPORT = PipelineKey.of("deny-report", DenyReport.class);

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
                    + cc.jumpkick.cli.PathDisplay.styledRaw(projectDir)
                    + " (run `jk lock` first).");
            return Exit.CONFIG;
        }
        Path cache = JkDirs.cache();

        Step check = Step.builder("check")
                .ticks(1)
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

        Pipeline pipeline = Pipeline.builder("deny").addStep(check).build();
        PipelineResult result = PipelineConsole.run(pipeline, PipelineConsole.modeFor(global), cache);
        if (!result.success()) return 1;

        DenyReport report = pipeline.get(REPORT).orElseThrow();
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
                || "cc.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }
}
