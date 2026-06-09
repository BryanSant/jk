// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.config.DenyPolicyParser;
import dev.jkbuild.deny.PolicyChecker;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.DenyPolicy;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.util.JkDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk deny} — apply the jk.toml {@code deny} policy against the
 * locked dependencies (PRD §23.6). Exits non-zero on any violation.
 */
public final class DenyCommand implements CliCommand {

    @Override public String name() { return "deny"; }
    @Override public String description() { return "Apply the project's license / source / yanked policy"; }

    private static final GoalKey<DenyPolicy> POLICY = GoalKey.of("policy", DenyPolicy.class);
    private static final GoalKey<Lockfile> LOCK = GoalKey.of("lock", Lockfile.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> VIOLATIONS = GoalKey.of("violations", List.class);

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path projectDir = global.workingDir();
        Path jkBuild = projectDir.resolve("jk.toml");
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(jkBuild)) { System.err.println("jk deny: " + jkBuild + " not found."); return 66; }
        if (!Files.exists(lockPath)) {
            System.err.println("jk deny: no jk.lock in " + projectDir + " (run `jk lock` first)."); return 2;
        }
        Path cache = JkDirs.cache();

        Phase parsePolicy = Phase.builder("parse-policy").scope(1).execute(ctx -> {
            ctx.label("parse policy + lock");
            ctx.put(POLICY, DenyPolicyParser.parse(jkBuild));
            ctx.put(LOCK, LockfileReader.read(lockPath));
            ctx.progress(1);
        }).build();

        Phase check = Phase.builder("check").requires("parse-policy").scope(1).execute(ctx -> {
            Lockfile lock = ctx.require(LOCK);
            ctx.label("check " + lock.packages().size() + " packages");
            List<PolicyChecker.Violation> violations = new PolicyChecker(ctx.require(POLICY)).check(lock);
            ctx.put(VIOLATIONS, violations);
            ctx.progress(1);
        }).build();

        Goal goal = Goal.builder("deny").addPhase(parsePolicy).addPhase(check).build();
        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) return 1;

        @SuppressWarnings("unchecked")
        List<PolicyChecker.Violation> violations = (List<PolicyChecker.Violation>) goal.get(VIOLATIONS).orElse(List.of());
        Lockfile lock = goal.get(LOCK).orElseThrow();

        if (violations.isEmpty()) {
            if (!global.outputIsJson())
                System.out.println("jk deny: " + lock.packages().size() + " package(s) checked — no violations.");
            return 0;
        }
        System.err.println("jk deny: " + violations.size() + " violation(s):");
        for (PolicyChecker.Violation v : violations)
            System.err.println("  " + Coords.module(v.module(), v.version()) + " — " + v.reason());
        return 1;
    }
}
