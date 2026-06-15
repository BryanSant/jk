// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.git.GitFetcher;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds composite source dependencies — {@code path = "../foo"} deps and
 * <em>branch</em> git deps — from source and contributes their jars to the
 * consumer's classpath. jk's analog of Gradle's {@code includeBuild} (composite
 * build): the target is a live local project, rebuilt on demand and injected at
 * classpath time, never written to {@code jk.lock} (cf. {@link WorkspaceClasspath}
 * for in-workspace siblings; immutable git deps stay on the lock-pinned
 * {@link GitSourceResolution} path).
 *
 * <p>For each composite dep the resolver: locates the target dir (a {@code path}
 * directory, or a branch git checkout via {@link GitFetcher}), parses its
 * {@code jk.toml}, builds it headlessly with {@link LocalProjectBuilder}
 * (Java + Kotlin, layout-aware) when stale, and returns the built main jar plus
 * the target's own external (Maven) transitive deps. Targets that themselves
 * declare composite deps are built first (post-order), so a parent compiles
 * against its children.
 */
public final class CompositeDepResolver {

    /**
     * @param jars            built main jars of each composite target (direct + transitive)
     * @param externalDepJars each target's external Maven transitive deps
     * @param errors          human-readable failures (coordinate mismatch, cycle, missing jk.toml)
     */
    public record Result(List<Path> jars, List<Path> externalDepJars, List<String> errors) {
        public boolean isEmpty() { return jars.isEmpty() && externalDepJars.isEmpty(); }
    }

    private CompositeDepResolver() {}

    /** Whether {@code project} declares any composite dep in {@code scopes} (cheap model scan). */
    public static boolean has(JkBuild project, Set<Scope> scopes) {
        for (Scope scope : scopes) {
            for (Dependency d : project.dependencies().of(scope)) {
                if (isComposite(d)) return true;
            }
        }
        return false;
    }

    /**
     * Resolve the consumer's composite deps in {@code depScopes}. {@code externalCpScopes}
     * is the {@link ClasspathResolver} scope set used to collect each target's external
     * deps (e.g. {@link ClasspathResolver#COMPILE_MAIN} for compile, {@link
     * ClasspathResolver#RUNTIME} for run). {@code gitRoot} is the git checkout cache root
     * (typically {@code $JK_CACHE/git}).
     */
    public static Result resolve(Path consumerDir, JkBuild consumer, Set<Scope> depScopes,
                                 Set<Scope> externalCpScopes, Cas cas, Path javaHome,
                                 String jkVersion, Path gitRoot)
            throws IOException, InterruptedException {
        Ctx ctx = new Ctx(cas, javaHome, jkVersion, gitRoot, externalCpScopes);
        for (Dependency d : compositeDeps(consumer, depScopes)) {
            ctx.build(consumerDir, d);
        }
        return new Result(new ArrayList<>(ctx.jars),
                new ArrayList<>(ctx.externalDepJars), ctx.errors);
    }

