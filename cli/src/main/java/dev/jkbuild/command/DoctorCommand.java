// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.compat.BuildTool;
import dev.jkbuild.compat.InstalledTool;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.discovery.SymlinkProvisioner;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.util.TreeFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code jk doctor} — repair the build-tool install tree under {@code $JK_CACHE_DIR/tools/<slug>/}
 * (mvn, gradle, kotlin). Walks every registered build tool, prunes broken symlinks, and optionally
 * fingerprints linked installs with {@code --verify-linked}.
 */
public final class DoctorCommand implements CliCommand {

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String description() {
        return "Repair discovered mvn/gradle/kotlin installs";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root. Default: $JK_CACHE_DIR/tools.", "--tools-dir")
                        .hide(),
                Opt.flag("Fingerprint each linked install (SHA-256 every file)", "--verify-linked"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        boolean verifyLinked = in.isSet("verify-linked");
        Path root = toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools");

        int healthy = 0, pruned = 0, verified = 0;
        Theme t = Theme.active();
        for (BuildTool tool : BuildTool.values()) {
            for (InstalledTool installed : listIncludingBrokenLinks(root, tool)) {
                Path home = installed.home();
                String toolName = Theme.colorize(tool.slug(), t.cyan());
                String label = toolName + " " + installed.version();
                if (SymlinkProvisioner.isBrokenLink(home)) {
                    String wasPointingAt = readlinkSafe(home);
                    SymlinkProvisioner.unlink(home);
                    pruned++;
                    System.out.println(Theme.colorize("pruned:  ", t.warning()) + " " + label
                            + " (link target missing: " + Theme.colorize(wasPointingAt, t.path()) + ")");
                    continue;
                }
                if (Files.isSymbolicLink(home) && verifyLinked) {
                    String fingerprint = TreeFingerprint.compute(home);
                    Path marker = root.resolve(tool.slug()).resolve(installed.version() + ".fingerprint");
                    Files.writeString(marker, fingerprint);
                    verified++;
                    System.out.println(Theme.colorize("verified:", t.completedStep()) + " " + label
                            + " (sha256-tree=" + fingerprint.substring(0, 12) + "…)");
                } else if (Files.isSymbolicLink(home)) {
                    System.out.println("linked:   " + label
                            + " " + Theme.colorize("→", t.darkGray()) + " "
                            + Theme.colorize(readlinkSafe(home), t.path()));
                } else {
                    System.out.println(Theme.colorize("ok:      ", t.completedStep()) + " " + label);
                }
                healthy++;
            }
        }
        System.out.println(Theme.colorize("---", t.darkGray()));
        System.out.println(Theme.colorize(String.valueOf(healthy), t.focused())
                + " healthy"
                + (pruned > 0 ? ", " + Theme.colorize(String.valueOf(pruned), t.focused()) + " pruned" : "")
                + (verified > 0 ? ", " + Theme.colorize(String.valueOf(verified), t.focused()) + " fingerprinted" : ""));
        return 0;
    }

    private static List<InstalledTool> listIncludingBrokenLinks(Path root, BuildTool tool) throws IOException {
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
