// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.engine.protocol.ExecPlan;
import dev.jkbuild.engine.protocol.ProjectInfo;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.layout.MainClassScanner;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.tool.AppLauncher;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The engine side of the thin-client contract (docs/thin-client-plan.md): parse-and-summarize
 * ({@link #projectInfo}) and decide-don't-execute ({@link #execPlan}). Every branch here used to
 * live in the CLI (RunCommand's exec assembly, DevCommand's classpath, InstallCommand's gates and
 * layout, AotCachePackage's inputs) — client/engine version skew in those branches was a recurring
 * bug class, so the engine now owns all of it and the client executes verbatim plans.
 */
public final class ExecPlans {

    private ExecPlans() {}

    // ------------------------------------------------------------- project info

    /** Summarize the project at {@code dir} — never throws; failures ride {@code error}. */
    public static ProjectInfo projectInfo(Path dir) {
        try {
            Path buildFile = dir.resolve("jk.toml");
            if (!Files.exists(buildFile)) {
                return ProjectInfo.error("no jk.toml in " + dir);
            }
            JkBuild build = JkBuildParser.parse(buildFile);

            String workspaceRootDir = "";
            List<String> moduleDirs = new ArrayList<>();
            if (build.isWorkspaceRoot()) {
                workspaceRootDir = dir.toString();
                for (String m : build.workspace().modules()) moduleDirs.add(m);
            } else {
                var root = WorkspaceLocator.findRoot(dir);
                if (root.isPresent()) workspaceRootDir = root.get().toString();
            }

            Path lockFile = dir.resolve("jk.lock");
            boolean hasLock = Files.exists(lockFile);
            String lockJdk = "";
            if (hasLock) {
                try {
                    String id = LockfileReader.read(lockFile).jdk();
                    if (id != null) lockJdk = id;
                } catch (IOException ignored) {
                    // unreadable lock — summarized as jdk-unknown, not an error
                }
            }

            var format = build.format();
            var boot = build.springBoot().orElse(null);
            return new ProjectInfo(
                    null,
                    build.project().group(),
                    build.project().name(),
                    build.project().version(),
                    build.project().jdk(),
                    build.project().javaRelease(),
                    build.project().isKotlin(),
                    build.project().kotlin() == null
                            ? ""
                            : build.project().kotlin().raw(),
                    dev.jkbuild.layout.SourceLayout.isSimpleLayout(build.project(), dir),
                    build.isWorkspaceRoot(),
                    workspaceRootDir,
                    moduleDirs,
                    build.isApplication(),
                    build.mainClass() == null ? "" : build.mainClass(),
                    build.shadowJar(),
                    build.nativeMode().name(),
                    orEmpty(build.graal()),
                    build.isSpringBoot(),
                    boot == null ? "" : boot.version(),
                    orEmpty(format.style()),
                    orEmpty(format.java()),
                    orEmpty(format.kotlin()),
                    Boolean.TRUE.equals(format.optimizeImports()),
                    hasLock,
                    lockJdk,
                    layoutOf(build, dir, BuildLayout::mainJar),
                    layoutOf(build, dir, BuildLayout::shadowJar),
                    layoutOf(build, dir, BuildLayout::nativeBinary),
                    layoutOf(build, dir, BuildLayout::nativeLibrary));
        } catch (RuntimeException | IOException e) {
            return ProjectInfo.error(String.valueOf(e.getMessage()));
        }
    }

    private static String layoutOf(
            JkBuild build, Path dir, java.util.function.Function<BuildLayout, Path> f) {
        try {
            return f.apply(BuildLayout.of(dir, build)).toAbsolutePath().toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ------------------------------------------------------------- exec plans

    /** Compute the plan for {@code kind} — never throws; failures ride {@code error}. */
    public static ExecPlan execPlan(Path dir, Path cache, String kind, String mainOverride, String binName) {
        return execPlan(dir, cache, kind, mainOverride, binName, null, null);
    }

    /** As above with install-destination overrides ({@code --bin-dir}/{@code --lib-dir}). */
    public static ExecPlan execPlan(
            Path dir, Path cache, String kind, String mainOverride, String binName, Path binDir, Path libDir) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
            BuildLayout layout = BuildLayout.of(dir, project);
            return switch (kind) {
                case "run" -> runPlan(dir, cache, project, layout, false);
                case "dev" -> runPlan(dir, cache, project, layout, true);
                case "install" -> installPlan(dir, cache, project, layout, mainOverride, binName, binDir, libDir);
                case "aot-cache" -> aotCachePlan(dir, cache, project, layout);
                default -> ExecPlan.error(kind, "unknown exec-plan kind: " + kind);
            };
        } catch (RuntimeException | IOException e) {
            return ExecPlan.error(kind, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecPlan.error(kind, "interrupted");
        }
    }

    /**
     * {@code jk run} / {@code jk dev}: pick the most self-contained artifact (native > shadow >
     * classes-dir) and assemble the full command line. Dev mode always runs from the classes dir
     * (the loop recompiles into it, and DevTools watches it) with the RUN classpath.
     */
    private static ExecPlan runPlan(Path dir, Path cache, JkBuild project, BuildLayout layout, boolean dev)
            throws IOException, InterruptedException {
        Path javaHome = projectJavaHome(dir);
        String java = javaBin(javaHome);

        if (!dev) {
            Path nativeBin = layout.nativeBinary();
            if (Files.isRegularFile(nativeBin) && Files.isExecutable(nativeBin)) {
                return runAck("run", List.of(nativeBin.toAbsolutePath().toString()), dir, javaHome,
                        nativeBin.getFileName().toString(), false, false, List.of());
            }
            Path shadow = layout.shadowJar();
            if (Files.isRegularFile(shadow)) {
                return runAck("run",
                        List.of(java, "-jar", shadow.toAbsolutePath().toString()),
                        dir, javaHome, "java -jar " + dir.relativize(shadow), false, false, List.of());
            }
        }

        // Classes-dir + RUN classpath: dev-scope deps ride; Boot's boot-layout jar (classes
        // hidden under BOOT-INF/) never lands on a -cp.
        List<Path> classpath = new ArrayList<>();
        boolean classesEntry = dev || project.isSpringBoot();
        classpath.add(classesEntry ? layout.classesDir() : layout.mainJar());

        boolean devtoolsInjected = false;
        boolean hotReload = false;
        Path lockFile = dir.resolve("jk.lock");
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            classpath.addAll(new ClasspathResolver(new Cas(cache)).classpathFor(lock, ClasspathResolver.RUN));
            if (dev) {
                hotReload = lock.artifacts().stream()
                        .anyMatch(a -> "org.springframework.boot:spring-boot-devtools".equals(a.name()));
            }
        }
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(dir, project, ClasspathResolver.RUN);
        classpath.addAll(siblings.jars());

        if (dev && !hotReload && project.isSpringBoot()) {
            // Tier 2: fetch devtools version-matched to the declared Boot line; offline
            // degrades silently to process-restart mode.
            Path devtools = fetchDevtools(project, cache);
            if (devtools != null) {
                classpath.add(devtools);
                hotReload = true;
                devtoolsInjected = true;
            }
        }

        String mainClass = project.mainClass() != null
                ? project.mainClass()
                : MainClassScanner.scanUnique(layout.classesDir());

        List<String> argv = new ArrayList<>();
        argv.add(java);
        argv.add("-cp");
        argv.add(joinPaths(classpath));
        argv.add(mainClass);

        boolean hasDeps = classpath.size() > 1;
        Path first = classpath.get(0);
        String display = first.toString().endsWith(".jar")
                ? (hasDeps ? "java -cp … -jar " : "java -jar ") + dir.relativize(first)
                : (hasDeps ? "java -cp … " : "java ") + mainClass;

        List<String> watchRoots = new ArrayList<>();
        if (dev && Files.isDirectory(dir.resolve("src"))) watchRoots.add(dir.resolve("src").toString());
        return runAck(dev ? "dev" : "run", argv, dir, javaHome, display, hotReload, devtoolsInjected, watchRoots);
    }

    private static ExecPlan runAck(
            String kind,
            List<String> argv,
            Path dir,
            Path javaHome,
            String display,
            boolean hotReload,
            boolean devtoolsInjected,
            List<String> watchRoots) {
        return new ExecPlan(
                null, kind, argv, dir.toString(),
                display, javaHome.toString(), hotReload, devtoolsInjected, watchRoots, List.of(), List.of(), "", "",
                "", false, "", "", "", List.of(), List.of());
    }

    /**
     * {@code jk install}'s application half: the gates, the {@code ~/.jk/lib} link set, and the
     * launcher script — the client applies links, writes the script, and marks it executable.
     */
    private static ExecPlan installPlan(
            Path dir,
            Path cache,
            JkBuild project,
            BuildLayout layout,
            String mainOverride,
            String binName,
            Path binDirOverride,
            Path libDirOverride)
            throws IOException {
        var p = project.project();
        String bin = binName != null && !binName.isBlank() ? binName : p.name();
        Path javaHome = projectJavaHome(dir);
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.home().resolve("bin");
        Path libDir = libDirOverride != null ? libDirOverride : JkDirs.home().resolve("lib");

        if (!project.isApplication()) {
            return ExecPlan.error(
                    "install", "not an application — declare [application] in jk.toml to make it installable");
        }

        List<String> linkSrcs = new ArrayList<>();
        List<String> linkDests = new ArrayList<>();

        // Native binary → ~/.jk/bin/<bin> directly; no launcher script.
        if (project.nativeMode() == JkBuild.NativeMode.ALWAYS) {
            Path dest = binDir.resolve(bin);
            linkSrcs.add(layout.nativeBinary().toAbsolutePath().toString());
            linkDests.add(dest.toString());
            return installAck(linkSrcs, linkDests, "", "", dest.toString());
        }

        Path launcherPath = binDir.resolve(AppLauncher.launcherFileName(bin));

        // Shadow / Boot: one self-contained jar in lib.
        if (project.shadowJar() || project.isSpringBoot()) {
            Path src = project.shadowJar() ? layout.shadowJar() : layout.mainJar();
            Path dest = libDir.resolve(src.getFileName().toString());
            linkSrcs.add(src.toAbsolutePath().toString());
            linkDests.add(dest.toString());
            String script = project.isSpringBoot()
                    ? AppLauncher.renderJarScript(javaHome, dest)
                    : AppLauncher.renderScript(javaHome, resolveMain(project, layout, mainOverride), List.of(dest));
            return installAck(linkSrcs, linkDests, launcherPath.toString(), script, launcherPath.toString());
        }

        // Plain jar: app jar + hard-linked runtime dependency jars, coordinate-named.
        List<Path> classpath = new ArrayList<>();
        Path appDest = libDir.resolve(layout.mainJar().getFileName().toString());
        linkSrcs.add(layout.mainJar().toAbsolutePath().toString());
        linkDests.add(appDest.toString());
        classpath.add(appDest);

        Path lockFile = resolveLockFile(dir);
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            for (ClasspathResolver.Entry entry :
                    new ClasspathResolver(new Cas(cache)).entriesFor(lock, ClasspathResolver.RUNTIME)) {
                if (!Files.exists(entry.jar())) continue;
                Path dest = libDir.resolve(
                        entry.artifact().moduleArtifact() + "-" + entry.artifact().version() + ".jar");
                linkSrcs.add(entry.jar().toAbsolutePath().toString());
                linkDests.add(dest.toString());
                classpath.add(dest);
            }
        }
        WorkspaceClasspath.Result siblings =
                WorkspaceClasspath.resolve(dir, project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME));
        for (Path sib : siblings.jars()) {
            Path dest = libDir.resolve(sib.getFileName().toString());
            linkSrcs.add(sib.toAbsolutePath().toString());
            linkDests.add(dest.toString());
            classpath.add(dest);
        }

        String script = AppLauncher.renderScript(javaHome, resolveMain(project, layout, mainOverride), classpath);
        return installAck(linkSrcs, linkDests, launcherPath.toString(), script, launcherPath.toString());
    }

    private static ExecPlan installAck(
            List<String> linkSrcs, List<String> linkDests, String launcherPath, String script, String binPath) {
        return new ExecPlan(
                null, "install", List.of(), "", "", "", false, false, List.of(), linkSrcs, linkDests, launcherPath,
                script, binPath, false, "", "", "", List.of(), List.of());
    }

    /** {@code jk build --aot-cache}: everything the client's layout/training step needs. */
    private static ExecPlan aotCachePlan(Path dir, Path cache, JkBuild project, BuildLayout layout)
            throws IOException {
        Path mainJar = layout.mainJar();
        if (!Files.isRegularFile(mainJar)) {
            return ExecPlan.error("aot-cache", "jar not found at " + mainJar + " — build before --aot-cache");
        }
        int major = JkBuild.Project.majorOf(project.project().jdk());
        String tier = major >= 25 ? "aot" : "cds";

        List<String> libNames = new ArrayList<>();
        List<String> libPaths = new ArrayList<>();
        Path lockFile = dir.resolve("jk.lock");
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            for (ClasspathResolver.Entry entry :
                    new ClasspathResolver(new Cas(cache)).entriesFor(lock, ClasspathResolver.RUNTIME)) {
                if (!Files.exists(entry.jar())) continue;
                libNames.add(entry.artifact().moduleArtifact() + "-"
                        + entry.artifact().version() + ".jar");
                libPaths.add(entry.jar().toAbsolutePath().toString());
            }
        }
        String mainClass = "";
        if (!project.isSpringBoot()) {
            mainClass =
                    project.mainClass() != null ? project.mainClass() : MainClassScanner.scanUnique(mainJar);
        }
        Path javaHome = projectJavaHome(dir);
        return new ExecPlan(
                null, "aot-cache", List.of(), dir.toString(), "", javaHome.toString(), false, false, List.of(),
                List.of(), List.of(), "", "", "", project.isSpringBoot(), mainJar.toAbsolutePath().toString(), tier,
                mainClass, libNames, libPaths);
    }

    // ------------------------------------------------------------- helpers

    private static String resolveMain(JkBuild project, BuildLayout layout, String mainOverride) throws IOException {
        if (mainOverride != null && !mainOverride.isBlank()) return mainOverride;
        if (project.mainClass() != null) return project.mainClass();
        return MainClassScanner.scanUnique(layout.classesDir());
    }

    /** The project-pinned JDK when resolvable; the engine's own JVM home otherwise. */
    private static Path projectJavaHome(Path dir) {
        try {
            return dev.jkbuild.jdk.JdkResolver.forProject(dir, JkDirs.home().resolve("jdks"))
                    .map(dev.jkbuild.jdk.InstalledJdk::home)
                    .orElseGet(JavaHomes::runningJavaHome);
        } catch (IOException e) {
            return JavaHomes.runningJavaHome();
        }
    }

    private static Path fetchDevtools(JkBuild project, Path cache) {
        try {
            var boot = project.springBoot().orElseThrow();
            Cas cas = new Cas(cache);
            return RepoGroupBuilder.buildFor(project, null, cas)
                    .tryFetchArtifact(dev.jkbuild.model.Coordinate.of(
                            "org.springframework.boot", "spring-boot-devtools", boot.version()))
                    .map(hit -> hit.fetched().cachePath())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Path resolveLockFile(Path projectDir) throws IOException {
        Path lockFile = projectDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) {
            var rootOpt = WorkspaceLocator.findRoot(projectDir);
            if (rootOpt.isPresent()) {
                Path candidate = rootOpt.get().resolve("jk.lock");
                if (Files.exists(candidate)) return candidate;
            }
        }
        return lockFile;
    }

    private static String javaBin(Path javaHome) {
        return javaHome.resolve("bin")
                .resolve(HostPlatform.isWindows() ? "java.exe" : "java")
                .toString();
    }

    private static String joinPaths(List<Path> paths) {
        String sep = System.getProperty("path.separator");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(paths.get(i).toAbsolutePath());
        }
        return sb.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