    /** The composite (path / branch-git) deps declared across {@code scopes}, deduped by module. */
    private static List<Dependency> compositeDeps(JkBuild project, Set<Scope> scopes) {
        List<Dependency> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency d : project.dependencies().of(scope)) {
                if (isComposite(d) && seen.add(d.module())) out.add(d);
            }
        }
        return out;
    }

    static boolean isComposite(Dependency d) {
        return d.isPath() || (d.isGit() && !d.gitSource().ref().isImmutable());
    }

    /** Mutable per-run state: accumulators, memo, and the recursion stack for cycle detection. */
    private static final class Ctx {
        final Cas cas;
        final Path javaHome;
        final String jkVersion;
        final Path gitRoot;
        final Set<Scope> externalCpScopes;
        final LinkedHashSet<Path> jars = new LinkedHashSet<>();
        final LinkedHashSet<Path> externalDepJars = new LinkedHashSet<>();
        final List<String> errors = new ArrayList<>();
        final Map<Path, Path> built = new LinkedHashMap<>();   // canonical dir → built main jar
        final LinkedHashSet<Path> onStack = new LinkedHashSet<>(); // for cycle detection

        Ctx(Cas cas, Path javaHome, String jkVersion, Path gitRoot, Set<Scope> externalCpScopes) {
            this.cas = cas; this.javaHome = javaHome; this.jkVersion = jkVersion;
            this.gitRoot = gitRoot; this.externalCpScopes = externalCpScopes;
        }

        /** Build one composite dep declared by a project rooted at {@code fromDir}; returns its main jar (or null on error). */
        Path build(Path fromDir, Dependency dep) throws IOException, InterruptedException {
            Path targetDir = targetDirOf(fromDir, dep);
            if (targetDir == null) return null;           // error already recorded
            Path key = canonical(targetDir);
            Path memo = built.get(key);
            if (memo != null) return memo;                // already resolved
            if (onStack.contains(key)) {
                errors.add("composite dependency cycle through " + targetDir);
                return null;
            }

            Path toml = targetDir.resolve("jk.toml");
            if (!Files.isRegularFile(toml)) {
                errors.add("composite dependency `" + dep.module() + "` has no jk.toml at " + targetDir);
                return null;
            }
            JkBuild target;
            try {
                target = JkBuildParser.parse(Files.readString(toml));
            } catch (RuntimeException e) {
                errors.add("composite dependency `" + dep.module() + "` failed to parse " + toml
                        + ": " + e.getMessage());
                return null;
            }
            String coord = target.project().group() + ":" + target.project().name();
            if (!coord.equals(dep.module())) {
                errors.add("composite dependency `" + dep.module() + "` points at a project whose "
                        + "coordinate is `" + coord + "` (" + targetDir + "); they must match");
                return null;
            }

            onStack.add(key);
            // Post-order: build this target's own composite deps first, so it compiles
            // against them. Only MAIN propagates transitively (cf. WorkspaceClasspath).
            List<Path> childJars = new ArrayList<>();
            for (Dependency child : compositeDeps(target, Set.of(Scope.MAIN))) {
                Path cj = build(targetDir, child);
                if (cj != null) childJars.add(cj);
            }
            onStack.remove(key);

            RepoGroup repos = RepoGroupBuilder.buildFor(target, null, cas);
            String version = target.project().version();
            Path builtJar = ensureBuilt(targetDir, target, repos, childJars);

            jars.add(builtJar);
            jars.addAll(childJars);
            // The target's own external (Maven) transitive deps must also reach the
            // consumer classpath (mirrors BuildPipeline's sibling-lockfile loop).
            Lockfile targetLock = new LockOrchestrator(repos).lock(target, jkVersion);
            externalDepJars.addAll(new ClasspathResolver(cas).classpathFor(targetLock, externalCpScopes));

            built.put(key, builtJar);
            return builtJar;
        }

        /** Reuse {@link BuildLayout#mainJar()} when fresh; otherwise build it. */
        private Path ensureBuilt(Path targetDir, JkBuild target, RepoGroup repos, List<Path> childJars)
                throws IOException, InterruptedException {
            Path jar = BuildLayout.of(targetDir, target).mainJar();
            boolean noCache = ActiveConfig.get().noCacheOr(false);
            if (!noCache && Files.isRegularFile(jar) && !isStale(targetDir, target, jar)) {
                return jar;
            }
            LocalProjectBuilder.build(targetDir, target,
                    target.project().group(), target.project().name(), target.project().version(),
                    javaHome, cas, repos, jkVersion, childJars);
            return jar;
        }

        /** True when any target source (or its {@code jk.toml}) is newer than the built jar. */
        private boolean isStale(Path targetDir, JkBuild target, Path jar) throws IOException {
            FileTime jarTime = Files.getLastModifiedTime(jar);
            boolean simple = CompileSupport.isSimpleLayout(target.project(), targetDir);
            Path javaRoot = simple ? targetDir.resolve("src") : targetDir.resolve("src/main/java");
            List<Path> sources = new ArrayList<>(CompileSupport.collectJavaSources(javaRoot));
            sources.addAll(CompileSupport.collectKotlinSources(targetDir, simple));
            sources.add(targetDir.resolve("jk.toml"));
            for (Path s : sources) {
                if (Files.exists(s) && Files.getLastModifiedTime(s).compareTo(jarTime) > 0) return true;
            }
            return false;
        }

        /** Resolve a composite dep's project directory, recording an error and returning null on failure. */
        private Path targetDirOf(Path fromDir, Dependency dep) throws IOException, InterruptedException {
            if (dep.isPath()) {
                return fromDir.resolve(dep.pathSource()).normalize();
            }
            // branch git dep: clone/checkout into the git cache, then treat the
            // checkout (+ optional monorepo subpath) as a path target.
            GitSource src = dep.gitSource();
            GitFetcher.Fetched fetched = new GitFetcher(gitRoot).fetch(src);
            Path checkout = fetched.checkoutPath();
            return (src.path() != null && !src.path().isBlank())
                    ? checkout.resolve(src.path()) : checkout;
        }

        private static Path canonical(Path p) {
            try {
                return p.toRealPath();
            } catch (IOException e) {
                return p.toAbsolutePath().normalize();
            }
        }
    }
}
