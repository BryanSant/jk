// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.lock;

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
        out.append("resolution-algorithm = ").append(quote(lockfile.resolutionAlgorithm())).append('\n');

        List<Lockfile.Package> sorted = new ArrayList<>(lockfile.packages());
        sorted.sort(Comparator
                .comparing(Lockfile.Package::name)
                .thenComparing(Lockfile.Package::version));

        for (Lockfile.Package pkg : sorted) {
            out.append('\n');
            out.append("[[package]]\n");
            out.append("name     = ").append(quote(pkg.name())).append('\n');
            out.append("version  = ").append(quote(pkg.version())).append('\n');
            out.append("source   = ").append(quote(pkg.source())).append('\n');
            if (pkg.checksum() != null) {
                out.append("checksum = ").append(quote(pkg.checksum())).append('\n');
            }
            if (pkg.path() != null) {
                out.append("path     = ").append(quote(pkg.path())).append('\n');
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
        return out.toString();
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
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
