// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.compat.BuildTool;
import dev.jkbuild.compat.InstalledTool;
import dev.jkbuild.discovery.SymlinkProvisioner;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk doctor} — repair the build-tool install tree under
 * {@code $JK_CACHE_DIR/tools/<slug>/} (mvn, gradle, kotlin). Walks every
 * registered build tool, prunes broken symlinks, and optionally
 * fingerprints linked installs with {@code --verify-linked}.
 *
 * <p>Was {@code jk tool reconcile} pre-v1.0; renamed when {@code jk
 * tool} took over CLI-tool lifecycle. {@code doctor} signals "this is a
 * maintenance verb" rather than "this is a noun you install."
 * {@code jk jdk reconcile} remains the parallel verb for JDKs.
 */
@Command(name = "doctor",
        description = "Repair discovered mvn/gradle/kotlin installs")
public final class DoctorCommand implements Callable<Integer> {

    @Option(names = "--tools-dir", hidden = true,
            description = "Override the tools install root. Default: $JK_CACHE_DIR/tools.")
    Path toolsDir;

    @Option(names = "--verify-linked",
            description = "SHA-256 every file under each linked install; record a fingerprint.")
    boolean verifyLinked;

    @Override
    public Integer call() throws IOException {
        Path root = toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools");

        int healthy = 0;
        int pruned = 0;
        int verified = 0;

        for (BuildTool tool : BuildTool.values()) {
            for (InstalledTool installed : listIncludingBrokenLinks(root, tool)) {
                Path home = installed.home();
                String label = tool.slug() + " " + installed.version();
                if (SymlinkProvisioner.isBrokenLink(home)) {
                    String wasPointingAt = readlinkSafe(home);
                    SymlinkProvisioner.unlink(home);
                    pruned++;
                    System.out.println("pruned: " + label
                            + " (link target missing: " + wasPointingAt + ")");
                    continue;
                }
                if (Files.isSymbolicLink(home) && verifyLinked) {
                    String fingerprint = TreeFingerprint.compute(home);
                    Path marker = root.resolve(tool.slug()).resolve(installed.version() + ".fingerprint");
                    Files.writeString(marker, fingerprint);
                    verified++;
                    System.out.println("verified: " + label
                            + " (sha256-tree=" + fingerprint.substring(0, 12) + "…)");
                } else if (Files.isSymbolicLink(home)) {
                    System.out.println("linked:   " + label
                            + " → " + readlinkSafe(home));
                } else {
                    System.out.println("ok:       " + label);
                }
                healthy++;
            }
        }

        System.out.println("---");
        System.out.println(healthy + " healthy"
                + (pruned > 0 ? ", " + pruned + " pruned" : "")
                + (verified > 0 ? ", " + verified + " fingerprinted" : ""));
        return 0;
    }

    /**
     * The registry's normal {@code list} filters by {@code Files.isDirectory},
     * which skips broken symlinks — exactly the entries doctor needs to see.
     * Walk the filesystem directly to include them.
     */
    private static List<InstalledTool> listIncludingBrokenLinks(Path root, BuildTool tool)
            throws IOException {
        Path slugDir = root.resolve(tool.slug());
        if (!Files.exists(slugDir)) return List.of();
        List<InstalledTool> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(slugDir)) {
            stream.forEach(path -> {
                if (Files.isDirectory(path) || Files.isSymbolicLink(path)) {
                    result.add(new InstalledTool(tool, path.getFileName().toString(), path));
                }
            });
        }
        return result;
    }

    private static String readlinkSafe(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException e) {
            return "?";
        }
    }
}
