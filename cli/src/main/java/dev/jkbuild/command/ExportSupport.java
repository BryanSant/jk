// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.compat.ImportReport;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.runtime.CompileSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared loading + locked-version + overwrite-guard helpers for the `jk export` subcommands. */
final class ExportSupport {

    private ExportSupport() {}

    /**
     * A loaded project (or workspace root) ready to export.
     *
     * @param locked merged {@code group:artifact}→version across the root and
     *               every module lock — unified workspace resolution means one
     *               version per module, so a flat map is faithful.
     */
    record Loaded(Path rootDir, JkBuild root, Map<Path, JkBuild> modules, Map<String, String> locked) {
        boolean isWorkspace() {
            return root.isWorkspaceRoot();
        }

        /** Modules keyed by their root-relative directory (for Gradle {@code include(...)}). */
        Map<String, JkBuild> modulesByRelPath() {
            Map<String, JkBuild> out = new LinkedHashMap<>();
            for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
                String rel = rootDir.relativize(e.getKey()).toString().replace('\\', '/');
                if (!rel.isBlank()) out.put(rel, e.getValue());
            }
            return out;
        }

        /** Concrete (AUTO-resolved) source layout per project, keyed like {@link #modulesByRelPath()} (root = ""). */
        Map<String, JkBuild.Layout> layoutByRelPath() {
            Map<String, JkBuild.Layout> out = new LinkedHashMap<>();
            out.put("", resolveLayout(rootDir, root));
            for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
                String rel = rootDir.relativize(e.getKey()).toString().replace('\\', '/');
                if (!rel.isBlank()) out.put(rel, resolveLayout(e.getKey(), e.getValue()));
            }
            return out;
        }
    }

    /** Resolve a project's concrete source layout (jk's own {@code AUTO} tree-probe rule). */
    static JkBuild.Layout resolveLayout(Path dir, JkBuild b) {
        return CompileSupport.isSimpleLayout(b.project(), dir) ? JkBuild.Layout.SIMPLE : JkBuild.Layout.TRADITIONAL;
    }

    /**
     * Parse the project at {@code startDir}; if it's a workspace root, also load
     * its modules. Versions come from {@code jk.lock} (root + each module) when
     * present. Returns {@code null} after printing an error when there's no
     * {@code jk.toml}.
     */
    static Loaded load(Path startDir, String cmd) throws IOException {
        Path toml = startDir.resolve("jk.toml");
        if (!Files.exists(toml)) {
            System.err.println(cmd + ": no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(startDir));
            return null;
        }
        JkBuild root;
        try {
            root = JkBuildParser.parse(toml);
        } catch (RuntimeException e) {
            System.err.println(cmd + ": " + e.getMessage());
            return null;
        }
        Map<Path, JkBuild> modules = root.isWorkspaceRoot() ? WorkspaceLoader.loadModules(startDir, root) : Map.of();

        Map<String, String> locked = new LinkedHashMap<>(lockedVersions(startDir));
        for (Path moduleDir : modules.keySet()) {
            lockedVersions(moduleDir).forEach(locked::putIfAbsent);
        }
        return new Loaded(startDir, root, modules, locked);
    }

    /** {@code group:artifact} → exact resolved version from {@code jk.lock}; empty when no lock. */
    static Map<String, String> lockedVersions(Path dir) {
        Path lock = dir.resolve("jk.lock");
        if (!Files.isRegularFile(lock)) return Map.of();
        try {
            Lockfile lf = LockfileReader.read(lock);
            Map<String, String> out = new LinkedHashMap<>();
            for (Lockfile.Artifact a : lf.artifacts()) {
                if (a.version() != null && !a.version().isBlank()) out.put(a.name(), a.version());
            }
            return out;
        } catch (Exception e) {
            return Map.of(); // corrupt/unreadable lock — fall back to declared selectors
        }
    }

    /** True if it's safe to write {@code target} (doesn't exist, or {@code force}); else prints + false. */
    static boolean canWrite(Path target, boolean force, String cmd) {
        if (Files.exists(target) && !force) {
            System.err.println(cmd + ": refusing to overwrite " + PathDisplay.styled(target) + " (use --force).");
            return false;
        }
        return true;
    }

    /** Print fidelity warnings/errors to stderr; return the count. */
    static int printReport(ImportReport report) {
        int n = 0;
        for (ImportReport.Issue issue : report.issues()) {
            String tag = issue.severity() == ImportReport.Severity.ERROR ? "error" : "note";
            System.err.println(Theme.colorize("  " + tag + ":", Theme.active().darkGray()) + " " + issue.message());
            n++;
        }
        return n;
    }

    static void wrote(Path path) {
        System.out.println(Theme.colorize("✓", Theme.active().success()) + " Wrote " + PathDisplay.styled(path));
    }
}
