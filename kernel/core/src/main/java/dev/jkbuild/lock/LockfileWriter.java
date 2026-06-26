// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.lock;

import dev.jkbuild.model.Scope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic TOML emitter for {@link Lockfile}. PRD §9.1 requirements:
 * sorted, LF newlines, two-space indent, no comments, terminal newline.
 *
 * <p>Hand-rolled rather than using a TOML library to guarantee output
 * stability across library upgrades.
 */
public final class LockfileWriter {

    private LockfileWriter() {}

    public static void write(Lockfile lockfile, Path file) throws IOException {
        Files.writeString(file, render(lockfile), StandardCharsets.UTF_8);
    }

    public static String render(Lockfile lockfile) {
        StringBuilder out = new StringBuilder(256);
        out.append("version = ").append(lockfile.version()).append('\n');
        out.append("generated-by = ").append(quote(lockfile.generatedBy())).append('\n');
        out.append("resolution-algorithm = ")
                .append(quote(lockfile.resolutionAlgorithm()))
                .append('\n');
        if (lockfile.jdk() != null) {
            out.append("jdk = ").append(quote(lockfile.jdk())).append('\n');
        }
        if (lockfile.kotlin() != null) {
            out.append("kotlin = ").append(quote(lockfile.kotlin())).append('\n');
        }

        List<Lockfile.Artifact> sorted = new ArrayList<>(lockfile.artifacts());
        sorted.sort(Comparator.comparing(Lockfile.Artifact::name).thenComparing(Lockfile.Artifact::version));

        for (Lockfile.Artifact pkg : sorted) {
            out.append('\n');
            out.append("[[artifact]]\n");
            out.append("name     = ").append(quote(pkg.name())).append('\n');
            out.append("version  = ").append(quote(pkg.version())).append('\n');
            out.append("source   = ").append(quote(pkg.source())).append('\n');
            if (pkg.checksum() != null) {
                out.append("checksum = ").append(quote(pkg.checksum())).append('\n');
            }
            if (pkg.sourcesChecksum() != null) {
                out.append("sources  = ").append(quote(pkg.sourcesChecksum())).append('\n');
            }
            if (pkg.pinnedBy() != null) {
                out.append("pinned-by = ").append(quote(pkg.pinnedBy())).append('\n');
            }
            if (pkg.path() != null) {
                out.append("path     = ").append(quote(pkg.path())).append('\n');
            }
            if (pkg.git() != null) {
                out.append("git      = ").append(quote(pkg.git().url())).append('\n');
                out.append("rev      = ").append(quote(pkg.git().rev())).append('\n');
                if (pkg.git().ref() != null) {
                    out.append("ref      = ").append(quote(pkg.git().ref())).append('\n');
                }
            }
            if (!pkg.scopes().isEmpty()) {
                List<Scope> sortedScopes = new ArrayList<>(pkg.scopes());
                sortedScopes.sort(Comparator.naturalOrder());
                out.append("scopes   = [");
                for (int i = 0; i < sortedScopes.size(); i++) {
                    if (i > 0) out.append(", ");
                    out.append(quote(sortedScopes.get(i).canonical()));
                }
                out.append("]\n");
            }
            if (!pkg.deps().isEmpty()) {
                List<String> deps = new ArrayList<>(pkg.deps());
                deps.sort(Comparator.naturalOrder());
                out.append("deps = [\n");
                for (String dep : deps) {
                    out.append("  ").append(quote(dep)).append(",\n");
                }
                out.append("]\n");
            }
        }

        List<Lockfile.PluginEntry> sortedPlugins = new ArrayList<>(lockfile.plugins());
        sortedPlugins.sort(
                Comparator.comparing(Lockfile.PluginEntry::coordinate).thenComparing(Lockfile.PluginEntry::version));

        for (Lockfile.PluginEntry p : sortedPlugins) {
            out.append('\n');
            out.append("[[plugin]]\n");
            out.append("coordinate = ").append(quote(p.coordinate())).append('\n');
            out.append("version    = ").append(quote(p.version())).append('\n');
            out.append("checksum   = ").append(quote(p.checksum())).append('\n');
        }

        return out.toString();
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
