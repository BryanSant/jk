// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import build.jumpkick.plugin.PluginConfig;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.plugin.protocol.ProtocolWriter;
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
 * The worker-side driver every build plugin delegates its {@code Plugin.run} to: reads the
 * engine's spec file, replays {@link BuildPlugin#register} against a recording context, then
 * answers the requested op —
 *
 * <ul>
 *   <li>{@code describe} — emit the registered step/packager declarations (the engine caches
 *       these per config+facts, so fully-cached builds never fork the worker);
 *   <li>{@code run-step <name>} — execute one step body against the spec's resolved inputs;
 *   <li>{@code package} — execute the packager body.
 * </ul>
 *
 * <p>Spec lines are flat NDJSON objects ({@code {"t":"config",…}}, {@code {"t":"cp",…}}, …);
 * replies ride the plugin's {@link ProtocolWriter} ({@code {"t":"label"}}, {@code {"t":"error"}},
 * {@code {"t":"step"}}/{@code {"t":"packager"}} for describe, and a terminal {@code {"t":"done"}}).
 */
public final class BuildPluginHarness {

    private BuildPluginHarness() {}

    /**
     * The worker entry point every plugin's {@code Plugin.run} delegates to. A plugin either
     * implements {@link BuildPlugin} directly (the low-level escape hatch — Android) or one or more
     * capability interfaces ({@link BuildExtension}/{@link PackageExtension}/…); either way this
     * replays its registration and answers the requested op.
     */
    public static int run(build.jumpkick.plugin.Plugin plugin, List<String> args, ProtocolWriter out)
            throws Exception {
        return run(asBuildPlugin(plugin), args, out);
    }

