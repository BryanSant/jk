// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.PluginConfig;
import dev.jkbuild.plugin.manifest.PluginContributions;
import dev.jkbuild.plugin.manifest.PluginManifest;
import dev.jkbuild.plugin.manifest.PluginTableRegistry;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.worker.WorkerClient;
import dev.jkbuild.worker.WorkerJar;
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
 * fully-cached builds never fork), writes execution specs, and forks the plugin's worker jar per
 * step/package run. The engine never classloads plugin code — declarations and executions both
 * cross the spec-file/NDJSON worker boundary.
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
            PluginManifest manifest,
            PluginConfig config,
            Path moduleDir,
            dev.jkbuild.model.PluginDeclaration declaration) {}

    public static Optional<Active> activeCodePlugin(JkBuild project, Path moduleDir) {
        for (PluginManifest m : PluginTableRegistry.manifestsFor(moduleDir, project.plugins())) {
            if (m.code() == null) continue;
            Optional<PluginConfig> config = project.pluginConfig(m.id());
            if (config.isPresent()) {
                dev.jkbuild.model.PluginDeclaration declaration = PluginTableRegistry.isBuiltIn(m.id())
                        ? null
                        : PluginManifestOps.declarationOf(moduleDir, project, m.id()).orElse(null);
                return Optional.of(new Active(m, config.get(), moduleDir, declaration));
            }
        }
        return Optional.empty();
    }

    /** The active packager's static artifact descriptor, or empty — manifest data, no fork. */
    public static Optional<PluginManifest.Packaging> shape(JkBuild project, Path moduleDir) {
        for (PluginManifest m : PluginTableRegistry.manifestsFor(moduleDir, project.plugins())) {
            if (m.packaging() == null) continue;
            if (project.pluginConfig(m.id()).isPresent()) return Optional.of(m.packaging());
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
            List<String> contributesSources) {}

    /** The registered packager, as declared. */
    public record PackagerDecl(String name, List<String> inputs) {}

    /** One registered plugin verb, as declared. */
    public record VerbDecl(String name, String description) {}

    public record Declarations(List<StepDecl> steps, PackagerDecl packager, List<VerbDecl> verbs) {

        /** Back-compat: no verbs. */
        public Declarations(List<StepDecl> steps, PackagerDecl packager) {
            this(steps, packager, List.of());
        }

        public StepDecl step(String name) {
            for (StepDecl s : steps) if (s.name().equals(name)) return s;
            return null;
        }

        public VerbDecl verb(String name) {
            for (VerbDecl v : verbs) if (v.name().equals(name)) return v;
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
     * happens in the worker ({@code describe}); the answer is a pure function of (jk version,
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
        List<VerbDecl> verbs = new ArrayList<>();
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
                            Ndjson.strArray(line, "contributesSources")));
                case "packager" ->
                    packager = new PackagerDecl(Ndjson.str(line, "name"), Ndjson.strArray(line, "inputs"));
                case "verb" ->
                    verbs.add(new VerbDecl(Ndjson.str(line, "name"), Ndjson.str(line, "description")));
                default -> {
                    // labels etc. — irrelevant to declarations
                }
            }
        }
        return new Declarations(steps, packager, verbs);
    }

    private static String describeKey(Active active, JkBuild project) {
        StringBuilder b = new StringBuilder();
        b.append(dev.jkbuild.util.JkVersion.VERSION)
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
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(config.values()).entrySet()) {
            b.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        return Hashing.sha256Hex(b.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    // ---- execution -----------------------------------------------------------------------------

    /** The resolved packager-dependency artifacts, fetched into the CAS by coordinate. */
    public static Map<String, Path> fetchPackagerDependencies(JkBuild project, Path moduleDir, Cas cas)
            throws IOException, InterruptedException {
        Map<String, Path> out = new LinkedHashMap<>();
        List<PluginContributions.PackagerDep> deps = PluginContributions.packagerDependencies(project, moduleDir);
        if (deps.isEmpty()) return out;
        dev.jkbuild.repo.RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
        for (PluginContributions.PackagerDep dep : deps) {
            out.put(dep.artifact(), fetchArtifact(repos, dep.module(), dep.version()));
        }
        return out;
    }

    /** Fetch one {@code module:version} jar into the CAS and return its path. */
    private static Path fetchArtifact(dev.jkbuild.repo.RepoGroup repos, String module, String version)
            throws IOException, InterruptedException {
        int colon = module.indexOf(':');
        dev.jkbuild.model.Coordinate coord =
                dev.jkbuild.model.Coordinate.of(module.substring(0, colon), module.substring(colon + 1), version);
        return repos.tryFetchArtifact(coord)
                .orElseThrow(() -> new IOException("cannot fetch " + coord
                        + " — the version a plugin's packager-dependency names must exist in a declared repo"))
                .fetched()
                .cachePath();
    }

    /**
     * The production classpath a step sees ({@code In.runtimeClasspath()}): lockfile RUNTIME jars
     * + workspace sibling jars (and their transitive RUNTIME deps). The module's own classes dir
     * is NOT included — the worker adds {@code exec.classesDir()} itself.
     */
    public static List<Path> productionClasspath(Path projectDir, Path cache, Path lockFile, JkBuild project)
            throws IOException {
        List<Path> classpath = new ArrayList<>();
        if (Files.exists(lockFile)) {
            var resolver = new dev.jkbuild.compile.ClasspathResolver(new Cas(cache));
            classpath.addAll(resolver.classpathFor(
                    dev.jkbuild.lock.LockfileReader.read(lockFile), dev.jkbuild.compile.ClasspathResolver.RUNTIME));
        }
        try {
            var siblings = dev.jkbuild.config.WorkspaceClasspath.resolve(
                    projectDir, project, java.util.Set.of(dev.jkbuild.model.Scope.EXPORT, dev.jkbuild.model.Scope.MAIN));
            for (Path jar : siblings.jars()) {
                if (!classpath.contains(jar)) classpath.add(jar);
            }
            for (Path sibLock : siblings.siblingLockfiles()) {
                try {
                    var sib = dev.jkbuild.lock.LockfileReader.read(sibLock);
                    for (Path pth : new dev.jkbuild.compile.ClasspathResolver(new Cas(cache))
                            .classpathFor(sib, dev.jkbuild.compile.ClasspathResolver.RUNTIME)) {
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
            lines.add("{\"t\":\"entry\",\"file\":" + Ndjson.quote(fileName) + ",\"path\":"
                    + Ndjson.quote(jar.toAbsolutePath().toString()) + ",\"snapshot\":" + snapshot + "}");
            return this;
        }

        public SpecWriter stepOutput(String name, Path dir) {
            lines.add("{\"t\":\"step-output\",\"name\":" + Ndjson.quote(name) + ",\"dir\":"
                    + Ndjson.quote(dir.toAbsolutePath().toString()) + "}");
            return this;
        }

        public SpecWriter verbArgs(List<String> args) {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) arr.append(',');
                arr.append(Ndjson.quote(args.get(i)));
            }
            arr.append(']');
            lines.add("{\"t\":\"verb-args\",\"values\":" + arr + "}");
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
     * The jar a plugin's code hooks fork: first-party plugins name a registered {@link WorkerJar};
     * a third-party plugin IS its worker — the [plugins]-declared, lock-pinned, SHA-verified jar
     * from the CAS — and it forks only once its coordinate is trusted (plugin-refactor Posture A:
     * the engine refuses untrusted third-party code with the {@code jk trust plugin} remediation).
     */
    static Path workerJarFor(Active active, Path cache) throws IOException {
        if (PluginTableRegistry.isBuiltIn(active.manifest().id())) {
            WorkerJar workerJar = WorkerJar.byArtifactId(active.manifest().code().worker())
                    .orElseThrow(() -> new IllegalStateException(
                            "plugin " + active.manifest().id() + " names unregistered worker "
                                    + active.manifest().code().worker()));
            return workerJar.locate(new Cas(cache));
        }
        dev.jkbuild.model.PluginDeclaration declaration = active.declaration();
        if (declaration == null) {
            throw new IOException("plugin " + active.manifest().id()
                    + " has no matching [plugins] declaration — declare it (or run `jk sync`)");
        }
        // The trust file lives in the machine's state dir; the sysprop is the test seam,
        // exactly like the jk.<worker>.jar properties the worker registry uses.
        String stateOverride = System.getProperty("jk.trust.state.dir");
        Path stateDir = stateOverride != null ? Path.of(stateOverride) : dev.jkbuild.util.JkDirs.state();
        dev.jkbuild.tool.TrustedPlugins trust;
        try {
            trust = dev.jkbuild.tool.TrustedPlugins.load(stateDir);
        } catch (IOException e) {
            trust = null;
        }
        if (trust == null || !trust.isTrusted(declaration.coordinate())) {
            throw new IOException("plugin " + declaration.coordinateWithVersion()
                    + " is not trusted to run build code on this machine.\n"
                    + "Trust it first: jk trust plugin " + declaration.coordinate());
        }
        return PluginManifestOps.jarFor(active.moduleDir(), declaration, cache)
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
     * Fork the plugin's worker on the spec and collect its protocol lines. Throws with the
     * worker's own error message when it reports one (or exits non-zero without reporting).
     */
    public static List<String> runWorker(Active active, Path cache, Path spec, java.util.function.Consumer<String> onLabel)
            throws IOException, InterruptedException {
        Path jar = workerJarFor(active, cache);
        List<String> collected = new ArrayList<>();
        String[] error = new String[1];
        WorkerClient client = new WorkerClient(active.manifest().code().protocolPrefix())
                .on("label", line -> {
                    if (onLabel != null) onLabel.accept(Ndjson.str(line, "text"));
                })
                .on("error", line -> error[0] = Ndjson.str(line, "message"))
                .onOther(collected::add);
        int exit = client.run(WorkerCommands.javaCommand(jar, spec));
        if (error[0] != null) {
            throw new IOException(error[0]);
        }
        if (exit != 0) {
            throw new IOException("plugin worker " + active.manifest().id() + " failed (exit " + exit + ")");
        }
        return collected;
    }
}
