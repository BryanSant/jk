// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.plugin.protocol.Ndjson;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engine side of the build-plugin code layer (build-plugins plan §3.2/3.3): learns a plugin's
 * registered steps/packager over the {@code describe} protocol (file-cached per config+facts, so
 * fully-cached builds never fork), writes execution specs, and forks the plugin's jar per
 * step/package run. The engine never classloads plugin code — declarations and executions both
 * cross the spec-file/NDJSON plugin boundary.
 *
 * <p>Static artifact shape ({@code [packaging]}) deliberately does NOT come through here — it is
 * manifest data, readable by run/install/image plan requests without any fork ({@link #shape}).
 */
public final class PluginBuild {

    private PluginBuild() {}

    /**
     * An installed plugin with a code layer, active on this project (owns a declared table).
     * {@code declaration} is the matching {@code [plugins]} entry for third-party plugins and
     * null for built-ins — it carries the coordinate the trust gate and jar lookup key on.
     */
    public record Active(
            PluginDescriptor manifest,
            PluginConfig config,
            Path moduleDir,
            cc.jumpkick.model.PluginDeclaration declaration) {}

    public static Optional<Active> activeCodePlugin(JkBuild project, Path moduleDir) {
        for (PluginDescriptor m : PluginTableRegistry.manifestsFor(moduleDir, project.plugins())) {
            if (m.code() == null) continue;
            Optional<PluginConfig> config = project.pluginConfig(m.id());
            if (config.isPresent()) {
                cc.jumpkick.model.PluginDeclaration declaration = PluginTableRegistry.isBuiltIn(m.id())
                        ? null
                        : PluginDescriptorOps.declarationOf(moduleDir, project, m.id()).orElse(null);
                return Optional.of(new Active(m, config.get(), moduleDir, declaration));
            }
        }
        return Optional.empty();
    }

    /** The active packager's static artifact descriptor, or empty — manifest data, no fork. */
    public static Optional<PluginDescriptor.Packaging> shape(JkBuild project, Path moduleDir) {
        for (PluginDescriptor m : PluginTableRegistry.manifestsFor(moduleDir, project.plugins())) {
            if (m.packaging() == null) continue;
            Optional<PluginConfig> config = project.pluginConfig(m.id());
            if (config.isPresent()) {
                // Config-conditional variants ([[packaging.variant]]): an [android] library
                // packages an AAR while an app packages an APK — same plugin, one manifest.
                return Optional.of(m.packaging().resolve(config.get()));
            }
        }
        return Optional.empty();
    }

    // ---- declarations (describe, file-cached) -------------------------------------------------

    /** One registered step, as declared over the describe protocol. */
    public record StepDecl(
            String name,
            String after,
            String before,
            List<String> inputs,
            List<String> outputs,
            List<String> contributesClasses,
            List<String> contributesResources,
            List<String> contributesSources,
            List<String> contributesTestClasspath,
            /** The classes-replacing output dir ({@code StepSpec.transformsClasses}), or null. */
            String transformsClasses) {

        /** True when this step replaces the module's classes dir downstream. */
        public boolean transforms() {
            return transformsClasses != null && !transformsClasses.isBlank();
        }

        /** The coarse {@link cc.jumpkick.plugin.build.Phase} this step orders itself after, or null. */
        public cc.jumpkick.plugin.build.Phase afterPhase() {
            return after == null ? null : cc.jumpkick.plugin.build.Phase.fromWire(after);
        }

        /** The coarse {@link cc.jumpkick.plugin.build.Phase} this step orders itself before, or null. */
        public cc.jumpkick.plugin.build.Phase beforePhase() {
            return before == null ? null : cc.jumpkick.plugin.build.Phase.fromWire(before);
        }
    }

    /** The registered packager, as declared. */
    public record PackagerDecl(String name, List<String> inputs) {}

    /** One registered plugin command, as declared. */
    public record CommandDecl(String name, String description) {}

    public record Declarations(List<StepDecl> steps, PackagerDecl packager, List<CommandDecl> commands) {

        public StepDecl step(String name) {
            for (StepDecl s : steps) if (s.name().equals(name)) return s;
            return null;
        }

        public CommandDecl command(String name) {
            for (CommandDecl v : commands) if (v.name().equals(name)) return v;
            return null;
        }
    }

    /** A step's scratch root — its declared output dirs resolve under this. */
    public static Path stepScratch(BuildLayout layout, String stepName) {
        return layout.moduleTargetDir().resolve("plugin").resolve(stepName);
    }

    /** Every dir the declared steps contribute as classes/resources, in declaration order. */
    public static List<Path> contributedDirs(Declarations decls, BuildLayout layout) {
        List<Path> out = new ArrayList<>();
        for (StepDecl step : decls.steps()) {
            Path scratch = stepScratch(layout, step.name());
            for (String rel : step.contributesClasses()) out.add(scratch.resolve(rel));
            for (String rel : step.contributesResources()) out.add(scratch.resolve(rel));
        }
        return out;
    }

    /**
     * The plugin's registered declarations for this project. Registration runs plugin code, so it
     * happens in the plugin ({@code describe}); the answer is a pure function of (jk version,
     * plugin version, config, registration-visible facts), so it is cached in a content-keyed file
     * under the module's target dir — a fully-cached rebuild reads the file and forks nothing.
     */
    public static Declarations declarations(Active active, JkBuild project, Path moduleDir, Path cache, Path layoutTarget)
            throws IOException, InterruptedException {
        String key = describeKey(active, project);
        Path cacheFile = layoutTarget.resolve("plugin").resolve(active.manifest().id() + "-describe-" + key + ".ndjson");
        List<String> lines;
        if (Files.isRegularFile(cacheFile)) {
            lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
        } else {
            Path spec = Files.createTempFile("jk-plugin-describe-", ".spec");
            try {
                List<String> specLines = new ArrayList<>();
                specLines.add("{\"t\":\"op\",\"op\":\"describe\",\"plugin\":" + Ndjson.quote(active.manifest().id()) + "}");
                appendConfig(specLines, active.config());
                appendProject(specLines, project, project.mainClass());
                Files.write(spec, specLines, StandardCharsets.UTF_8);
                lines = runWorker(active, cache, spec, null);
            } finally {
                Files.deleteIfExists(spec);
            }
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, lines, StandardCharsets.UTF_8);
        }

        return decode(lines);
    }

    /** Decode a describe reply's declaration lines (the cached file's exact content). */
    static Declarations decode(List<String> lines) {
        List<StepDecl> steps = new ArrayList<>();
        PackagerDecl packager = null;
        List<CommandDecl> commands = new ArrayList<>();
        for (String line : lines) {
            switch (String.valueOf(Ndjson.str(line, "t"))) {
                case "step" ->
                    steps.add(new StepDecl(
                            Ndjson.str(line, "name"),
                            Ndjson.str(line, "after"),
                            Ndjson.str(line, "before"),
                            Ndjson.strArray(line, "inputs"),
                            Ndjson.strArray(line, "outputs"),
                            Ndjson.strArray(line, "contributesClasses"),
                            Ndjson.strArray(line, "contributesResources"),
                            Ndjson.strArray(line, "contributesSources"),
                            Ndjson.strArray(line, "contributesTestClasspath"),
                            Ndjson.str(line, "transformsClasses")));
                case "packager" ->
                    packager = new PackagerDecl(Ndjson.str(line, "name"), Ndjson.strArray(line, "inputs"));
                case "command" ->
                    commands.add(new CommandDecl(Ndjson.str(line, "name"), Ndjson.str(line, "description")));
                default -> {
                    // labels etc. — irrelevant to declarations
                }
            }
        }
        return new Declarations(steps, packager, commands);
    }

    private static String describeKey(Active active, JkBuild project) {
        StringBuilder b = new StringBuilder();
        b.append(cc.jumpkick.model.BuildIdentity.cacheKeyVersion())
                .append('|')
                .append(active.manifest().version())
                .append('|')
                .append(configToken(active.config()))
                .append('|')
                .append(project.project().group())
                .append(':')
                .append(project.project().name())
                .append(':')
                .append(project.project().version())
                .append('|')
                .append(project.project().javaRelease())
                .append('|')
                .append(project.nativeConfig().isPresent())
                .append('|')
                .append(project.project().isKotlin())
                .append('|')
                .append(String.valueOf(project.mainClass()));
        return Hashing.sha256Hex(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** A stable render of the validated config — the token action keys carry for In.config(). */
    public static String configToken(PluginConfig config) {
        StringBuilder b = new StringBuilder(config.id());
        appendToken(b, config.values());
        return Hashing.sha256Hex(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    @SuppressWarnings("unchecked")
    private static void appendToken(StringBuilder b, Map<String, Object> values) {
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(values).entrySet()) {
            if (e.getValue() instanceof Map<?, ?> m) {
                b.append('|').append(e.getKey()).append("={");
                appendToken(b, (Map<String, Object>) m);
                b.append('}');
            } else {
                b.append('|').append(e.getKey()).append('=').append(e.getValue());
            }
        }
    }

    // ---- execution -----------------------------------------------------------------------------

    /** The resolved packager-dependency artifacts, fetched into the CAS by coordinate. */
    public static Map<String, Path> fetchPackagerDependencies(JkBuild project, Path moduleDir, Cas cas)
            throws IOException, InterruptedException {
        Map<String, Path> out = new LinkedHashMap<>();
        List<PluginContributions.PackagerDep> deps = PluginContributions.packagerDependencies(project, moduleDir);
        if (deps.isEmpty()) return out;
        cc.jumpkick.repo.RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
        for (PluginContributions.PackagerDep dep : deps) {
            out.put(dep.artifact(), fetchArtifact(repos, dep.module(), dep.version()));
        }
        return out;
    }

    /**
     * The resolved step-dependency artifacts ({@code [[contribute.step-dependency]]}): tool jars
     * a step body needs (aapt2, r8, a platform jar), fetched into the CAS by coordinate —
     * classifier-aware ({@code group:artifact:version:classifier}), never on the project graph.
     */
    public static Map<String, Path> fetchStepDependencies(JkBuild project, Path moduleDir, Cas cas)
            throws IOException, InterruptedException {
        return fetchStepDependencies(project, moduleDir, cas, Map.of());
    }

    /**
     * As above with {@code jk.lock}'s provisioned-SDK revision pins ({@code [[sdk]]}) — drift
     * between the pin and the installed component is reported, never silently built over.
     */
    public static Map<String, Path> fetchStepDependencies(
            JkBuild project, Path moduleDir, Cas cas, Map<String, String> sdkPins)
            throws IOException, InterruptedException {
        return fetchStepDependencies(project, moduleDir, cas, sdkPins, false);
    }

    /**
     * As above; {@code lenient} makes each fetch best-effort — a tool that cannot provision
     * (a license-gated SDK component, an unreachable repo) is simply absent from the map, and a
     * consumer that needs it fails with its own message ({@code requireExtra}). Command dispatch
     * uses this: {@code jk android licenses} must be reachable BEFORE any license is accepted,
     * and it only needs the license-free SDK root.
     */
    public static Map<String, Path> fetchStepDependencies(
            JkBuild project, Path moduleDir, Cas cas, Map<String, String> sdkPins, boolean lenient)
            throws IOException, InterruptedException {
        Map<String, Path> out = new LinkedHashMap<>();
        List<PluginContributions.StepDep> deps = PluginContributions.stepDependencies(project, moduleDir);
        if (deps.isEmpty()) return out;
        cc.jumpkick.repo.RepoGroup repos = null;
        for (PluginContributions.StepDep dep : deps) {
            try {
                if (dep.sdkComponent() != null) {
                    out.put(
                            dep.artifact(),
                            SdkComponents.resolve(
                                    dep.sdkComponent(), dep.sdkPath(), sdkPins.get(dep.sdkComponent())));
                    continue;
                }
                if (repos == null) repos = RepoGroupBuilder.buildFor(project, null, cas);
                if (dep.transitive()) {
                    out.put(dep.artifact(), toolClosureDir(dep.coordinateSpec(), repos, cas));
                    continue;
                }
                cc.jumpkick.model.Coordinate coord = cc.jumpkick.model.Coordinate.parse(dep.coordinateSpec());
                out.put(
                        dep.artifact(),
                        repos.tryFetchArtifact(coord)
                                .orElseThrow(() -> new IOException("cannot fetch " + coord
                                        + " — the coordinate a plugin's step-dependency names must exist in a"
                                        + " declared repo"))
                                .fetched()
                                .cachePath());
            } catch (IOException | RuntimeException e) {
                if (!lenient) throw e;
                // Best-effort: the command that needs this tool reports the miss itself.
            }
        }
        return out;
    }

    /**
     * A {@code transitive = true} step-dependency: the coordinate's full runtime closure,
     * materialized as a directory of hard-linked jars under the cache — JVM tools with real
     * dependency graphs (manifest-merger) fork with {@code -cp <dir>/*}. Keyed by the exact
     * (version-pinned) coordinate, so an existing non-empty dir is authoritative.
     */
    private static Path toolClosureDir(String coordinateSpec, cc.jumpkick.repo.RepoGroup repos, Cas cas)
            throws IOException, InterruptedException {
        Path dir = cas.root().resolve("plugin-tools").resolve(coordinateSpec.replace(':', '_'));
        if (Files.isDirectory(dir)) {
            try (var listing = Files.list(dir)) {
                if (listing.findFirst().isPresent()) return dir;
            }
        }
        cc.jumpkick.model.Coordinate root = cc.jumpkick.model.Coordinate.parse(coordinateSpec);
        var declared = List.of(new cc.jumpkick.model.Dependency(
                root.group() + ":" + root.artifact(),
                cc.jumpkick.model.VersionSelector.parse("=" + root.version())));
        var resolution = new cc.jumpkick.resolver.NaiveResolver(new cc.jumpkick.repo.EffectivePomBuilder(repos))
                .resolve(declared);
        java.util.LinkedHashSet<String> modules = new java.util.LinkedHashSet<>();
        modules.add(root.group() + ":" + root.artifact());
        modules.addAll(resolution.modules().keySet());

        Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".closure-");
        for (String module : modules) {
            var resolved = resolution.modules().get(module);
            cc.jumpkick.model.Coordinate coord = resolved != null ? resolved.coordinate() : root;
            Path jar = repos.tryFetchArtifact(coord)
                    .orElseThrow(() -> new IOException("cannot fetch " + coord
                            + " — a transitive step-dependency's closure must exist in a declared repo"))
                    .fetched()
                    .cachePath();
            Path alias = staging.resolve(coord.artifact() + "-" + coord.version() + ".jar");
            try {
                Files.createLink(alias, jar);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(jar, alias);
            }
        }
        try {
            Files.move(staging, dir, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (!Files.isDirectory(dir)) throw e; // lost a race → the winner's dir serves
        }
        return dir;
    }

    /** The {@code [[sdk]]} revision pins of {@code lockFile}, or empty (no lock / none recorded). */
    public static Map<String, String> sdkPins(Path lockFile) {
        if (lockFile == null || !Files.isRegularFile(lockFile)) return Map.of();
        try {
            Map<String, String> pins = new LinkedHashMap<>();
            for (var e : cc.jumpkick.lock.LockfileReader.read(lockFile).sdk()) {
                pins.put(e.component(), e.revision());
            }
            return pins;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Fetch one {@code module:version} jar into the CAS and return its path. */
    private static Path fetchArtifact(cc.jumpkick.repo.RepoGroup repos, String module, String version)
            throws IOException, InterruptedException {
        int colon = module.indexOf(':');
        cc.jumpkick.model.Coordinate coord =
                cc.jumpkick.model.Coordinate.of(module.substring(0, colon), module.substring(colon + 1), version);
        return repos.tryFetchArtifact(coord)
                .orElseThrow(() -> new IOException("cannot fetch " + coord
                        + " — the version a plugin's packager-dependency names must exist in a declared repo"))
                .fetched()
                .cachePath();
    }

    /**
     * The production classpath a step sees ({@code In.runtimeClasspath()}): lockfile RUNTIME jars
     * + workspace sibling jars (and their transitive RUNTIME deps). The module's own classes dir
     * is NOT included — the plugin adds {@code exec.classesDir()} itself.
     */
    public static List<Path> productionClasspath(Path projectDir, Path cache, Path lockFile, JkBuild project)
            throws IOException {
        List<Path> classpath = new ArrayList<>();
        if (Files.exists(lockFile)) {
            var resolver = new cc.jumpkick.compile.ClasspathResolver(new Cas(cache));
            classpath.addAll(resolver.classpathFor(
                    cc.jumpkick.lock.LockfileReader.read(lockFile), cc.jumpkick.compile.ClasspathResolver.RUNTIME));
        }
        try {
            var siblings = cc.jumpkick.config.WorkspaceClasspath.resolve(
                    projectDir, project, java.util.Set.of(cc.jumpkick.model.Scope.EXPORT, cc.jumpkick.model.Scope.MAIN));
            for (Path jar : siblings.jars()) {
                if (!classpath.contains(jar)) classpath.add(jar);
            }
            for (Path sibLock : siblings.siblingLockfiles()) {
                try {
                    var sib = cc.jumpkick.lock.LockfileReader.read(sibLock);
                    for (Path pth : new cc.jumpkick.compile.ClasspathResolver(new Cas(cache))
                            .classpathFor(sib, cc.jumpkick.compile.ClasspathResolver.RUNTIME)) {
                        if (!classpath.contains(pth)) classpath.add(pth);
                    }
                } catch (Exception ignored) {
                    /* best-effort, mirrors shadow packaging */
                }
            }
        } catch (Exception ignored) {
            /* no workspace — fine */
        }
        return classpath;
    }

    /** One production runtime entry the engine hands a step/packager (container-aware). */
    public record ProdEntry(String fileName, Path jar, boolean snapshot, Path container) {}

    /**
     * The production RUNTIME entries a step sees ({@code In.runtimeEntries()}): lock-ordered
     * artifacts (an AAR rides as its exploded container + classes.jar) followed by workspace
     * sibling artifacts (a sibling that also produced a container artifact — e.g. an [android]
     * library's AAR next to its conventional classes jar — rides with that container attached).
     */
    public static List<ProdEntry> productionEntries(Path projectDir, Path cache, Path lockFile, JkBuild project)
            throws IOException {
        List<ProdEntry> out = new ArrayList<>();
        Cas cas = new Cas(cache);
        if (Files.exists(lockFile)) {
            var resolver = new cc.jumpkick.compile.ClasspathResolver(cas);
            for (var entry : resolver.entriesFor(
                    cc.jumpkick.lock.LockfileReader.read(lockFile), cc.jumpkick.compile.ClasspathResolver.RUNTIME)) {
                var a = entry.artifact();
                String ext = entry.container() != null ? ".aar" : ".jar";
                out.add(new ProdEntry(
                        a.moduleArtifact() + "-" + a.version() + ext,
                        entry.jar(),
                        a.version().contains("SNAPSHOT"),
                        entry.container()));
            }
        }
        try {
            var siblings = cc.jumpkick.config.WorkspaceClasspath.resolve(
                    projectDir, project, java.util.Set.of(cc.jumpkick.model.Scope.EXPORT, cc.jumpkick.model.Scope.MAIN));
            for (Path jar : siblings.jars()) {
                Path container = null;
                String name = jar.getFileName().toString();
                Path aar = jar.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".aar");
                if (Files.isRegularFile(aar)) {
                    container = cc.jumpkick.cache.ExplodedArchives.explodeFile(cas, aar);
                    name = aar.getFileName().toString();
                }
                out.add(new ProdEntry(name, Files.isRegularFile(jar) ? jar : null, true, container));
            }
        } catch (Exception ignored) {
            /* no workspace — fine */
        }
        return out;
    }

    /**
     * The main artifact's path under the packager's declared extension ({@code
     * target/lib/<name>-<version>.apk}) — the one place the extension swap lives.
     */
    public static Path mainArtifactPath(cc.jumpkick.layout.BuildLayout layout, Active active) {
        Path jarPath = layout.mainJar();
        var packaging = active.manifest().packaging();
        if (packaging != null) packaging = packaging.resolve(active.config());
        String ext = packaging == null ? "jar" : packaging.artifactExtension();
        if (!"jar".equals(ext)) {
            String fileName = jarPath.getFileName().toString();
            jarPath = jarPath.resolveSibling(fileName.substring(0, fileName.length() - ".jar".length()) + "." + ext);
        }
        return jarPath;
    }

    /** The spec for one step/package execution — mirror of {@code BuildPluginHarness.Spec}. */
    public static final class SpecWriter {
        private final List<String> lines = new ArrayList<>();

        public SpecWriter op(String op, String step, String pluginId) {
            StringBuilder b = new StringBuilder("{\"t\":\"op\",\"op\":").append(Ndjson.quote(op));
            if (step != null) b.append(",\"step\":").append(Ndjson.quote(step));
            b.append(",\"plugin\":").append(Ndjson.quote(pluginId)).append('}');
            lines.add(b.toString());
            return this;
        }

        public SpecWriter config(PluginConfig config) {
            appendConfig(lines, config);
            return this;
        }

        public SpecWriter project(JkBuild project, String resolvedMain) {
            appendProject(lines, project, resolvedMain);
            return this;
        }

        public SpecWriter layout(Path classesDir, Path moduleDir, Path scratch) {
            lines.add("{\"t\":\"layout\",\"classesDir\":" + Ndjson.quote(String.valueOf(classesDir))
                    + ",\"moduleDir\":" + Ndjson.quote(String.valueOf(moduleDir))
                    + ",\"scratch\":" + Ndjson.quote(String.valueOf(scratch)) + "}");
            return this;
        }

        public SpecWriter javaHome(Path javaHome) {
            lines.add("{\"t\":\"java-home\",\"path\":" + Ndjson.quote(javaHome.toString()) + "}");
            return this;
        }

        public SpecWriter artifact(Path path) {
            lines.add("{\"t\":\"artifact\",\"path\":" + Ndjson.quote(path.toAbsolutePath().toString()) + "}");
            return this;
        }

        public SpecWriter classpath(List<Path> entries) {
            for (Path p : entries) {
                lines.add("{\"t\":\"cp\",\"path\":" + Ndjson.quote(p.toAbsolutePath().toString()) + "}");
            }
            return this;
        }

        public SpecWriter entry(String fileName, Path jar, boolean snapshot) {
            return entry(fileName, jar, snapshot, null);
        }

        public SpecWriter entry(String fileName, Path jar, boolean snapshot, Path container) {
            StringBuilder b = new StringBuilder("{\"t\":\"entry\",\"file\":").append(Ndjson.quote(fileName));
            if (jar != null) b.append(",\"path\":").append(Ndjson.quote(jar.toAbsolutePath().toString()));
            b.append(",\"snapshot\":").append(snapshot);
            if (container != null) {
                b.append(",\"container\":").append(Ndjson.quote(container.toAbsolutePath().toString()));
            }
            b.append('}');
            lines.add(b.toString());
            return this;
        }

        public SpecWriter stepOutput(String name, Path dir) {
            lines.add("{\"t\":\"step-output\",\"name\":" + Ndjson.quote(name) + ",\"dir\":"
                    + Ndjson.quote(dir.toAbsolutePath().toString()) + "}");
            return this;
        }

        public SpecWriter commandArgs(List<String> args) {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) arr.append(',');
                arr.append(Ndjson.quote(args.get(i)));
            }
            arr.append(']');
            lines.add("{\"t\":\"command-args\",\"values\":" + arr + "}");
            return this;
        }

        /**
         * A resolved secret (signing credentials): package specs only — never describe or step
         * specs, never action-key plaintext (the key carries a digest), never echoed by plugins.
         */
        public SpecWriter secret(String key, String value) {
            lines.add("{\"t\":\"secret\",\"key\":" + Ndjson.quote(key) + ",\"value\":"
                    + Ndjson.quote(value) + "}");
            return this;
        }

        public SpecWriter extra(String name, Path path) {
            lines.add("{\"t\":\"extra\",\"name\":" + Ndjson.quote(name) + ",\"path\":"
                    + Ndjson.quote(path.toAbsolutePath().toString()) + "}");
            return this;
        }

        public Path write() throws IOException {
            Path spec = Files.createTempFile("jk-plugin-", ".spec");
            Files.write(spec, lines, StandardCharsets.UTF_8);
            return spec;
        }
    }

    /**
     * The jar a plugin's code hooks fork: first-party plugins name a registered {@link PluginJar};
     * a third-party plugin IS its jar — the [plugins]-declared, lock-pinned, SHA-verified jar
     * from the CAS — and it forks only once its coordinate is trusted (plugin-refactor Posture A:
     * the engine refuses untrusted third-party code with the {@code jk trust plugin} remediation).
     */
    static Path workerJarFor(Active active, Path cache) throws IOException {
        if (PluginTableRegistry.isBuiltIn(active.manifest().id())) {
            PluginJar workerJar = PluginJar.byArtifactId(active.manifest().code().worker())
                    .orElseThrow(() -> new IllegalStateException(
                            "plugin " + active.manifest().id() + " names unregistered worker "
                                    + active.manifest().code().worker()));
            return workerJar.locate(new Cas(cache));
        }
        cc.jumpkick.model.PluginDeclaration declaration = active.declaration();
        if (declaration == null) {
            throw new IOException("plugin " + active.manifest().id()
                    + " has no matching [plugins] declaration — declare it (or run `jk sync`)");
        }
        // The trust file lives in the machine's state dir; the sysprop is the test seam,
        // exactly like the jk.<worker>.jar properties the plugin registry uses.
        String stateOverride = System.getProperty("jk.trust.state.dir");
        Path stateDir = stateOverride != null ? Path.of(stateOverride) : cc.jumpkick.util.JkDirs.state();
        cc.jumpkick.tool.TrustedPlugins trust;
        try {
            trust = cc.jumpkick.tool.TrustedPlugins.load(stateDir);
        } catch (IOException e) {
            trust = null;
        }
        if (trust == null || !trust.isTrusted(declaration.coordinate())) {
            throw new IOException("plugin " + declaration.coordinateWithVersion()
                    + " is not trusted to run build code on this machine.\n"
                    + "Trust it first: jk trust plugin " + declaration.coordinate());
        }
        return PluginDescriptorOps.jarFor(active.moduleDir(), declaration, cache)
                .orElseThrow(() -> new IOException("plugin " + declaration.coordinateWithVersion()
                        + " is not in the local cache — run `jk sync` first"));
    }

    private static void appendConfig(List<String> lines, PluginConfig config) {
        for (Map.Entry<String, Object> e : config.values().entrySet()) {
            Object v = e.getValue();
            String key = Ndjson.quote(e.getKey());
            if (v instanceof String s) {
                lines.add("{\"t\":\"config\",\"key\":" + key + ",\"kind\":\"string\",\"value\":" + Ndjson.quote(s)
                        + "}");
            } else if (v instanceof Boolean b) {
                lines.add("{\"t\":\"config\",\"key\":" + key + ",\"kind\":\"bool\",\"value\":" + b + "}");
            } else if (v instanceof Long l) {
                lines.add("{\"t\":\"config\",\"key\":" + key + ",\"kind\":\"int\",\"value\":" + l + "}");
            } else if (v instanceof List<?> list) {
                StringBuilder arr = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) arr.append(',');
                    arr.append(Ndjson.quote(String.valueOf(list.get(i))));
                }
                arr.append(']');
                lines.add("{\"t\":\"config\",\"key\":" + key + ",\"kind\":\"list\",\"values\":" + arr + "}");
            }
        }
    }

    private static void appendProject(List<String> lines, JkBuild project, String resolvedMain) {
        StringBuilder b = new StringBuilder("{\"t\":\"project\",\"group\":")
                .append(Ndjson.quote(project.project().group()))
                .append(",\"name\":")
                .append(Ndjson.quote(project.project().name()))
                .append(",\"version\":")
                .append(Ndjson.quote(project.project().version()))
                .append(",\"javaRelease\":")
                .append(project.project().javaRelease())
                .append(",\"nativeDeclared\":")
                .append(project.nativeConfig().isPresent())
                .append(",\"kotlin\":")
                .append(project.project().isKotlin());
        if (resolvedMain != null && !resolvedMain.isBlank()) {
            b.append(",\"mainClass\":").append(Ndjson.quote(resolvedMain));
        }
        b.append('}');
        lines.add(b.toString());
        for (Map.Entry<String, String> e : project.manifest().entrySet()) {
            lines.add("{\"t\":\"manifest-attr\",\"key\":" + Ndjson.quote(e.getKey()) + ",\"value\":"
                    + Ndjson.quote(e.getValue()) + "}");
        }
    }

    /**
     * Fork the plugin on the spec and collect its protocol lines. Throws with the
     * plugin's own error message when it reports one (or exits non-zero without reporting).
     */
    public static List<String> runWorker(Active active, Path cache, Path spec, java.util.function.Consumer<String> onLabel)
            throws IOException, InterruptedException {
        Path jar = workerJarFor(active, cache);
        List<String> collected = new ArrayList<>();
        String[] error = new String[1];
        PluginClient client = new PluginClient(active.manifest().code().protocolPrefix())
                .on("label", line -> {
                    if (onLabel != null) onLabel.accept(Ndjson.str(line, "text"));
                })
                .on("error", line -> error[0] = Ndjson.str(line, "message"))
                .onOther(collected::add);
        int exit = client.run(PluginLaunch.javaCommand(jar, spec));
        if (error[0] != null) {
            throw new IOException(error[0]);
        }
        if (exit != 0) {
            throw new IOException("plugin worker " + active.manifest().id() + " failed (exit " + exit + ")");
        }
        return collected;
    }
}
