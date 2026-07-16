// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.http.Http;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.repo.LibraryRegistryClient;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import org.jline.utils.AttributedStyle;

/** {@code jk library update} — pull the latest library catalog. */
public final class LibraryUpdateCommand implements CliCommand {

    static final URI DEFAULT_SOURCE = LibraryRegistryClient.DEFAULT_SOURCE;

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String description() {
        return "Fetch the latest library catalog";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<url>", "Override the upstream URL (used by tests).", "--source")
                        .hide(),
                Opt.value("<file>", "Override the local cache path (used by tests).", "--cache-file")
                        .hide());
    }

    private URI source = DEFAULT_SOURCE;
    private Path cacheFileOverride;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.source = in.value("source").map(URI::create).orElse(DEFAULT_SOURCE);
        this.cacheFileOverride = in.value("cache-file").map(Path::of).orElse(null);

        long startNanos = System.nanoTime();
        Path cacheFile = cacheFileOverride != null ? cacheFileOverride : LibraryCatalog.downloadedFile();
        Path etagFile = LibraryCatalog.etagFileFor(cacheFile);
        Path previousBackup = cacheFile.resolveSibling(cacheFile.getFileName() + ".prev");
        Map<String, LibraryCatalog.Module> before = currentEntries(cacheFile);

        LibraryRegistryClient.Result result;
        try {
            result = new LibraryRegistryClient(new Http()).fetch(source, etagFile);
        } catch (IOException e) {
            CliOutput.err("jk library update: failed to reach " + source + "\n  " + e.getMessage());
            return 1;
        }
        if (result instanceof LibraryRegistryClient.Result.Unchanged) {
            printSummary(before.size(), Diff.compute(before, before), Duration.ofNanos(System.nanoTime() - startNanos));
            return 0;
        }
        LibraryRegistryClient.Result.Updated updated = (LibraryRegistryClient.Result.Updated) result;
        String body = new String(updated.body(), StandardCharsets.UTF_8);

        Map<String, LibraryCatalog.Module> after;
        try {
            after = materialise(body);
        } catch (RuntimeException e) {
            CliOutput.err("jk library update: refusing to replace cache — upstream payload did not validate:\n  "
                    + e.getMessage());
            return 1;
        }

        Files.createDirectories(cacheFile.getParent());
        if (Files.exists(cacheFile)) Files.copy(cacheFile, previousBackup, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(cacheFile, body, StandardCharsets.UTF_8);
        if (updated.etag() != null) {
            Files.writeString(etagFile, updated.etag(), StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(etagFile);
        }

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

    private static Map<String, LibraryCatalog.Module> materialise(String body) {
        LibraryCatalog parsed = LibraryCatalog.parse(body);
        Map<String, LibraryCatalog.Module> out = new LinkedHashMap<>();
        for (String name : parsed.names()) parsed.lookup(name).ifPresent(m -> out.put(name, m));
        return out;
    }

    private void printSummary(int total, Diff diff, Duration elapsed) {
        CliOutput.out(
                Theme.colorize("✓ Library catalog updated", Theme.active().completedStep())
                        + " — "
                        + Theme.colorize(String.valueOf(total), AttributedStyle.DEFAULT.bold())
                        + " entries cached "
                        + ConsoleSpec.took(elapsed));
        if (diff.isEmpty()) {
            CliOutput.out("\n  (no changes from previous version)");
            return;
        }
        emitList("Added", diff.added, Theme.active().completedStep());
        emitList("Removed", diff.removed, Theme.active().error());
        emitList("Changed", diff.changed, Theme.active().warning());
    }

    private static void emitList(String label, List<String> items, AttributedStyle labelStyle) {
        if (items.isEmpty()) return;
        CliOutput.out("\n  " + Theme.colorize(label, labelStyle) + ": " + items.size());
        int shown = Math.min(items.size(), 10);
        for (int i = 0; i < shown; i++) CliOutput.out("    " + items.get(i));
        if (items.size() > shown) CliOutput.out("    … and " + (items.size() - shown) + " more");
    }

    private record Diff(List<String> added, List<String> removed, List<String> changed) {
        static Diff compute(Map<String, LibraryCatalog.Module> before, Map<String, LibraryCatalog.Module> after) {
            List<String> added = new ArrayList<>(), removed = new ArrayList<>(), changed = new ArrayList<>();
            for (Iterator<String> it = new TreeSet<>(after.keySet()).iterator(); it.hasNext(); ) {
                String name = it.next();
                LibraryCatalog.Module b = before.get(name), a = after.get(name);
                if (b == null) added.add(Coords.shortName(name) + " → " + Coords.module(a.moduleKey()));
                else if (!b.equals(a))
                    changed.add(Coords.shortName(name)
                            + ": "
                            + Coords.module(b.moduleKey())
                            + " → "
                            + Coords.module(a.moduleKey()));
            }
            for (String name : new TreeSet<>(before.keySet()))
                if (!after.containsKey(name)) removed.add(Coords.shortName(name));
            return new Diff(added, removed, changed);
        }

        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }
    }
}
