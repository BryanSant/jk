// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.util.TreeFingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** {@code jk jdk reconcile} — report and optionally prune JDK installs. */
public final class JdkReconcileCommand implements CliCommand {

    @Override public String name() { return "reconcile"; }
    @Override public String description() { return "Report and (optionally) prune JDK installs"; }

    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir").hide(),
                Opt.flag("Delete empty or bin/java-less directories that look orphaned.", "--prune"),
                Opt.flag("SHA-256 every file under each install and print a tree fingerprint. Slow.", "--verify"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        boolean prune = in.isSet("prune");
        boolean verify = in.isSet("verify");
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Path root = registry.jdksRoot();
        if (!Files.isDirectory(root)) { System.out.println("(no JDK directory at " + root + ")"); return 0; }
        List<InstalledJdk> jdks = registry.list();
        int healthy = 0, pruned = 0, verified = 0;
        List<String> reported = new ArrayList<>();

        for (InstalledJdk jdk : jdks) {
            if (verify) {
                String fingerprint = TreeFingerprint.compute(jdk.home());
                Path marker = root.resolve(jdk.identifier() + ".fingerprint");
                Files.writeString(marker, fingerprint);
                System.out.println("ok:       " + jdk.identifier() + " (sha256-tree=" + fingerprint.substring(0, 12) + "…)");
                verified++;
            } else {
                System.out.println("ok:       " + jdk.identifier());
            }
            healthy++;
        }

        try (Stream<Path> stream = Files.list(root)) {
            List<Path> orphans = stream
                    .filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !Files.isDirectory(IntellijJdkDir.javaHome(p).resolve("bin")))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
            for (Path orphan : orphans) {
                if (prune) { deleteRecursively(orphan); System.out.println("pruned:   " + orphan.getFileName()); pruned++; }
                else { System.out.println("broken:   " + orphan.getFileName() + " (no bin/java — pass --prune to delete)"); reported.add(orphan.toString()); }
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
            stream.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
