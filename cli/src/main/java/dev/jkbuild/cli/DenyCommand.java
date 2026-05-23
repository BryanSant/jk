// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.deny.PolicyChecker;
import dev.jkbuild.config.DenyPolicyParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.DenyPolicy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk deny} — apply the jk.toml {@code deny} policy against the
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
        Path jkBuild = projectDir.resolve("jk.toml");
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(jkBuild)) {
            System.err.println("jk deny: " + jkBuild + " not found.");
            return 66;
        }
        if (!Files.exists(lockPath)) {
            System.err.println("jk deny: no jk.lock in " + projectDir
                    + " (run `jk lock` first).");
            return 2;
        }

        DenyPolicy policy = DenyPolicyParser.parse(jkBuild);
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
