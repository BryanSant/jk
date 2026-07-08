// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command.ide;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.jdk.JdkVendor;
import dev.jkbuild.jdk.StableJdkPointer;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.repo.MavenLayout;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * IDE-agnostic model computation shared by every {@link IdeGenerator}. Resolves the workspace, its
 * modules, their external libraries (fetching missing JARs like {@code jk sync}), the cross-module
 * dependency edges, and the per-module JDK/SDK handles — all of it independent of which IDE will
 * consume the result. Mirrors the {@code ExportSupport} convention: a final class of static
 * helpers, no instances.
 *
 * <p><b>Engine-hosted sync</b> (Wave 4 of the slim-client migration): a real invocation runs one
 * hosted {@code jk sync} against the workspace root <em>before</em> computing the model — Wave 1's
 * {@code sync-request} verbatim, workspace cascade included — instead of the old per-module
 * in-process {@code CacheSync}+{@code Http} fetch. Best-effort, exactly like that in-line sync was:
 * a failed sync warns and the model simply skips whatever jars are still missing. Everything after
 * the sync is local: lockfile reads, CAS lookups ({@code RepoArtifactResolver.locateOrMaterialize}
 * is a link from already-synced CAS blobs into the Maven-layout mirror — no network), and JDK
 * pointer maintenance (client-resident by design). The test-only in-process path keeps the in-line
 * fetch so it builds the exact same model with no engine.
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
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
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
     * used by tests). Ensures each module's stable JDK pointer so the resolved {@code JAVA_HOME}
     * paths are valid regardless of which IDE consumes them.
     */
    public static IdeModel build(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        Path ideConfigDir = in.value("ide-config-dir").map(Path::of).orElse(null);

        Path startDir = global.workingDir();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas = new Cas(cache);

        // Find workspace root (or treat a single project as its own root).
        Path wsRoot;
        JkBuild rootBuild;
        try {
            Path buildFile = startDir.resolve("jk.toml");
            if (!Files.exists(buildFile)) {
                throw new IdeException(2, "no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(startDir));
            }
            rootBuild = JkBuildParser.parse(buildFile);
            if (rootBuild.isWorkspaceRoot()) {
                wsRoot = startDir;
            } else {
                var rootOpt = WorkspaceLocator.findRoot(startDir);
                wsRoot = rootOpt.orElse(startDir);
                rootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            }
        } catch (IdeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IdeException(2, e.getMessage());
        }
        // Canonicalize wsRoot so paths from BuildGraph (which calls toRealPath) and workspace-loader
        // paths are consistent — critical for correct relativize() on systems where the temp/project
        // dir is reached via a symlink (e.g. macOS /var/folders → /private/var/folders).
        try {
            wsRoot = wsRoot.toRealPath();
        } catch (IOException ignored) {
        }

        Map<Path, JkBuild> modules =
                rootBuild.isWorkspaceRoot() ? WorkspaceLoader.loadModules(wsRoot, rootBuild) : Map.of();

        // Unified module set: workspace modules, or the single root project.
        Map<Path, JkBuild> allModules = new LinkedHashMap<>();
        if (modules.isEmpty()) allModules.put(wsRoot, rootBuild);
        else allModules.putAll(modules);

        // Bring the CAS in line with the lockfiles up front. Hosted on the engine for a real
        // invocation (one sync-request covers the workspace cascade); the test-only in-process
        // path fetches per module inside collectLibDefs, exactly as before.
        boolean hosted = !engineDisabledForTests();
        if (hosted) {
            hostedBestEffortSync(wsRoot, cache, jdksDir, global);
        }

        // Collect all library definitions across every module.
        Map<String, LibDef> allLibs = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> me : allModules.entrySet()) {
            collectLibDefs(me.getKey(), me.getValue(), modules, cas, allLibs, !hosted);
        }

        // Per-module JDKs: each module maps to a stable jk-<vendor>-<level> SDK backed by a
        // StableJdkPointer path (survives JDK point-release upgrades).
        JdkRegistry jdkRegistry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        StableJdkPointer pointer = jdksDir != null ? new StableJdkPointer(jdksDir) : StableJdkPointer.atDefaultRoot();
        List<IntellijSdkRegistrar.SdkEntry> sdkEntries = new ArrayList<>();
        Set<String> seenSdk = new LinkedHashSet<>();
        Map<Path, SdkRef> sdkRefs = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> me : allModules.entrySet()) {
            sdkRefs.put(me.getKey(), sdkRefFor(me.getKey(), me.getValue(), jdkRegistry, pointer, sdkEntries, seenSdk));
        }
        SdkRef defaultSdk =
                defaultSdkRef(wsRoot, rootBuild, modules, sdkRefs, jdkRegistry, pointer, sdkEntries, seenSdk);

        // Per-module dependency edges and processor jars.
        Map<Path, List<ModuleRef>> siblingRefs = new LinkedHashMap<>();
        Map<Path, List<LibEntry>> libEntries = new LinkedHashMap<>();
        Map<Path, List<Path>> processorJars = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> me : allModules.entrySet()) {
            siblingRefs.put(me.getKey(), siblingModuleRefs(me.getKey(), me.getValue(), modules));
            libEntries.put(me.getKey(), moduleLibEntries(me.getKey(), me.getValue(), modules, allLibs));
            processorJars.put(me.getKey(), processorLibFiles(me.getKey(), me.getValue(), modules, allLibs));
        }

        return new IdeModel(
                wsRoot,
                rootBuild,
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

    /**
     * One hosted {@code jk sync} against the workspace root, rendered with the standard Sync chip.
     * Best-effort by design (mirroring the old in-line {@code CacheSync} call): any failure — the
     * engine unreachable, offline, a pinned-but-uninstalled JDK failing the goal's resolve-only
     * ensure-jdk phase — warns and returns; the model build below skips whatever is still missing.
     */
    private static void hostedBestEffortSync(Path wsRoot, Path cache, Path jdksDir, GlobalOptions global) {
        dev.jkbuild.cli.run.GoalConsole.Mode mode = dev.jkbuild.cli.run.GoalConsole.modeFor(global);
        long[] fetched = new long[1];
        long[] upToDate = new long[1];
        dev.jkbuild.cli.run.ConsoleSpec spec = new dev.jkbuild.cli.run.ConsoleSpec(
                "Sync",
                r -> fetched[0] == 0 && upToDate[0] == 0
                        ? "already up to date"
                        : fetched[0] + " fetched, " + upToDate[0] + " up-to-date",
                r -> "Dependency sync incomplete — missing jars will be skipped.",
                true);
        String label = wsRoot.getFileName() != null ? wsRoot.getFileName().toString() : wsRoot.toString();
        var session = dev.jkbuild.config.SessionContext.current();
        try {
            dev.jkbuild.cli.engine.EngineClient.runSync(
                    dev.jkbuild.engine.EnginePaths.current(),
                    new dev.jkbuild.cli.engine.EngineClient.SyncRequest(
                            wsRoot,
                            cache,
                            jdksDir,
                            null,
                            false,
                            session.offline(),
                            session.force(),
                            false,
                            global.verbose),
                    phases -> dev.jkbuild.cli.run.GoalConsole.chooseConsoleListener(phases, mode, spec, label),
                    fetched,
                    upToDate);
        } catch (IOException e) {
            dev.jkbuild.cli.CliOutput.err(
                    "jk ide: dependency sync incomplete (" + e.getMessage() + ") — missing jars will be skipped");
        }
    }

    // =========================================================================
    // JDK / SDK resolution
    // =========================================================================

    /**
     * Resolve a module's stable SDK handle. Prefers the module's locked JDK identifier ({@code
     * jk.lock}'s {@code jdk}); falls back to the declared {@code project.jdk} level and the default
     * vendor (Temurin). When the JDK is installed, ensures the {@link StableJdkPointer} and queues an
     * {@link IntellijSdkRegistrar.SdkEntry} (once per distinct SDK).
     */
    static SdkRef sdkRefFor(
            Path moduleDir,
            JkBuild module,
            JdkRegistry registry,
            StableJdkPointer pointer,
            List<IntellijSdkRegistrar.SdkEntry> sdkEntries,
            Set<String> seen)
            throws IOException {
        int level = module.project().jdkMajor() > 0
                ? module.project().jdkMajor()
                : module.project().javaRelease();
        String lockJdk = readLockJdk(moduleDir);
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(lockJdk == null ? "" : lockJdk);
        if (q.major().isPresent()) level = q.major().get();
        if (level <= 0) level = 21;

        Optional<JdkHit> hit = Optional.empty();
        if (lockJdk != null && !lockJdk.isBlank()) hit = registry.findHitBySpec(lockJdk);
        if (hit.isEmpty()) hit = registry.findHitBySpec(String.valueOf(level));

        String vendor;
        String version = q.exactVersion().orElse(null);
        if (hit.isPresent()) {
            JdkVendor v = hit.get().vendor();
            vendor = v.jbPrefix().orElse(v.vendor().toLowerCase(Locale.ROOT));
            if (version == null) version = hit.get().version();
        } else if (!q.hints().isEmpty()) {
            vendor = q.hints().get(0);
        } else {
            vendor = "temurin";
        }

        String stableName = vendor + "-" + level;
        String sdkName = "jk-" + stableName;
        if (hit.isPresent() && seen.add(sdkName)) {
            pointer.ensure(stableName, IntellijJdkDir.installDirOf(hit.get().home()));
            sdkEntries.add(new IntellijSdkRegistrar.SdkEntry(
                    sdkName, pointer.javaHome(stableName), version != null ? version : String.valueOf(level)));
        }
        return new SdkRef(
                stableName,
                sdkName,
                module.project().javaRelease() > 0 ? module.project().javaRelease() : level,
                pointer.javaHome(stableName),
                version != null ? version : String.valueOf(level));
    }

    /**
     * The project-default SDK: root {@code project.jdk}, else the highest module level. Reuses a
     * module's resolved {@link SdkRef} when one matches that level; otherwise resolves the root build.
     */
    static SdkRef defaultSdkRef(
            Path wsRoot,
            JkBuild root,
            Map<Path, JkBuild> modules,
            Map<Path, SdkRef> sdkRefs,
            JdkRegistry registry,
            StableJdkPointer pointer,
            List<IntellijSdkRegistrar.SdkEntry> sdkEntries,
            Set<String> seen)
            throws IOException {
        if (sdkRefs.containsKey(wsRoot)) return sdkRefs.get(wsRoot);
        int level = root.project().jdkMajor();
        if (level == 0)
            for (JkBuild m : modules.values())
                level = Math.max(level, m.project().jdkMajor());
        for (SdkRef r : sdkRefs.values()) if (r.languageLevel() == level) return r;
        return sdkRefFor(wsRoot, root, registry, pointer, sdkEntries, seen);
    }

    /** The resolved JDK identifier stamped in a module's {@code jk.lock}, or null. */
    static String readLockJdk(Path moduleDir) {
        Path lf = moduleDir.resolve("jk.lock");
        if (!Files.exists(lf)) return null;
        try {
            return LockfileReader.read(lf).jdk();
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    // =========================================================================
    // Library collection
    // =========================================================================

    /**
     * Collect all external (non-workspace) dep library definitions for one module. {@code
     * fetchMissing} is the test-only in-process mode: fetch missing JARs in-line (the pre-Wave-4
     * behavior); a real invocation already ran the hosted workspace sync and passes {@code false}.
     */
    static void collectLibDefs(
            Path moduleDir,
            JkBuild module,
            Map<Path, JkBuild> modules,
            Cas cas,
            Map<String, LibDef> allLibs,
            boolean fetchMissing)
            throws IOException {
        Path lockFile = moduleDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) return;
        Lockfile lock = LockfileReader.read(lockFile);

        // Fetch any missing JARs so the repo has entries to reference (mirrors `jk sync` — the IDE
        // export should work without a prior sync).
        if (fetchMissing) {
            try {
                new CacheSync(cas, new Http()).sync(lock, CacheSync.ProgressObserver.NOOP);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                /* best-effort; missing JARs are skipped below */
            }
        }

        Set<String> siblingCoords = siblingCoordinates(module, modules);

        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue; // path/git dep
            if (siblingCoords.contains(pkg.name())) continue; // workspace sibling → module dep
            if (allLibs.containsKey(pkg.name() + ":" + pkg.version())) continue;

            if (pkg.name().indexOf(':') < 0) continue;
            Coordinate coord = pkg.coordinate();

            // Use a Maven-layout path with a proper .jar extension rather than the CAS
            // (extension-less hash paths) — IDEs require .jar.
            String artifactRelPath = MavenLayout.artifactPath(coord);
            Path jar = dev.jkbuild.repo.RepoArtifactResolver.locateOrMaterialize(cas, pkg.source(), artifactRelPath, pkg.checksumHex());
            if (jar == null) continue; // not yet synced / non-Maven dep

            Path sourcesPath = null;
            if (pkg.sourcesChecksum() != null) {
                Coordinate srcCoord =
                        new Coordinate(coord.group(), coord.artifact(), coord.version(), "sources", "jar");
                String srcRelPath = MavenLayout.artifactPath(srcCoord);
                sourcesPath = dev.jkbuild.repo.RepoArtifactResolver.locateOrMaterialize(cas, pkg.source(), srcRelPath, pkg.sourcesChecksumHex());
            }

            String libName = pkg.name() + ":" + pkg.version();
            allLibs.put(libName, new LibDef(libName, libFileName(libName), jar, sourcesPath));
        }
    }

    // =========================================================================
    // Per-module dependency lists
    // =========================================================================

    /** Workspace siblings this module directly depends on (MAIN + TEST scopes). */
    static List<ModuleRef> siblingModuleRefs(Path moduleDir, JkBuild module, Map<Path, JkBuild> modules)
            throws IOException {
        List<ModuleRef> result = new ArrayList<>();
        WorkspaceClasspath.Result mainCp =
                WorkspaceClasspath.resolve(moduleDir, module, EnumSet.of(Scope.EXPORT, Scope.MAIN));
        WorkspaceClasspath.Result testCp = WorkspaceClasspath.resolve(moduleDir, module, EnumSet.of(Scope.TEST));

        // Map jar → module name for all workspace siblings.
        Map<Path, String> jarToModule = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> me : modules.entrySet()) {
            BuildLayout layout = BuildLayout.of(me.getKey(), me.getValue());
            jarToModule.put(layout.mainJar(), moduleName(me.getValue()));
        }

        // Use the full declared closure, not jars() — the latter is filtered to jars that already
        // exist on disk, so before a first build every sibling dependency would be dropped and the
        // IDE couldn't resolve any cross-module classes. The IDE compiles the modules itself; the
        // edge is what matters, not the artifact.
        Set<String> added = new LinkedHashSet<>();
        for (Path sj : mainCp.siblingClosureJars()) {
            String name = jarToModule.get(sj);
            if (name != null && added.add(name)) result.add(new ModuleRef(name, "COMPILE"));
        }
        for (Path sj : testCp.siblingClosureJars()) {
            String name = jarToModule.get(sj);
            if (name != null && added.add(name)) result.add(new ModuleRef(name, "TEST"));
        }
        return result;
    }

    /** External library references (with raw jk scopes) for one module — processor-only deps excluded. */
    static List<LibEntry> moduleLibEntries(
            Path moduleDir, JkBuild module, Map<Path, JkBuild> allModules, Map<String, LibDef> allLibs)
            throws IOException {
        Path lockFile = moduleDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) return List.of();
        Lockfile lock = LockfileReader.read(lockFile);
        Set<String> siblingCoords = siblingCoordinates(module, allModules);

        List<LibEntry> result = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue;
            if (siblingCoords.contains(pkg.name())) continue;
            // Processor-only deps belong on the annotation-processor path, not the compile classpath.
            if (processorOnly(pkg.scopes())) continue;
            String libName = pkg.name() + ":" + pkg.version();
            if (!allLibs.containsKey(libName)) continue;
            result.add(new LibEntry(libName, List.copyOf(pkg.scopes())));
        }
        return result;
    }

    /** The processor-scoped dependency JARs of a module — fed into the IDE's annotation-processing config. */
    static List<Path> processorLibFiles(
            Path moduleDir, JkBuild module, Map<Path, JkBuild> modules, Map<String, LibDef> allLibs)
            throws IOException {
        Path lockFile = moduleDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) return List.of();
        Lockfile lock = LockfileReader.read(lockFile);
        Set<String> siblingCoords = siblingCoordinates(module, modules);
        List<Path> out = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue;
            if (siblingCoords.contains(pkg.name())) continue;
            if (!pkg.inAnyScope(EnumSet.of(Scope.PROCESSOR))) continue;
            LibDef def = allLibs.get(pkg.name() + ":" + pkg.version());
            if (def != null && def.jarPath() != null) out.add(def.jarPath());
        }
        return out;
    }

    /** True when {@code PROCESSOR} is the only classpath-relevant scope on a package. */
    static boolean processorOnly(List<Scope> scopes) {
        if (!scopes.contains(Scope.PROCESSOR)) return false;
        for (Scope s : scopes) {
            if (s == Scope.MAIN || s == Scope.EXPORT || s == Scope.PROVIDED || s == Scope.RUNTIME || s == Scope.TEST) {
                return false;
            }
        }
        return true;
    }

    /** Coordinates of all workspace siblings that this module could declare as deps. */
    static Set<String> siblingCoordinates(JkBuild module, Map<Path, JkBuild> allModules) {
        Set<String> coords = new LinkedHashSet<>();
        for (JkBuild sib : allModules.values()) {
            if (sib != module) {
                coords.add(sib.project().group() + ":" + sib.project().name());
            }
        }
        return coords;
    }

    // =========================================================================
    // Naming helpers
    // =========================================================================

    /** The IDE module/project name for a workspace module. */
    public static String moduleName(JkBuild module) {
        return module.project().name();
    }

    /** Library filename (no extension). Replaces non-safe chars with underscores. */
    static String libFileName(String libName) {
        return sanitize(libName.replace(':', '_').replace('.', '_'));
    }

    /** Sanitize a string to a valid filename component. */
    public static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
