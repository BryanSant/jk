// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.cache.Cas;
import build.jumpkick.config.JkBuildParser;
import build.jumpkick.config.WorkspaceClasspath;
import build.jumpkick.config.WorkspaceLoader;
import build.jumpkick.config.WorkspaceLocator;
import build.jumpkick.engine.protocol.IdeWireModel;
import build.jumpkick.jdk.IntellijJdkDir;
import build.jumpkick.jdk.JdkHit;
import build.jumpkick.jdk.JdkRegistry;
import build.jumpkick.jdk.JdkSelector;
import build.jumpkick.jdk.JdkVendor;
import build.jumpkick.jdk.StableJdkPointer;
import build.jumpkick.layout.BuildLayout;
import build.jumpkick.lock.Lockfile;
import build.jumpkick.lock.LockfileReader;
import build.jumpkick.model.Coordinate;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.Scope;
import build.jumpkick.repo.MavenLayout;
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
 * Engine-hosted {@code jk ide} model math (thin-client contract): resolving the workspace, its
 * modules, external libraries (lockfile + CAS reads), cross-module edges, and per-module JDK/SDK
 * handles all need the parsed models, so it runs engine-side and ships as an {@link IdeWireModel}.
 * The IDE-specific file generators — TTY + disk writers — stay client-side.
 *
 * <p>The dependency <em>sync</em> is not here: a real invocation runs one hosted {@code jk sync}
 * (client-rendered) before requesting the model; the test-only in-process path passes {@code
 * fetchMissing} to keep the pre-Wave-4 in-line fetch, so both paths build the same model.
 *
 * <p>{@link StableJdkPointer#ensure} writes under the jk home — the engine and client share it, so
 * pointer maintenance is equally correct here at model-build time.
 */
public final class IdeOps {

    private IdeOps() {}

    public static IdeWireModel ideModel(Path startDir, Path cache, Path jdksDir, boolean fetchMissing) {
        try {
            return build(startDir, cache, jdksDir, fetchMissing);
        } catch (IOException | RuntimeException e) {
            return IdeWireModel.error(String.valueOf(e.getMessage()));
        }
    }

    private static IdeWireModel build(Path startDir, Path cache, Path jdksDir, boolean fetchMissing)
            throws IOException {
        Cas cas = new Cas(cache);

        Path buildFile = startDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            return IdeWireModel.error("no jk.toml in " + startDir);
        }
        JkBuild rootBuild = JkBuildParser.parse(buildFile);
        Path wsRoot;
        if (rootBuild.isWorkspaceRoot()) {
            wsRoot = startDir;
        } else {
            var rootOpt = WorkspaceLocator.findRoot(startDir);
            wsRoot = rootOpt.orElse(startDir);
            rootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
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
        Map<Path, JkBuild> allModules = new LinkedHashMap<>();
        if (modules.isEmpty()) allModules.put(wsRoot, rootBuild);
        else allModules.putAll(modules);

        // Library definitions across every module: name → (fileName, jar, sources).
        Map<String, String[]> allLibs = new LinkedHashMap<>(); // libName → {fileName, jar, sources|null}
        for (Map.Entry<Path, JkBuild> me : allModules.entrySet()) {
            collectLibDefs(me.getKey(), me.getValue(), modules, cas, allLibs, fetchMissing);
        }

        // Per-module JDK/SDK handles.
        JdkRegistry jdkRegistry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        StableJdkPointer pointer = jdksDir != null ? new StableJdkPointer(jdksDir) : StableJdkPointer.atDefaultRoot();
        List<String> sdkEntries = new ArrayList<>(); // "name|home|version"
        Set<String> seenSdk = new LinkedHashSet<>();
        Map<Path, String[]> sdkRefs = new LinkedHashMap<>(); // dir → {stable, sdkName, level, home, version}
        for (Map.Entry<Path, JkBuild> me : allModules.entrySet()) {
            sdkRefs.put(me.getKey(), sdkRefFor(me.getKey(), me.getValue(), jdkRegistry, pointer, sdkEntries, seenSdk));
        }
        String[] defaultSdk =
                defaultSdkRef(wsRoot, rootBuild, modules, sdkRefs, jdkRegistry, pointer, sdkEntries, seenSdk);

        // Per-module dependency edges, external-library refs, processor jars.
        List<Path> dirs = new ArrayList<>(allModules.keySet());
        List<String> siblingRefs = new ArrayList<>(); // "i|name|scope"
        List<String> libEntries = new ArrayList<>(); // "i|libName|SCOPE1,SCOPE2"
        List<String> processorJars = new ArrayList<>(); // "i|path"
        for (int i = 0; i < dirs.size(); i++) {
            Path dir = dirs.get(i);
            JkBuild module = allModules.get(dir);
            for (String[] mr : siblingModuleRefs(dir, module, modules)) {
                siblingRefs.add(i + "|" + mr[0] + "|" + mr[1]);
            }
            for (String[] le : moduleLibEntries(dir, module, modules, allLibs)) {
                libEntries.add(i + "|" + le[0] + "|" + le[1]);
            }
            for (String jar : processorLibFiles(dir, module, modules, allLibs)) {
                processorJars.add(i + "|" + jar);
            }
        }

        // Per-module facts + layout paths.
        List<String> moduleDirs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> javaReleases = new ArrayList<>();
        List<String> mainClasses = new ArrayList<>();
        List<String> classesDirs = new ArrayList<>();
        List<String> testClassesDirs = new ArrayList<>();
        List<String> jdtClassesDirs = new ArrayList<>();
        List<String> jdtTestClassesDirs = new ArrayList<>();
        List<String> genSrcDirs = new ArrayList<>();
        List<String> genTestSrcDirs = new ArrayList<>();
        List<String> sdkStableNames = new ArrayList<>();
        List<String> sdkNames = new ArrayList<>();
        List<String> sdkLevels = new ArrayList<>();
        List<String> sdkHomes = new ArrayList<>();
        List<String> sdkVersions = new ArrayList<>();
        for (Path dir : dirs) {
            JkBuild module = allModules.get(dir);
            BuildLayout layout = BuildLayout.of(dir, module);
            moduleDirs.add(dir.toString());
            names.add(module.project().name());
            javaReleases.add(String.valueOf(module.project().javaRelease()));
            mainClasses.add(module.mainClass() == null ? "" : module.mainClass());
            classesDirs.add(layout.classesDir().toString());
            testClassesDirs.add(layout.testClassesDir().toString());
            jdtClassesDirs.add(layout.jdtClassesDir().toString());
            jdtTestClassesDirs.add(layout.jdtTestClassesDir().toString());
            genSrcDirs.add(layout.generatedSourcesDir("annotations").toString());
            genTestSrcDirs.add(layout.generatedSourcesDir("annotations", "test").toString());
            String[] sdk = sdkRefs.get(dir);
            sdkStableNames.add(sdk[0]);
            sdkNames.add(sdk[1]);
            sdkLevels.add(sdk[2]);
            sdkHomes.add(sdk[3]);
            sdkVersions.add(sdk[4]);
        }

        List<String> libNames = new ArrayList<>();
        List<String> libFiles = new ArrayList<>();
        List<String> libJars = new ArrayList<>();
        List<String> libSources = new ArrayList<>();
        for (Map.Entry<String, String[]> e : allLibs.entrySet()) {
            libNames.add(e.getKey());
            libFiles.add(e.getValue()[0]);
            libJars.add(e.getValue()[1]);
            libSources.add(e.getValue()[2] == null ? "" : e.getValue()[2]);
        }

        return new IdeWireModel(
                null,
                wsRoot.toString(),
                rootBuild.project().name(),
                !modules.isEmpty(),
                moduleDirs,
                names,
                javaReleases,
                mainClasses,
                classesDirs,
                testClassesDirs,
                jdtClassesDirs,
                jdtTestClassesDirs,
                genSrcDirs,
                genTestSrcDirs,
                libNames,
                libFiles,
                libJars,
                libSources,
                siblingRefs,
                libEntries,
                processorJars,
                sdkStableNames,
                sdkNames,
                sdkLevels,
                sdkHomes,
                sdkVersions,
                defaultSdk[0],
                defaultSdk[1],
                Integer.parseInt(defaultSdk[2]),
                defaultSdk[3],
                defaultSdk[4],
                sdkEntries);
    }

    // =========================================================================
    // JDK / SDK resolution
    // =========================================================================

    /**
     * Resolve a module's stable SDK handle as {@code {stableName, sdkName, languageLevel, javaHome,
     * version}}. Prefers the module's locked JDK identifier; falls back to the declared {@code
     * project.jdk} level and the default vendor (Temurin). When the JDK is installed, ensures the
     * {@link StableJdkPointer} and queues a {@code name|home|version} SDK entry (once per SDK).
     */
    private static String[] sdkRefFor(
            Path moduleDir,
            JkBuild module,
            JdkRegistry registry,
            StableJdkPointer pointer,
            List<String> sdkEntries,
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
            sdkEntries.add(sdkName + "|" + pointer.javaHome(stableName) + "|"
                    + (version != null ? version : String.valueOf(level)));
        }
        int langLevel = module.project().javaRelease() > 0 ? module.project().javaRelease() : level;
        return new String[] {
            stableName,
            sdkName,
            String.valueOf(langLevel),
            pointer.javaHome(stableName).toString(),
            version != null ? version : String.valueOf(level)
        };
    }

    /**
     * The project-default SDK: root {@code project.jdk}, else the highest module level. Reuses a
     * module's resolved handle when one matches that level; otherwise resolves the root build.
     */
    private static String[] defaultSdkRef(
            Path wsRoot,
            JkBuild root,
            Map<Path, JkBuild> modules,
            Map<Path, String[]> sdkRefs,
            JdkRegistry registry,
            StableJdkPointer pointer,
            List<String> sdkEntries,
            Set<String> seen)
            throws IOException {
        if (sdkRefs.containsKey(wsRoot)) return sdkRefs.get(wsRoot);
        int level = root.project().jdkMajor();
        if (level == 0)
            for (JkBuild m : modules.values())
                level = Math.max(level, m.project().jdkMajor());
        for (String[] r : sdkRefs.values()) if (Integer.parseInt(r[2]) == level) return r;
        return sdkRefFor(wsRoot, root, registry, pointer, sdkEntries, seen);
    }

    /** The resolved JDK identifier stamped in a module's {@code jk.lock}, or null. */
    private static String readLockJdk(Path moduleDir) {
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
     * Collect all external (non-workspace) dep library definitions for one module as {@code
     * libName → {fileName, jarPath, sourcesPath|null}}. {@code fetchMissing} is the test-only
     * in-process mode: fetch missing JARs in-line; a real invocation already ran the hosted
     * workspace sync.
     */
    private static void collectLibDefs(
            Path moduleDir,
            JkBuild module,
            Map<Path, JkBuild> modules,
            Cas cas,
            Map<String, String[]> allLibs,
            boolean fetchMissing)
            throws IOException {
        Path lockFile = moduleDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) return;
        Lockfile lock = LockfileReader.read(lockFile);

        if (fetchMissing) {
            try {
                new build.jumpkick.resolver.CacheSync(cas, new build.jumpkick.http.Http())
                        .sync(lock, build.jumpkick.resolver.CacheSync.ProgressObserver.NOOP);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // best-effort; missing JARs are skipped below
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
            Path jar = build.jumpkick.repo.RepoArtifactResolver.locateOrMaterialize(
                    cas, pkg.source(), artifactRelPath, pkg.checksumHex());
            if (jar == null) continue; // not yet synced / non-Maven dep

            Path sourcesPath = null;
            if (pkg.sourcesChecksum() != null) {
                Coordinate srcCoord =
                        new Coordinate(coord.group(), coord.artifact(), coord.version(), "sources", "jar");
                String srcRelPath = MavenLayout.artifactPath(srcCoord);
                sourcesPath = build.jumpkick.repo.RepoArtifactResolver.locateOrMaterialize(
                        cas, pkg.source(), srcRelPath, pkg.sourcesChecksumHex());
            }

            String libName = pkg.name() + ":" + pkg.version();
            allLibs.put(libName, new String[] {
                libFileName(libName), jar.toString(), sourcesPath == null ? null : sourcesPath.toString()
            });
        }
    }

    // =========================================================================
    // Per-module dependency lists
    // =========================================================================

    /** Workspace siblings this module directly depends on, as {@code {name, COMPILE|TEST}}. */
    private static List<String[]> siblingModuleRefs(Path moduleDir, JkBuild module, Map<Path, JkBuild> modules)
            throws IOException {
        List<String[]> result = new ArrayList<>();
        WorkspaceClasspath.Result mainCp =
                WorkspaceClasspath.resolve(moduleDir, module, EnumSet.of(Scope.EXPORT, Scope.MAIN));
        WorkspaceClasspath.Result testCp = WorkspaceClasspath.resolve(moduleDir, module, EnumSet.of(Scope.TEST));

        // Map jar → module name for all workspace siblings.
        Map<Path, String> jarToModule = new LinkedHashMap<>();
        for (Map.Entry<Path, JkBuild> me : modules.entrySet()) {
            BuildLayout layout = BuildLayout.of(me.getKey(), me.getValue());
            jarToModule.put(layout.mainJar(), me.getValue().project().name());
        }

        // Use the full declared closure, not jars() — the latter is filtered to jars that already
        // exist on disk, so before a first build every sibling dependency would be dropped and the
        // IDE couldn't resolve any cross-module classes. The IDE compiles the modules itself; the
        // edge is what matters, not the artifact.
        Set<String> added = new LinkedHashSet<>();
        for (Path sj : mainCp.siblingClosureJars()) {
            String name = jarToModule.get(sj);
            if (name != null && added.add(name)) result.add(new String[] {name, "COMPILE"});
        }
        for (Path sj : testCp.siblingClosureJars()) {
            String name = jarToModule.get(sj);
            if (name != null && added.add(name)) result.add(new String[] {name, "TEST"});
        }
        return result;
    }

    /** External library references for one module as {@code {libName, "MAIN,TEST"}} — processor-only deps excluded. */
    private static List<String[]> moduleLibEntries(
            Path moduleDir, JkBuild module, Map<Path, JkBuild> allModules, Map<String, String[]> allLibs)
            throws IOException {
        Path lockFile = moduleDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) return List.of();
        Lockfile lock = LockfileReader.read(lockFile);
        Set<String> siblingCoords = siblingCoordinates(module, allModules);

        List<String[]> result = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue;
            if (siblingCoords.contains(pkg.name())) continue;
            // Processor-only deps belong on the annotation-processor path, not the compile classpath.
            if (processorOnly(pkg.scopes())) continue;
            String libName = pkg.name() + ":" + pkg.version();
            if (!allLibs.containsKey(libName)) continue;
            StringBuilder scopes = new StringBuilder();
            for (Scope s : pkg.scopes()) {
                if (scopes.length() > 0) scopes.append(',');
                scopes.append(s.name());
            }
            result.add(new String[] {libName, scopes.toString()});
        }
        return result;
    }

    /** The processor-scoped dependency JARs of a module — fed into the IDE's annotation-processing config. */
    private static List<String> processorLibFiles(
            Path moduleDir, JkBuild module, Map<Path, JkBuild> modules, Map<String, String[]> allLibs)
            throws IOException {
        Path lockFile = moduleDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) return List.of();
        Lockfile lock = LockfileReader.read(lockFile);
        Set<String> siblingCoords = siblingCoordinates(module, modules);
        List<String> out = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue;
            if (siblingCoords.contains(pkg.name())) continue;
            if (!pkg.inAnyScope(EnumSet.of(Scope.PROCESSOR))) continue;
            String[] def = allLibs.get(pkg.name() + ":" + pkg.version());
            if (def != null && def[1] != null) out.add(def[1]);
        }
        return out;
    }

    /** True when {@code PROCESSOR} is the only classpath-relevant scope on a package. */
    private static boolean processorOnly(List<Scope> scopes) {
        if (!scopes.contains(Scope.PROCESSOR)) return false;
        for (Scope s : scopes) {
            if (s == Scope.MAIN || s == Scope.EXPORT || s == Scope.PROVIDED || s == Scope.RUNTIME || s == Scope.TEST) {
                return false;
            }
        }
        return true;
    }

    /** Coordinates of all workspace siblings that this module could declare as deps. */
    private static Set<String> siblingCoordinates(JkBuild module, Map<Path, JkBuild> allModules) {
        Set<String> coords = new LinkedHashSet<>();
        for (JkBuild sib : allModules.values()) {
            if (sib != module) {
                coords.add(sib.project().group() + ":" + sib.project().name());
            }
        }
        return coords;
    }

    /** Library filename (no extension). Replaces non-safe chars with underscores. */
    private static String libFileName(String libName) {
        return libName.replace(':', '_').replace('.', '_').replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
