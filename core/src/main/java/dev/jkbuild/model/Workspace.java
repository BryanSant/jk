// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.List;
import java.util.Objects;

/**
 * The {@code workspace} block of a root {@code jk.toml}. v0.3 first
 * iteration: literal member paths (no globs), no excludes, no default
 * members. Globs, {@code default-members}, {@code exclude}, and
 * {@code substitute} arrive incrementally as their callers come online.
 */
public record Workspace(List<String> members) {

    public Workspace {
        Objects.requireNonNull(members, "members");
        members = List.copyOf(members);
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }
}
