// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.util.TreeFingerprint;

import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk jdk reconcile} — walk the IntelliJ JDK directory and report
 * which entries look healthy, which look broken (an empty or
 * {@code bin}-less directory), and optionally fingerprint each install
 * with {@code --verify} so a reproducibility audit can compare them.
 *
 * <p>Broken entries are pruned only with {@code --prune} so the command
 * is safe to run by hand in IntelliJ's directory.
 */
@Command(name = "reconcile", description = "Report and (optionally) prune JDK installs")
public final class JdkReconcileCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Option(names = "--prune",
            description = "Delete empty or bin/java-less directories that look orphaned.")
    boolean prune;

    @Option(names = "--verify",
            description = "SHA-256 every file under each install and print a tree fingerprint. Slow.")
    boolean verify;

    @Override
    public Integer call() throws IOException {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Path root = registry.jdksRoot();
        if (!Files.isDirectory(root)) {
            System.out.println("(no JDK directory at " + root + ")");
            return 0;
        }
        List<InstalledJdk> jdks = registry.list();

        int healthy = 0;
        int pruned = 0;
        int verified = 0;
        List<String> reported = new ArrayList<>();

        // Healthy installs: registry already filters out dirs without a `bin/`.
        for (InstalledJdk jdk : jdks) {
            if (verify) {
                String fingerprint = TreeFingerprint.compute(jdk.home());
                System.out.println("ok:       " + jdk.identifier()
                        + " (sha256-tree=" + fingerprint.substring(0, 12) + "…)");
                verified++;
            } else {
                System.out.println("ok:       " + jdk.identifier());
            }
            healthy++;
        }

        // Suspect entries: present on disk, no bin/java.
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> orphans = stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !Files.isDirectory(IntellijJdkDir.javaHome(p).resolve("bin")))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
            for (Path orphan : orphans) {
                if (prune) {
                    deleteRecursively(orphan);
                    System.out.println("pruned:   " + orphan.getFileName());
                    pruned++;
                } else {
                    System.out.println("broken:   " + orphan.getFileName()
                            + " (no bin/java — pass --prune to delete)");
                    reported.add(orphan.toString());
                }
            }
        }

        System.out.println("---");
        System.out.println(healthy + " healthy"
                + (pruned > 0 ? ", " + pruned + " pruned" : "")
                + (verified > 0 ? ", " + verified + " fingerprinted" : "")
                + (!reported.isEmpty() ? ", " + reported.size() + " broken" : ""));
        return reported.isEmpty() ? 0 : 1;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