    /**
     * Adapt a capability plugin to the low-level {@link BuildPlugin} substrate: a {@code register()}
     * that drives each implemented capability against a phase-scoped context, recording the same
     * {@link StepSpec}/{@link PackagerSpec} declarations. A plugin that implements {@link BuildPlugin}
     * directly is returned unchanged.
     */
    static BuildPlugin asBuildPlugin(build.jumpkick.plugin.Plugin plugin) {
        if (plugin instanceof BuildPlugin bp) {
            return bp;
        }
        return ctx -> {
            String id = plugin.id();
            try {
                if (plugin instanceof ResolveExtension e) {
                    e.resolve(new DefaultStepContext(ctx, id, Phase.RESOLVE, Phase.COMPILE));
                }
                if (plugin instanceof BuildExtension e) {
                    e.build(new DefaultStepContext(ctx, id, Phase.COMPILE, Phase.PACKAGE));
                }
                if (plugin instanceof TestExtension e) {
                    e.test(new DefaultStepContext(ctx, id, Phase.COMPILE, Phase.TEST));
                }
                if (plugin instanceof PackageExtension e) {
                    e.pack(new DefaultPackageContext(ctx));
                }
            } catch (RuntimeException | Error ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            if (plugin instanceof RunExtension || plugin instanceof ImageExtension
                    || plugin instanceof PublishExtension) {
                throw new IllegalStateException("terminal-goal capability (run/image/publish) on plugin `" + id
                        + "` is not wired yet (extension-remodel Stream 6)");
            }
        };
    }

    // Package-private: the substrate path. Real plugins reach it through run(Plugin, …); same-package
    // tests may drive a bare BuildPlugin fixture directly.
    static int run(BuildPlugin plugin, List<String> args, ProtocolWriter out) throws Exception {
        if (args.isEmpty()) {
            System.err.println("build-plugin worker: expected spec file path as first argument");
            return 64;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("build-plugin worker: spec file not found: " + specFile);
            return 66;
        }
        Spec spec = Spec.read(specFile);

        Recorder recorder = new Recorder(spec.config(), spec.project());
        plugin.register(recorder);

        switch (spec.op()) {
            case "describe" -> describe(recorder, out);
            case "run-step" -> {
                StepSpec step = recorder.step(spec.stepName());
                if (step == null || step.body() == null) {
                    out.emit("{\"t\":\"error\",\"code\":\"unknown-step\",\"message\":"
                            + Ndjson.quote("no registered step named " + spec.stepName()) + "}");
                    return 65;
                }
                try {
                    step.body().run(new SpecStepExec(spec, out));
                } catch (Exception e) {
                    out.emit("{\"t\":\"error\",\"code\":\"step-failed\",\"message\":"
                            + Ndjson.quote(String.valueOf(e.getMessage())) + "}");
                    return 1;
                }
            }
            case "command" -> {
                PluginCommandSpec command = recorder.command(spec.stepName());
                if (command == null || command.body() == null) {
                    out.emit("{\"t\":\"error\",\"code\":\"unknown-command\",\"message\":"
                            + Ndjson.quote("no registered command named " + spec.stepName()) + "}");
                    return 65;
                }
                try {
                    int exit = command.body().run(new SpecCommandExec(spec, out));
                    out.emit("{\"t\":\"done\"}");
                    return exit;
                } catch (Exception e) {
                    out.emit("{\"t\":\"error\",\"code\":\"command-failed\",\"message\":"
                            + Ndjson.quote(String.valueOf(e.getMessage())) + "}");
                    return 1;
                }
            }
            case "package" -> {
                PackagerSpec packager = recorder.packager();
                if (packager == null || packager.body() == null) {
                    out.emit("{\"t\":\"error\",\"code\":\"no-packager\",\"message\":"
                            + Ndjson.quote("plugin registered no packager") + "}");
                    return 65;
                }
                try {
                    packager.body().produce(new SpecPackageIo(spec, out));
                } catch (Exception e) {
                    out.emit("{\"t\":\"error\",\"code\":\"package-failed\",\"message\":"
                            + Ndjson.quote(String.valueOf(e.getMessage())) + "}");
                    return 1;
                }
            }
            default -> {
                out.emit("{\"t\":\"error\",\"code\":\"unknown-op\",\"message\":" + Ndjson.quote(spec.op()) + "}");
                return 64;
            }
        }
        out.emit("{\"t\":\"done\"}");
        return 0;
    }

    private static void describe(Recorder recorder, ProtocolWriter out) {
        for (StepSpec step : recorder.steps()) {
            StringBuilder b = new StringBuilder("{\"t\":\"step\",\"name\":")
                    .append(Ndjson.quote(step.name()))
                    .append(",\"after\":")
                    .append(Ndjson.quote(step.afterPhase().wireName()))
                    .append(",\"before\":")
                    .append(Ndjson.quote(step.beforePhase().wireName()))
                    .append(",\"inputs\":")
                    .append(quoteArray(step.declaredInputs().stream().map(In::wireName).toList()))
                    .append(",\"outputs\":")
                    .append(quoteArray(step.declaredOutputs()))
                    .append(",\"contributesClasses\":")
                    .append(quoteArray(step.classesContributions()))
                    .append(",\"contributesResources\":")
                    .append(quoteArray(step.resourcesContributions()))
                    .append(",\"contributesSources\":")
                    .append(quoteArray(step.sourcesContributions()))
                    .append(",\"contributesTestClasspath\":")
                    .append(quoteArray(step.testClasspathContributions()))
                    .append(",\"transformsClasses\":")
                    .append(Ndjson.quote(step.classesTransform() == null ? "" : step.classesTransform()))
                    .append('}');
            out.emit(b.toString());
        }
        PackagerSpec packager = recorder.packager();
        if (packager != null) {
            out.emit("{\"t\":\"packager\",\"name\":" + Ndjson.quote(packager.name()) + ",\"inputs\":"
                    + quoteArray(packager.declaredInputs().stream().map(In::wireName).toList()) + "}");
        }
        for (PluginCommandSpec command : recorder.commands()) {
            out.emit("{\"t\":\"command\",\"name\":" + Ndjson.quote(command.name()) + ",\"description\":"
                    + Ndjson.quote(command.description()) + "}");
        }
    }

    private static String quoteArray(List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(Ndjson.quote(values.get(i)));
        }
        return b.append(']').toString();
    }

    // ---- registration recording ----------------------------------------------------------

    private static final class Recorder implements BuildPluginContext {
        private final PluginConfig config;
        private final ProjectFacts project;
        private final List<StepSpec> steps = new ArrayList<>();
        private final List<PluginCommandSpec> commands = new ArrayList<>();
        private PackagerSpec packager;

        Recorder(PluginConfig config, ProjectFacts project) {
            this.config = config;
            this.project = project;
        }

        @Override
        public PluginConfig config() {
            return config;
        }

        @Override
        public ProjectFacts project() {
            return project;
        }

        @Override
        public void step(StepSpec spec) {
            steps.add(spec);
        }

        @Override
        public void packaging(PackagerSpec spec) {
            if (packager != null) {
                throw new IllegalStateException("one packager may replace the main artifact — a second ("
                        + spec.name() + ") conflicts with " + packager.name());
            }
            packager = spec;
        }

