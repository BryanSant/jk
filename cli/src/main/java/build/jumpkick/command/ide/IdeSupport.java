// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command.ide;

import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.engine.protocol.IdeWireModel;
import build.jumpkick.model.Scope;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client half of the {@code jk ide} model build. The model math — workspace + module parsing,
 * lockfile + CAS reads, cross-module edges, per-module JDK/SDK handles — runs engine-side
 * ({@code IdeOps}, thin-client contract) and ships as an {@link IdeWireModel}; this class runs the
 * hosted best-effort sync first (client-rendered), fetches the wire model, and reconstructs the
 * {@link IdeModel} the generators consume. File generation and all TTY output stay client-side.
 *
 * <p>The test-only in-process path answers through the {@code InProcessEngine} twin, which keeps
 * the pre-Wave-4 in-line jar fetch so it builds the exact same model with no engine.
 */
public final class IdeSupport {

    private IdeSupport() {}

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@code
     * BuildCommand.engineDisabledForTests()} for the full rationale. A real {@code jk ide}/{@code
     * idea}/{@code vscode} pre-syncs through the engine; file generation always runs here.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    /** A build failure carrying the process exit code the command should return. */
    public static final class IdeException extends RuntimeException {
        private final int code;

        public IdeException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    // =========================================================================
    // Model build
    // =========================================================================

    /**
     * Resolve the full {@link IdeModel} for the workspace at the invocation's working directory.
     * Reads {@code --cache-dir}/{@code --jdks-dir}/{@code --ide-config-dir} overrides (all optional,
     * used by tests). The engine ensures each module's stable JDK pointer while computing the model,
     * so the resolved {@code JAVA_HOME} paths are valid regardless of which IDE consumes them.
     */
    public static IdeModel build(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        Path ideConfigDir = in.value("ide-config-dir").map(Path::of).orElse(null);

        Path startDir = global.workingDir();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        if (!Files.exists(startDir.resolve("jk.toml"))) {
            throw new IdeException(2, "no jk.toml in " + build.jumpkick.cli.PathDisplay.styledRaw(startDir));
        }

        // Bring the CAS in line with the lockfiles up front. Hosted on the engine for a real
        // invocation (one sync-request covers the workspace cascade, rendered here); the
        // test-only in-process twin fetches in-line while collecting libs, exactly as before.
        boolean hosted = !engineDisabledForTests();
        IdeWireModel wire;
        if (hosted) {
            hostedBestEffortSync(syncRoot(startDir), cache, jdksDir, global);
            try {
                wire = build.jumpkick.cli.engine.EngineClient.ideModel(
                        build.jumpkick.engine.EnginePaths.current(), startDir, cache, jdksDir);
            } catch (IOException e) {
                throw new IdeException(2, String.valueOf(e.getMessage()));
            }
        } else {
            wire = build.jumpkick.cli.engine.InProcessEngine.require().ideModel(startDir, cache, jdksDir);
        }
        if (wire.error() != null) {
            throw new IdeException(2, wire.error());
        }
        return reconstruct(wire, cacheDir, jdksDir, ideConfigDir);
    }

    /** Rebuild the generator-facing {@link IdeModel} from the wire form. */
    private static IdeModel reconstruct(IdeWireModel wire, Path cacheDir, Path jdksDir, Path ideConfigDir) {
        Path wsRoot = Path.of(wire.wsRoot());

        List<Path> dirs = new ArrayList<>(wire.moduleDirs().size());
        Map<Path, IdeModule> allModules = new LinkedHashMap<>();
        Map<Path, SdkRef> sdkRefs = new LinkedHashMap<>();
        for (int i = 0; i < wire.moduleDirs().size(); i++) {
            Path dir = Path.of(wire.moduleDirs().get(i));
            dirs.add(dir);
            String main = wire.mainClasses().get(i);
            allModules.put(dir, new IdeModule(
                    wire.names().get(i),
                    parseInt(wire.javaReleases().get(i)),
                    main.isEmpty() ? null : main,
                    Path.of(wire.classesDirs().get(i)),
                    Path.of(wire.testClassesDirs().get(i)),
                    Path.of(wire.jdtClassesDirs().get(i)),
                    Path.of(wire.jdtTestClassesDirs().get(i)),
                    Path.of(wire.genSrcDirs().get(i)),
                    Path.of(wire.genTestSrcDirs().get(i))));
            sdkRefs.put(dir, new SdkRef(
                    wire.sdkStableNames().get(i),
                    wire.sdkNames().get(i),
                    parseInt(wire.sdkLevels().get(i)),
                    Path.of(wire.sdkHomes().get(i)),
                    wire.sdkVersions().get(i)));
        }
        Map<Path, IdeModule> modules = wire.workspace() ? allModules : Map.of();

        Map<String, LibDef> allLibs = new LinkedHashMap<>();
        for (int i = 0; i < wire.libNames().size(); i++) {
            String sources = wire.libSources().get(i);
            allLibs.put(wire.libNames().get(i), new LibDef(
                    wire.libNames().get(i),
                    wire.libFiles().get(i),
                    Path.of(wire.libJars().get(i)),
                    sources.isEmpty() ? null : Path.of(sources)));
        }

        Map<Path, List<ModuleRef>> siblingRefs = new LinkedHashMap<>();
        for (String row : wire.siblingRefs()) {
            String[] parts = row.split("\\|", 3);
            Path dir = dirs.get(Integer.parseInt(parts[0]));
            siblingRefs.computeIfAbsent(dir, d -> new ArrayList<>()).add(new ModuleRef(parts[1], parts[2]));
        }
        Map<Path, List<LibEntry>> libEntries = new LinkedHashMap<>();
        for (String row : wire.libEntries()) {
            String[] parts = row.split("\\|", 3);
            Path dir = dirs.get(Integer.parseInt(parts[0]));
            List<Scope> scopes = new ArrayList<>();
            for (String s : parts[2].split(",")) {
                if (!s.isBlank()) scopes.add(Scope.valueOf(s));
            }
            libEntries.computeIfAbsent(dir, d -> new ArrayList<>()).add(new LibEntry(parts[1], List.copyOf(scopes)));
        }
        Map<Path, List<Path>> processorJars = new LinkedHashMap<>();
        for (String row : wire.processorJars()) {
            String[] parts = row.split("\\|", 2);
            Path dir = dirs.get(Integer.parseInt(parts[0]));
            processorJars.computeIfAbsent(dir, d -> new ArrayList<>()).add(Path.of(parts[1]));
        }

        SdkRef defaultSdk = new SdkRef(
                wire.defSdkStableName(),
                wire.defSdkName(),
                wire.defSdkLevel(),
                Path.of(wire.defSdkHome()),
                wire.defSdkVersion());

        List<IntellijSdkRegistrar.SdkEntry> sdkEntries = new ArrayList<>();
        for (String row : wire.sdkEntries()) {
            String[] parts = row.split("\\|", 3);
            sdkEntries.add(new IntellijSdkRegistrar.SdkEntry(parts[0], Path.of(parts[1]), parts[2]));
        }

        return new IdeModel(
                wsRoot,
                wire.rootName(),
                modules,
                allModules,
                allLibs,
                siblingRefs,
                libEntries,
                processorJars,
                sdkRefs,
                defaultSdk,
                sdkEntries,
                cacheDir,
                jdksDir,
                ideConfigDir);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The workspace root the pre-model sync should target: the enclosing workspace when {@code
     * startDir} is a module (one hosted sync covers the cascade), else {@code startDir} itself.
     * Resolved via {@code PROJECT_INFO} — no client-side parsing.
     */
    private static Path syncRoot(Path startDir) {
        try {
            var info = build.jumpkick.cli.engine.EngineClient.projectInfo(
                    build.jumpkick.engine.EnginePaths.current(), startDir);
            if (info.error() == null && !info.workspaceRootDir().isEmpty()) {
                return Path.of(info.workspaceRootDir());
            }
        } catch (Exception ignored) {
            // best-effort — sync against startDir; the model build skips whatever is missing
        }
        return startDir;
    }

    /**
     * One hosted {@code jk sync} against the workspace root, rendered with the standard Sync chip.
     * Best-effort by design (mirroring the old in-line {@code CacheSync} call): any failure — the
     * engine unreachable, offline, a pinned-but-uninstalled JDK failing the pipeline's resolve-only
     * ensure-jdk step — warns and returns; the model build skips whatever is still missing.
     */
    private static void hostedBestEffortSync(Path wsRoot, Path cache, Path jdksDir, GlobalOptions global) {
        build.jumpkick.cli.run.PipelineConsole.Mode mode = build.jumpkick.cli.run.PipelineConsole.modeFor(global);
        long[] fetched = new long[1];
        long[] upToDate = new long[1];
        build.jumpkick.cli.run.ConsoleSpec spec = new build.jumpkick.cli.run.ConsoleSpec(
                "Sync",
                r -> fetched[0] == 0 && upToDate[0] == 0
                        ? "already up to date"
                        : fetched[0] + " fetched, " + upToDate[0] + " up-to-date",
                r -> "Dependency sync incomplete — missing jars will be skipped.",
                true);
        String label = wsRoot.getFileName() != null ? wsRoot.getFileName().toString() : wsRoot.toString();
        var session = build.jumpkick.config.SessionContext.current();
        try {
            build.jumpkick.cli.engine.EngineClient.runSync(
                    build.jumpkick.engine.EnginePaths.current(),
                    new build.jumpkick.cli.engine.EngineClient.SyncRequest(
                            wsRoot,
                            cache,
                            jdksDir,
                            null,
                            false,
                            session.offline(),
                            session.force(),
                            false,
                            global.verbose),
                    steps -> build.jumpkick.cli.run.PipelineConsole.chooseConsoleListener(steps, mode, spec, label),
                    fetched,
                    upToDate);
        } catch (IOException e) {
            build.jumpkick.cli.CliOutput.err(
                    "jk ide: dependency sync incomplete (" + e.getMessage() + ") — missing jars will be skipped");
        }
    }

    // =========================================================================
    // Naming helpers
    // =========================================================================

    /** Sanitize a string to a valid filename component. */
    public static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
