// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.deny.PolicyChecker;
import dev.buildjk.config.DenyPolicyParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.DenyPolicy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk deny} — apply the build.jk {@code deny} policy against the
 * locked dependencies (PRD §23.6). Exits non-zero on any violation.
 */
@Command(name = "deny", description = "Apply the project's license / source / yanked policy")
public final class DenyCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Override
    public Integer call() throws IOException {
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildJk = projectDir.resolve("jk.toml");
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(buildJk)) {
            System.err.println("jk deny: " + buildJk + " not found.");
            return 66;
        }
        if (!Files.exists(lockPath)) {
            System.err.println("jk deny: no jk.lock in " + projectDir
                    + " (run `jk lock` first).");
            return 2;
        }

        DenyPolicy policy = DenyPolicyParser.parse(buildJk);
        Lockfile lock = LockfileReader.read(lockPath);
        List<PolicyChecker.Violation> violations = new PolicyChecker(policy).check(lock);

        if (violations.isEmpty()) {
            System.out.println("jk deny: " + lock.packages().size()
                    + " package(s) checked — no violations.");
            return 0;
        }
        System.err.println("jk deny: " + violations.size() + " violation(s):");
        for (PolicyChecker.Violation v : violations) {
            System.err.println("  " + v.module() + ":" + v.version() + " — " + v.reason());
        }
        return 1;
    }
}