        List<StepSpec> steps() {
            return steps;
        }

        StepSpec step(String name) {
            for (StepSpec s : steps) if (s.name().equals(name)) return s;
            return null;
        }

        PackagerSpec packager() {
            return packager;
        }

        @Override
        public void command(PluginCommandSpec spec) {
            commands.add(spec);
        }

        List<PluginCommandSpec> commands() {
            return commands;
        }

        PluginCommandSpec command(String name) {
            for (PluginCommandSpec v : commands) if (v.name().equals(name)) return v;
            return null;
        }
    }

    // ---- the engine's spec, decoded --------------------------------------------------------

    record Spec(
            String op,
            String stepName,
            PluginConfig config,
            ProjectFacts project,
            Path classesDir,
            Path moduleDir,
            Path scratch,
            Path javaHome,
            Path artifactPath,
            List<Path> classpath,
            List<PackageIo.RuntimeEntry> entries,
            Map<String, Path> stepOutputs,
            Map<String, Path> extras,
            List<String> commandArgs,
            Map<String, String> secrets) {

        static Spec read(Path file) throws IOException {
            String op = "";
            String stepName = null;
            String pluginId = "";
            Map<String, Object> configValues = new LinkedHashMap<>();
            String group = "";
            String name = "";
            String version = "";
            int javaRelease = 0;
            String mainClass = null;
            boolean nativeDeclared = false;
            boolean kotlin = false;
            Map<String, String> manifest = new LinkedHashMap<>();
            Path classesDir = null;
            Path moduleDir = null;
            Path scratch = null;
            Path javaHome = null;
            Path artifactPath = null;
            List<Path> classpath = new ArrayList<>();
            List<PackageIo.RuntimeEntry> entries = new ArrayList<>();
            Map<String, Path> stepOutputs = new LinkedHashMap<>();
            Map<String, Path> extras = new LinkedHashMap<>();
            List<String> commandArgs = new ArrayList<>();
            Map<String, String> secrets = new LinkedHashMap<>();

            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                switch (String.valueOf(Ndjson.str(line, "t"))) {
                    case "op" -> {
                        op = String.valueOf(Ndjson.str(line, "op"));
                        stepName = Ndjson.str(line, "step");
                        pluginId = String.valueOf(Ndjson.str(line, "plugin"));
                    }
                    case "config" -> {
                        String key = Ndjson.str(line, "key");
                        switch (String.valueOf(Ndjson.str(line, "kind"))) {
                            case "string" -> configValues.put(key, Ndjson.str(line, "value"));
                            case "bool" -> configValues.put(key, Ndjson.bool(line, "value", false));
                            case "int" -> configValues.put(key, Ndjson.longValue(line, "value", 0));
                            case "list" -> configValues.put(key, Ndjson.strArray(line, "values"));
                            default -> {
                                // unknown kind — treat as absent (forward compatibility)
                            }
                        }
                    }
                    case "project" -> {
                        group = String.valueOf(Ndjson.str(line, "group"));
                        name = String.valueOf(Ndjson.str(line, "name"));
                        version = String.valueOf(Ndjson.str(line, "version"));
                        javaRelease = Ndjson.intValue(line, "javaRelease", 0);
                        mainClass = Ndjson.str(line, "mainClass");
                        nativeDeclared = Ndjson.bool(line, "nativeDeclared", false);
                        kotlin = Ndjson.bool(line, "kotlin", false);
                    }
                    case "manifest-attr" -> manifest.put(Ndjson.str(line, "key"), Ndjson.str(line, "value"));
                    case "layout" -> {
                        String classes = Ndjson.str(line, "classesDir");
                        if (classes != null) classesDir = Path.of(classes);
                        String module = Ndjson.str(line, "moduleDir");
                        if (module != null) moduleDir = Path.of(module);
                        String scratchDir = Ndjson.str(line, "scratch");
                        if (scratchDir != null) scratch = Path.of(scratchDir);
                    }
                    case "java-home" -> javaHome = Path.of(String.valueOf(Ndjson.str(line, "path")));
                    case "artifact" -> artifactPath = Path.of(String.valueOf(Ndjson.str(line, "path")));
                    case "cp" -> classpath.add(Path.of(String.valueOf(Ndjson.str(line, "path"))));
                    case "entry" -> {
                        String jarPath = Ndjson.str(line, "path");
                        String container = Ndjson.str(line, "container");
                        entries.add(new PackageIo.RuntimeEntry(
                                String.valueOf(Ndjson.str(line, "file")),
                                jarPath == null ? null : Path.of(jarPath),
                                Ndjson.bool(line, "snapshot", false),
                                container == null ? null : Path.of(container)));
                    }
                    case "step-output" ->
                        stepOutputs.put(
                                String.valueOf(Ndjson.str(line, "name")),
                                Path.of(String.valueOf(Ndjson.str(line, "dir"))));
                    case "command-args" -> commandArgs.addAll(Ndjson.strArray(line, "values"));
                    case "extra" ->
                        extras.put(
                                String.valueOf(Ndjson.str(line, "name")),
                                Path.of(String.valueOf(Ndjson.str(line, "path"))));
                    case "secret" ->
                        secrets.put(
                                String.valueOf(Ndjson.str(line, "key")),
                                String.valueOf(Ndjson.str(line, "value")));
                    default -> {
                        // unknown line — forward compatibility
                    }
                }
            }
            PluginConfig config = new PluginConfig(pluginId, configValues);
            ProjectFacts facts =
                    new ProjectFacts(group, name, version, javaRelease, mainClass, nativeDeclared, kotlin, manifest);
            return new Spec(
                    op, stepName, config, facts, classesDir, moduleDir, scratch, javaHome, artifactPath, classpath,
                    entries, stepOutputs, extras, commandArgs, secrets);
        }
    }

    // ---- exec facades over the spec ---------------------------------------------------------

    private record SpecStepExec(Spec spec, ProtocolWriter out) implements StepExec {
        @Override
        public Path classesDir() {
            return spec.classesDir();
        }

        @Override
        public List<Path> runtimeClasspath() {
            return spec.classpath();
        }

        @Override
        public List<PackageIo.RuntimeEntry> runtimeEntries() {
            return spec.entries();
        }

        @Override
        public PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Path moduleDir() {
            return spec.moduleDir();
        }

        @Override
        public Path scratch() {
            return spec.scratch();
        }

        @Override
        public Path javaHome() {
            return spec.javaHome();
        }

        @Override
        public Optional<Path> extra(String name) {
            return Optional.ofNullable(spec.extras().get(name));
        }

        @Override
        public Optional<Path> stepOutput(String step) {
            return Optional.ofNullable(spec.stepOutputs().get(step));
        }

        @Override
        public void label(String text) {
            out.emit("{\"t\":\"label\",\"text\":" + Ndjson.quote(text) + "}");
        }
    }

    private record SpecCommandExec(Spec spec, ProtocolWriter out) implements PluginCommandExec {
        @Override
        public List<String> args() {
            return spec.commandArgs();
        }

        @Override
        public build.jumpkick.plugin.PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Path moduleDir() {
            return spec.moduleDir();
        }

        @Override
        public java.util.Optional<Path> extra(String name) {
            return java.util.Optional.ofNullable(spec.extras().get(name));
        }

        @Override
        public java.util.Optional<Path> mainArtifact() {
            return java.util.Optional.ofNullable(spec.artifactPath())
                    .filter(java.nio.file.Files::isRegularFile);
        }

        @Override
        public void out(String line) {
            out.emit("{\"t\":\"command-out\",\"line\":" + Ndjson.quote(line) + "}");
        }

        @Override
        public void label(String text) {
            out.emit("{\"t\":\"label\",\"text\":" + Ndjson.quote(text) + "}");
        }
    }

    private record SpecPackageIo(Spec spec, ProtocolWriter out) implements PackageIo {
        @Override
        public Path classesDir() {
            return spec.classesDir();
        }

        @Override
        public Path moduleDir() {
            return spec.moduleDir();
        }

        @Override
        public List<RuntimeEntry> runtimeEntries() {
            return spec.entries();
        }

        @Override
        public PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Optional<Path> stepOutput(String step) {
            return Optional.ofNullable(spec.stepOutputs().get(step));
        }

        @Override
        public Optional<Path> extra(String name) {
            return Optional.ofNullable(spec.extras().get(name));
        }

        @Override
        public Path artifactPath() {
            return spec.artifactPath();
        }

        @Override
        public Optional<String> secret(String key) {
            return Optional.ofNullable(spec.secrets().get(key));
        }

        @Override
        public Path javaHome() {
            return spec.javaHome();
        }

        @Override
        public void label(String text) {
            out.emit("{\"t\":\"label\",\"text\":" + Ndjson.quote(text) + "}");
        }
    }
}
