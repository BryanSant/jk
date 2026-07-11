// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.compat.ImportReport;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.engine.protocol.GeneratedFiles;
import dev.jkbuild.gradle.GradleExporter;
import dev.jkbuild.layout.SourceLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.mvn.PomExporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-hosted full-model generators (thin-client contract): {@code jk export maven}/{@code
 * gradle} need the parsed root + every workspace module + merged locked versions, so content
 * generation runs engine-side; the client applies overwrite guards, writes the payloads, and owns
 * all console output.
 */
public final class GenerateOps {

    private GenerateOps() {}

    public static GeneratedFiles generate(Path dir, String kind) {
        return generate(dir, kind, Map.of());
    }

    public static GeneratedFiles generate(Path dir, String kind, Map<String, String> params) {
        try {
            return switch (kind) {
                case "export-maven" -> exportMaven(dir);
                case "export-gradle" -> exportGradle(dir);
                case "scaffold" -> ScaffoldOps.scaffold(dir, params);
                default -> GeneratedFiles.error("unknown generate kind: " + kind);
            };
        } catch (IOException | RuntimeException e) {
            return GeneratedFiles.error(String.valueOf(e.getMessage()));
        }
    }

    private static GeneratedFiles exportMaven(Path dir) throws IOException {
        Loaded loaded = load(dir);
        List<String> paths = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        PomExporter.Result rootResult =
                PomExporter.export(loaded.root(), resolveLayout(dir, loaded.root()), loaded.locked());
        paths.add(dir.resolve("pom.xml").toString());
        contents.add(rootResult.xml());
        addNotes(notes, rootResult.report());

        for (Map.Entry<Path, JkBuild> e : loaded.modules().entrySet()) {
            Map<String, String> moduleLocked = lockedVersions(e.getKey());
            PomExporter.Result r = PomExporter.export(
                    e.getValue(),
                    resolveLayout(e.getKey(), e.getValue()),
                    moduleLocked.isEmpty() ? loaded.locked() : moduleLocked);
            paths.add(e.getKey().resolve("pom.xml").toString());
            contents.add(r.xml());
            addNotes(notes, r.report());
        }
        return new GeneratedFiles(null, paths, contents, notes);
    }

    private static GeneratedFiles exportGradle(Path dir) throws IOException {
        Loaded loaded = load(dir);

        Map<String, JkBuild> byRel = new LinkedHashMap<>();
        Map<String, JkBuild.Layout> layoutByRel = new LinkedHashMap<>();
        layoutByRel.put("", resolveLayout(dir, loaded.root()));
        for (Map.Entry<Path, JkBuild> e : loaded.modules().entrySet()) {
            String rel = dir.relativize(e.getKey()).toString().replace('\\', '/');
            if (rel.isBlank()) continue;
            byRel.put(rel, e.getValue());
            layoutByRel.put(rel, resolveLayout(e.getKey(), e.getValue()));
        }
        GradleExporter.Result result = GradleExporter.export(loaded.root(), byRel, layoutByRel, loaded.locked());

        List<String> paths = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        paths.add(dir.resolve("settings.gradle.kts").toString());
        contents.add(result.settings());
        for (Map.Entry<String, String> e : result.buildFiles().entrySet()) {
            paths.add(dir.resolve(e.getKey()).resolve("build.gradle.kts").toString());
            contents.add(e.getValue());
        }
        addNotes(notes, result.report());
        return new GeneratedFiles(null, paths, contents, notes);
    }

    private record Loaded(JkBuild root, Map<Path, JkBuild> modules, Map<String, String> locked) {}

    /** The export view: parsed root (+ workspace modules) and merged locked versions. */
    private static Loaded load(Path dir) throws IOException {
        Path toml = dir.resolve("jk.toml");
        if (!Files.exists(toml)) {
            throw new IOException("no jk.toml in " + dir);
        }
        JkBuild root = JkBuildParser.parse(toml);
        Map<Path, JkBuild> modules = root.isWorkspaceRoot() ? WorkspaceLoader.loadModules(dir, root) : Map.of();
        Map<String, String> locked = new LinkedHashMap<>(lockedVersions(dir));
        for (Path moduleDir : modules.keySet()) {
            lockedVersions(moduleDir).forEach(locked::putIfAbsent);
        }
        return new Loaded(root, modules, locked);
    }

    private static Map<String, String> lockedVersions(Path dir) {
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

    private static JkBuild.Layout resolveLayout(Path dir, JkBuild b) {
        return SourceLayout.isSimpleLayout(b.project(), dir) ? JkBuild.Layout.SIMPLE : JkBuild.Layout.TRADITIONAL;
    }

    private static void addNotes(List<String> notes, ImportReport report) {
        for (ImportReport.Issue issue : report.issues()) {
            notes.add((issue.severity() == ImportReport.Severity.ERROR ? "error" : "warning") + "|"
                    + issue.message());
        }
    }
}
