// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.config.DenyPolicyParser;
import dev.jkbuild.deny.PolicyChecker;
import dev.jkbuild.engine.protocol.DenyReport;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine-hosted {@code jk deny} check (thin-client contract): parse the {@code [deny]} block, read
 * the lock, run the checker — all engine-side, because the policy lives in user-authored jk.toml
 * and a fail-soft client-side scan that misread exotic TOML would degrade to a silently
 * <em>permissive</em> policy, the one wrong answer a gate must never give.
 */
public final class PolicyOps {

    private PolicyOps() {}

    public static DenyReport denyCheck(Path dir) {
        try {
            var policy = DenyPolicyParser.parse(dir.resolve("jk.toml"));
            Lockfile lock = LockfileReader.read(dir.resolve("jk.lock"));
            List<PolicyChecker.Violation> violations = new PolicyChecker(policy).check(lock);
            List<String> modules = new ArrayList<>(violations.size());
            List<String> versions = new ArrayList<>(violations.size());
            List<String> reasons = new ArrayList<>(violations.size());
            for (PolicyChecker.Violation v : violations) {
                modules.add(v.module());
                versions.add(v.version());
                reasons.add(v.reason());
            }
            return new DenyReport(null, lock.artifacts().size(), modules, versions, reasons);
        } catch (IOException | RuntimeException e) {
            return DenyReport.error(String.valueOf(e.getMessage()));
        }
    }
}
