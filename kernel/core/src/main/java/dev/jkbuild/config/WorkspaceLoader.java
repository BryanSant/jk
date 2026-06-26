// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads every module's {@code jk.toml} for a workspace root.
 *
 * <p>Literal module paths only (no globs). Each entry in
 * {@code workspace.modules} must resolve to a directory containing a
 * {@code jk.toml}. Missing modules raise {@link JkBuildParseException}.
 */
public final class WorkspaceLoader {

    private WorkspaceLoader() {}

    public static Map<Path, JkBuild> loadModules(Path workspaceRoot, JkBuild root) throws IOException {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(root, "root");
        if (!root.isWorkspaceRoot()) return Map.of();

        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        List<String> bad = new ArrayList<>();
        for (String module : root.workspace().modules()) {
            Path moduleDir = workspaceRoot.resolve(module).normalize();
            Path moduleJkToml = moduleDir.resolve("jk.toml");
            if (!Files.exists(moduleJkToml)) {
                bad.add(module);
                continue;
            }
            JkBuild moduleBuild = JkBuildParser.parse(moduleJkToml);
            // Workspaces cannot be nested — a module declaring its own
            // [workspace] block would imply two competing roots and an
            // ambiguous shared <root>/target/. Reject loudly so the
            // user fixes the manifest rather than discovers it at build
            // time when paths quietly diverge.
            if (moduleBuild.isWorkspaceRoot()) {
                throw new JkBuildParseException("workspaces cannot be nested — `" + module
                        + "` declares its own `[workspace]` block "
                        + "while being a module of `" + workspaceRoot + "`.");
            }
            modules.put(moduleDir, moduleBuild);
        }
        if (!bad.isEmpty()) {
            throw new JkBuildParseException("workspace modules missing jk.toml: " + bad);
        }
        checkArtifactCollisions(root, modules);
        return modules;
    }

    /**
     * Final artifacts land in a shared {@code <workspaceRoot>/target/}
     * directory keyed by {@code <artifact>-<version>.jar} — so two modules
     * declaring the same artifact + version would race to write the same
     * jar and the second one would silently win. Reject at workspace-parse
     * time so the failure is loud and the file paths in the error point
     * at the conflict.
     *
     * <p>Modules and the workspace root itself can collide (a workspace
     * root that's <i>also</i> a runnable project is rare but legal, so we
     * include the root in the uniqueness set).
     */
    private static void checkArtifactCollisions(JkBuild root, Map<Path, JkBuild> modules) {
        Map<String, Path> claimed = new LinkedHashMap<>();
        record Entry(Path dir, JkBuild build) {}
        List<Entry> all = new ArrayList<>(modules.size() + 1);
        // Only include the root if it could plausibly produce its own jar
        // (i.e., it declares a non-blank artifact). Many workspace roots
        // are pure coordinators with no own artifact; skip those.
        if (!root.project().name().isBlank()) {
            all.add(new Entry(null, root));
        }
        for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
            all.add(new Entry(e.getKey(), e.getValue()));
        }
        for (Entry e : all) {
            String key = e.build.project().name() + "-" + e.build.project().version();
            // containsKey, not the put return value: the workspace root
            // stores `null` as its dir, and Map.put can't distinguish a
            // returned null between "no prior entry" and "prior entry's
            // value was null".
            if (claimed.containsKey(key)) {
                Path previous = claimed.get(key);
                String prevLabel = previous == null ? "<workspace root>" : previous.toString();
                String thisLabel = e.dir == null ? "<workspace root>" : e.dir.toString();
                throw new JkBuildParseException("workspace artifact collision: `" + key + ".jar` would be "
                        + "produced by both `" + prevLabel + "` and `" + thisLabel
                        + "`. Final artifacts share <workspaceRoot>/target/, so two "
                        + "modules can't emit the same `<artifact>-<version>.jar`. "
                        + "Differentiate via the modules' [project].artifact or "
                        + "[project].version.");
            }
            claimed.put(key, e.dir);
        }
    }
}
