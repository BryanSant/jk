// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.http.Http;
import dev.jkbuild.registry.AliasRegistry;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * {@code jk registry update} — pull the latest alias registry from
 * {@code github.com/BryanSant/jk-registry} and replace the cached copy
 * at {@code ~/.jk/registry/aliases.toml}.
 *
 * <p>The upstream is unsigned for now (signing is on the roadmap). The
 * downloaded payload is validated by parsing it; a malformed response
 * leaves the previous cached copy untouched.
 *
 * <p>No version pinning. Every {@code jk registry update} pulls
 * {@code main} HEAD — globally consistent, per the registry's curation
 * policy.
 */
@Command(name = "update", description = "Fetch the latest alias registry")
public final class RegistryUpdateCommand implements Callable<Integer> {

    /** Upstream URL — the registry's source of truth. Override via {@code --source} for tests. */
    static final URI DEFAULT_SOURCE = URI.create(
            "https://raw.githubusercontent.com/BryanSant/jk-registry/main/aliases.toml");

    @Option(names = "--source", hidden = true,
            description = "Override the upstream URL (used by tests; not part of the public CLI).")
    URI source = DEFAULT_SOURCE;

    @Option(names = "--cache-file", hidden = true,
            description = "Override the local cache path (used by tests).")
    Path cacheFileOverride;

    @Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path cacheFile = cacheFileOverride != null ? cacheFileOverride
                : JkDirs.home().resolve("registry").resolve(AliasRegistry.FILE_NAME);
        Path previousBackup = cacheFile.resolveSibling(AliasRegistry.FILE_NAME + ".prev");

        Map<String, AliasRegistry.Module> before = currentEntries(cacheFile);

        HttpResponse<byte[]> response;
        try {
            response = new Http().get(source);
        } catch (IOException e) {
            System.err.println("jk registry update: failed to reach " + source);
            System.err.println("  " + e.getMessage());
            return 1;
        }
        if (response.statusCode() != 200) {
            System.err.println("jk registry update: HTTP " + response.statusCode()
                    + " from " + source);
            return 1;
        }
        String body = new String(response.body(), StandardCharsets.UTF_8);

        // Parse before writing — a malformed payload should never replace
        // a good cached copy.
        Map<String, AliasRegistry.Module> after;
        try {
            after = materialise(body);
        } catch (RuntimeException e) {
            System.err.println("jk registry update: refusing to replace cache — "
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
        printSummary(cacheFile, after.size(), diff);
        return 0;
    }

    private static Map<String, AliasRegistry.Module> currentEntries(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) return Map.of();
        try {
            return materialise(Files.readString(cacheFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /** Parse a TOML payload's {@code [aliases]} table into a flat map. */
    private static Map<String, AliasRegistry.Module> materialise(String body) {
        AliasRegistry parsed = AliasRegistry.parse(body);
        Map<String, AliasRegistry.Module> out = new java.util.LinkedHashMap<>();
        for (String name : parsed.names()) {
            parsed.lookup(name).ifPresent(m -> out.put(name, m));
        }
        return out;
    }

    private void printSummary(Path cacheFile, int total, Diff diff) {
        System.out.println(Theme.colorize("✓", Theme.brightGreen().bold())
                + " registry updated — " + total + " entries cached at " + cacheFile);
        emitList("added", diff.added);
        emitList("removed", diff.removed);
        emitList("changed", diff.changed);
        if (diff.isEmpty()) {
            System.out.println("  (no changes from previous version)");
        }
    }

    private static void emitList(String label, List<String> items) {
        if (items.isEmpty()) return;
        System.out.println("  " + label + ": " + items.size());
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
                Map<String, AliasRegistry.Module> before,
                Map<String, AliasRegistry.Module> after) {
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            List<String> changed = new ArrayList<>();
            for (Iterator<String> it = new TreeSet<>(after.keySet()).iterator(); it.hasNext();) {
                String name = it.next();
                AliasRegistry.Module b = before.get(name);
                AliasRegistry.Module a = after.get(name);
                if (b == null) added.add(name + " → " + a.moduleKey());
                else if (!b.equals(a)) changed.add(name + ": " + b.moduleKey() + " → " + a.moduleKey());
            }
            for (String name : new TreeSet<>(before.keySet())) {
                if (!after.containsKey(name)) removed.add(name);
            }
            return new Diff(added, removed, changed);
        }

        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }
    }
}
