// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.BuildJk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads every member's {@code build.jk} for a workspace root.
 *
 * <p>v0.3 first iteration: literal member paths only (no globs).
 * Each entry in {@code workspace.members} must resolve to a directory
 * containing a {@code build.jk}. Missing members raise
 * {@link BuildJkParseException}.
 */
public final class WorkspaceLoader {

    private WorkspaceLoader() {}

    public static Map<Path, BuildJk> loadMembers(Path workspaceRoot, BuildJk root) throws IOException {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(root, "root");
        if (!root.isWorkspaceRoot()) return Map.of();

        Map<Path, BuildJk> members = new LinkedHashMap<>();
        List<String> bad = new ArrayList<>();
        for (String member : root.workspace().members()) {
            Path memberDir = workspaceRoot.resolve(member).normalize();
            Path memberBuildJk = memberDir.resolve("build.jk");
            if (!Files.exists(memberBuildJk)) {
                bad.add(member);
                continue;
            }
            members.put(memberDir, BuildJkParser.parse(memberBuildJk));
        }
        if (!bad.isEmpty()) {
            throw new BuildJkParseException(
                    "workspace members missing build.jk: " + bad);
        }
        return members;
    }
}
