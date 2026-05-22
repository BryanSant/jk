// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.discovery.SymlinkProvisioner;
import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk reconcile} — walk {@code ~/.jk/jdks/}, prune broken
 * symlinks, optionally fingerprint linked installs with {@code --verify-linked}.
 *
 * <p>Useful in post-checkout hooks (when the user nukes their SDKMAN and
 * jk's links go stale) and in CI fixtures.
 */
@Command(name = "reconcile", description = "Prune broken JDK links; report state.")
public final class JdkReconcileCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Option(names = "--verify-linked",
            description = "SHA-256 every file under each linked install; record a fingerprint "
                    + "for reproducibility tracking. Slow.")
    boolean verifyLinked;

    @Override
    public Integer call() throws IOException {
        Path root = jdksDir != null
                ? jdksDir : Path.of(System.getProperty("user.home"), ".jk", "jdks");
        JdkRegistry registry = new JdkRegistry(root);
        List<InstalledJdk> jdks = registry.list();

        int healthy = 0;
        int pruned = 0;
        int verified = 0;
        List<String> issues = new ArrayList<>();

        for (InstalledJdk jdk : jdks) {
            Path home = jdk.home();
            if (SymlinkProvisioner.isBrokenLink(home)) {
                String wasPointingAt = readlinkSafe(home);
                SymlinkProvisioner.unlink(home);
                pruned++;
                System.out.println("pruned: " + jdk.identifier()
                        + " (link target missing: " + wasPointingAt + ")");
                continue;
            }
            if (Files.isSymbolicLink(home) && verifyLinked) {
                String fingerprint = TreeFingerprint.compute(home);
                Path marker = root.resolve(jdk.identifier() + ".fingerprint");
                Files.writeString(marker, fingerprint);
                verified++;
                System.out.println("verified: " + jdk.identifier()
                        + " (sha256-tree=" + fingerprint.substring(0, 12) + "…)");
            } else if (Files.isSymbolicLink(home)) {
                System.out.println("linked:   " + jdk.identifier()
                        + " → " + readlinkSafe(home));
            } else {
                System.out.println("ok:       " + jdk.identifier());
            }
            healthy++;
        }

        System.out.println("---");
        System.out.println(healthy + " healthy"
                + (pruned > 0 ? ", " + pruned + " pruned" : "")
                + (verified > 0 ? ", " + verified + " fingerprinted" : ""));
        return issues.isEmpty() ? 0 : 1;
    }

    private static String readlinkSafe(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException e) {
            return "?";
        }
    }
}
