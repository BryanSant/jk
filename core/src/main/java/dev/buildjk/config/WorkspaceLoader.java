// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.config;

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
 * Loads every member's {@code jk.toml} for a workspace root.
 *
 * <p>Literal member paths only (no globs). Each entry in
 * {@code workspace.members} must resolve to a directory containing a
 * {@code jk.toml}. Missing members raise {@link BuildJkParseException}.
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
            Path memberJkToml = memberDir.resolve("jk.toml");
            if (!Files.exists(memberJkToml)) {
                bad.add(member);
                continue;
            }
            members.put(memberDir, BuildJkParser.parse(memberJkToml));
        }
        if (!bad.isEmpty()) {
            throw new BuildJkParseException(
                    "workspace members missing jk.toml: " + bad);
        }
        return members;
    }
}
