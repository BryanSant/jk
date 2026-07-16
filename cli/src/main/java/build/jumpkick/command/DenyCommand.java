// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.cli.engine.EngineClient;
import build.jumpkick.cli.engine.InProcessEngine;
import build.jumpkick.cli.run.PipelineConsole;
import build.jumpkick.cli.theme.Coords;
import build.jumpkick.engine.EnginePaths;
import build.jumpkick.engine.protocol.DenyReport;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineKey;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.Step;
import build.jumpkick.util.JkDirs;
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
                    + build.jumpkick.cli.PathDisplay.styledRaw(projectDir)
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
                || "build.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }
}
