// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.http.Http;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.util.JkDirs;
import org.jline.utils.AttributedStyle;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * {@code jk library update} — pull the latest library catalog from
 * {@code github.com/jkbuild/jk-library-registry} and replace the cached copy
 * at {@code ~/.jk/libs.global.toml}.
 *
 * <p>The upstream is unsigned for now (signing is on the roadmap). The
 * downloaded payload is validated by parsing it; a malformed response
 * leaves the previous cached copy untouched.
 *
 * <p>No version pinning. Every {@code jk library update} pulls
 * {@code main} HEAD — globally consistent, per the catalog's curation
 * policy.
 */
@Command(name = "update", description = "Fetch the latest library catalog")
public final class LibraryUpdateCommand implements Callable<Integer> {

    /** Upstream URL — the catalog's source of truth. Override via {@code --source} for tests. */
    static final URI DEFAULT_SOURCE = URI.create(
            "https://raw.githubusercontent.com/jkbuild/jk-library-registry/refs/heads/main/libraries.toml");

    @Option(names = "--source", hidden = true,
            description = "Override the upstream URL (used by tests; not part of the public CLI).")
    URI source = DEFAULT_SOURCE;

    @Option(names = "--cache-file", hidden = true,
            description = "Override the local cache path (used by tests).")
    Path cacheFileOverride;

    @Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException, InterruptedException {
        long startNanos = System.nanoTime();
        Path cacheFile = cacheFileOverride != null ? cacheFileOverride
                : LibraryCatalog.downloadedFile();
        Path previousBackup = cacheFile.resolveSibling(cacheFile.getFileName() + ".prev");

        Map<String, LibraryCatalog.Module> before = currentEntries(cacheFile);

        HttpResponse<byte[]> response;
        try {
            response = new Http().get(source);
        } catch (IOException e) {
            System.err.println("jk library update: failed to reach " + source);
            System.err.println("  " + e.getMessage());
            return 1;
        }
        if (response.statusCode() != 200) {
            System.err.println("jk library update: HTTP " + response.statusCode()
                    + " from " + source);
            return 1;
        }
        String body = new String(response.body(), StandardCharsets.UTF_8);

        // Parse before writing — a malformed payload should never replace
        // a good cached copy.
        Map<String, LibraryCatalog.Module> after;
        try {
            after = materialise(body);
        } catch (RuntimeException e) {
            System.err.println("jk library update: refusing to replace cache — "
                    + "upstream payload did not validate:");
            System.err.println("  " + e.getMessage());
            return 1;
        }

        Files.createDirectories(cacheFile.getParent());
        if (Files.exists(cacheFile)) {
            Files.copy(cacheFile, previousBackup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(cacheFile, body, StandardCharsets.UTF_8);

        Diff diff = Diff.compute(before, after);
        printSummary(after.size(), diff, Duration.ofNanos(System.nanoTime() - startNanos));
        return 0;
    }

    private static Map<String, LibraryCatalog.Module> currentEntries(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) return Map.of();
        try {
            return materialise(Files.readString(cacheFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /** Parse a TOML payload's {@code [libraries]} table into a flat map. */
    private static Map<String, LibraryCatalog.Module> materialise(String body) {
        LibraryCatalog parsed = LibraryCatalog.parse(body);
        Map<String, LibraryCatalog.Module> out = new java.util.LinkedHashMap<>();
        for (String name : parsed.names()) {
            parsed.lookup(name).ifPresent(m -> out.put(name, m));
        }
        return out;
    }

    private void printSummary(int total, Diff diff, Duration elapsed) {
        // Header: green banner, bold count, dim elapsed — matching jk's
        // build/test result lines.
        System.out.println(
                Theme.colorize("✓ Library catalog updated", Theme.active().completedStep())
                        + " — " + Theme.colorize(String.valueOf(total), AttributedStyle.DEFAULT.bold())
                        + " entries cached "
                        + Theme.colorize("in " + dev.jkbuild.cli.run.ConsoleSpec.fmtDuration(elapsed),
                                Theme.active().darkGray()));
        if (diff.isEmpty()) {
            System.out.println();
            System.out.println("  (no changes from previous version)");
            return;
        }
        emitList("Added", diff.added, Theme.active().completedStep());
        emitList("Removed", diff.removed, Theme.active().error());
        emitList("Changed", diff.changed, Theme.active().warning());
    }

    /** Print a blank-line-separated section: a colored label with a count, then up to 10 items. */
    private static void emitList(String label, List<String> items, AttributedStyle labelStyle) {
        if (items.isEmpty()) return;
        System.out.println();
        System.out.println("  " + Theme.colorize(label, labelStyle) + ": " + items.size());
        int shown = Math.min(items.size(), 10);
        for (int i = 0; i < shown; i++) {
            System.out.println("    " + items.get(i));
        }
        if (items.size() > shown) {
            System.out.println("    … and " + (items.size() - shown) + " more");
        }
    }

    private record Diff(List<String> added, List<String> removed, List<String> changed) {
        static Diff compute(
                Map<String, LibraryCatalog.Module> before,
                Map<String, LibraryCatalog.Module> after) {
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            List<String> changed = new ArrayList<>();
            for (Iterator<String> it = new TreeSet<>(after.keySet()).iterator(); it.hasNext();) {
                String name = it.next();
                LibraryCatalog.Module b = before.get(name);
                LibraryCatalog.Module a = after.get(name);
                if (b == null) added.add(Coords.shortName(name) + " → " + Coords.module(a.moduleKey()));
                else if (!b.equals(a)) changed.add(Coords.shortName(name) + ": "
                        + Coords.module(b.moduleKey()) + " → " + Coords.module(a.moduleKey()));
            }
            for (String name : new TreeSet<>(before.keySet())) {
                if (!after.containsKey(name)) removed.add(Coords.shortName(name));
            }
            return new Diff(added, removed, changed);
        }

        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }
    }
}
