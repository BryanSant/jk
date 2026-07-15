// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.protocol;

import build.jumpkick.plugin.PluginConfig;
import build.jumpkick.plugin.build.PackageIo;
import build.jumpkick.plugin.build.ProjectFacts;
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
 * The parsed engine→plugin spec: one NDJSON reader for every plugin, replacing the per-plugin
 * KEY-value/tab parsers. It decodes the {@link PluginProtocol} spec vocabulary into typed
 * accessors; each op reads only the fields it declared. Unknown line types and config kinds are
 * ignored (forward-compat).
 */
public final class PluginSpec {

    private String op = "";
    private String name; // step/command name from the op line
    private String pluginId = "";
    private final Map<String, Object> config = new LinkedHashMap<>();
    private ProjectFacts project;
    private final Map<String, String> manifest = new LinkedHashMap<>();
    private Path classesDir, sourceOutput, moduleDir, scratch, workdir, snapshotDir;
    private Path javaHome, artifactPath;
    private final List<Path> compileClasspath = new ArrayList<>();
    private final List<Path> processorClasspath = new ArrayList<>();
    private final List<Path> friendPaths = new ArrayList<>();
    private final List<Path> runtimeClasspath = new ArrayList<>();
    private final List<PackageIo.RuntimeEntry> entries = new ArrayList<>();
    private final List<Path> sources = new ArrayList<>();
    private final List<String> args = new ArrayList<>();
    private final List<CompilerPlugin> compilerPlugins = new ArrayList<>();
    private final Map<String, Path> stepOutputs = new LinkedHashMap<>();
    private final Map<String, Path> extras = new LinkedHashMap<>();
    private final Map<String, String> secrets = new LinkedHashMap<>();
    private final List<String> commandArgs = new ArrayList<>();

    private PluginSpec() {}

    /** A typed Kotlin compiler-plugin entry: coordinate id, jar path, and its options. */
    public record CompilerPlugin(String id, Path jar, List<String> options) {}

    public static PluginSpec read(Path file) throws IOException {
        PluginSpec s = new PluginSpec();
        String group = "", pname = "", version = "", mainClass = null;
        int javaRelease = 0;
        boolean nativeDeclared = false, kotlin = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            switch (String.valueOf(Ndjson.str(line, PluginProtocol.T))) {
                case PluginProtocol.OP -> {
                    s.op = String.valueOf(Ndjson.str(line, PluginProtocol.OP_NAME));
                    s.name = Ndjson.str(line, PluginProtocol.NAME);
                    s.pluginId = String.valueOf(Ndjson.str(line, PluginProtocol.PLUGIN));
                }
                case PluginProtocol.CONFIG -> {
                    String key = Ndjson.str(line, PluginProtocol.KEY);
                    switch (String.valueOf(Ndjson.str(line, PluginProtocol.CONFIG_KIND))) {
                        case PluginProtocol.KIND_STRING -> s.config.put(key, Ndjson.str(line, PluginProtocol.VALUE));
                        case PluginProtocol.KIND_BOOL -> s.config.put(key, Ndjson.bool(line, PluginProtocol.VALUE, false));
                        case PluginProtocol.KIND_INT -> s.config.put(key, Ndjson.longValue(line, PluginProtocol.VALUE, 0));
                        case PluginProtocol.KIND_LIST -> s.config.put(key, Ndjson.strArray(line, PluginProtocol.VALUES));
                        default -> {
                            // unknown kind — forward-compat
                        }
                    }
                }
                case PluginProtocol.PROJECT -> {
                    group = String.valueOf(Ndjson.str(line, "group"));
                    pname = String.valueOf(Ndjson.str(line, "name"));
                    version = String.valueOf(Ndjson.str(line, "version"));
                    javaRelease = Ndjson.intValue(line, "javaRelease", 0);
                    mainClass = Ndjson.str(line, "mainClass");
                    nativeDeclared = Ndjson.bool(line, "nativeDeclared", false);
                    kotlin = Ndjson.bool(line, "kotlin", false);
                }
                case PluginProtocol.MANIFEST_ATTR ->
                    s.manifest.put(Ndjson.str(line, PluginProtocol.KEY), Ndjson.str(line, PluginProtocol.VALUE));
                case PluginProtocol.LAYOUT -> {
                    s.classesDir = path(Ndjson.str(line, "classesDir"));
                    s.sourceOutput = path(Ndjson.str(line, "sourceOutput"));
                    s.moduleDir = path(Ndjson.str(line, "moduleDir"));
                    s.scratch = path(Ndjson.str(line, "scratch"));
                    s.workdir = path(Ndjson.str(line, "workdir"));
                    s.snapshotDir = path(Ndjson.str(line, "snapshotDir"));
                }
                case PluginProtocol.JAVA_HOME -> s.javaHome = path(Ndjson.str(line, PluginProtocol.PATH));
                case PluginProtocol.ARTIFACT -> s.artifactPath = path(Ndjson.str(line, PluginProtocol.PATH));
                case PluginProtocol.CP -> {
                    Path p = path(Ndjson.str(line, PluginProtocol.PATH));
                    switch (String.valueOf(Ndjson.str(line, PluginProtocol.ROLE))) {
                        case PluginProtocol.ROLE_PROCESSOR -> s.processorClasspath.add(p);
                        case PluginProtocol.ROLE_FRIEND -> s.friendPaths.add(p);
                        case PluginProtocol.ROLE_RUNTIME -> s.runtimeClasspath.add(p);
                        default -> s.compileClasspath.add(p); // compile is the default role
                    }
                }
                case PluginProtocol.ENTRY -> {
                    String jar = Ndjson.str(line, PluginProtocol.PATH);
                    String container = Ndjson.str(line, PluginProtocol.CONTAINER);
                    s.entries.add(new PackageIo.RuntimeEntry(
                            String.valueOf(Ndjson.str(line, PluginProtocol.FILE_NAME)),
                            jar == null ? null : Path.of(jar),
                            Ndjson.bool(line, PluginProtocol.SNAPSHOT, false),
                            container == null ? null : Path.of(container)));
                }
                case PluginProtocol.SOURCE -> s.sources.add(path(Ndjson.str(line, PluginProtocol.PATH)));
                case PluginProtocol.ARG -> s.args.add(Ndjson.str(line, PluginProtocol.VALUE));
                case PluginProtocol.COMPILER_PLUGIN ->
                    s.compilerPlugins.add(new CompilerPlugin(
                            Ndjson.str(line, "id"),
                            path(Ndjson.str(line, PluginProtocol.PATH)),
                            Ndjson.strArray(line, "options")));
                case PluginProtocol.STEP_OUTPUT ->
                    s.stepOutputs.put(
                            String.valueOf(Ndjson.str(line, PluginProtocol.NAME)),
                            path(Ndjson.str(line, PluginProtocol.DIR)));
                case PluginProtocol.EXTRA ->
                    s.extras.put(
                            String.valueOf(Ndjson.str(line, PluginProtocol.NAME)),
                            path(Ndjson.str(line, PluginProtocol.PATH)));
                case PluginProtocol.SECRET ->
                    s.secrets.put(
                            String.valueOf(Ndjson.str(line, PluginProtocol.KEY)),
                            String.valueOf(Ndjson.str(line, PluginProtocol.VALUE)));
                case PluginProtocol.COMMAND_ARGS -> s.commandArgs.addAll(Ndjson.strArray(line, PluginProtocol.VALUES));
                default -> {
                    // unknown line — forward-compat
                }
            }
        }
        s.project = new ProjectFacts(group, pname, version, javaRelease, mainClass, nativeDeclared, kotlin, s.manifest);
        return s;
    }

    private static Path path(String s) {
        return s == null ? null : Path.of(s);
    }

    public String op() {
        return op;
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public String pluginId() {
        return pluginId;
    }

    public PluginConfig config() {
        return new PluginConfig(pluginId, config);
    }

    public ProjectFacts project() {
        return project;
    }

    public Path classesDir() {
        return classesDir;
    }

    public Path sourceOutput() {
        return sourceOutput;
    }

    public Path moduleDir() {
        return moduleDir;
    }

    public Path scratch() {
        return scratch;
    }

    public Path workdir() {
        return workdir;
    }

    public Path snapshotDir() {
        return snapshotDir;
    }

    public Path javaHome() {
        return javaHome;
    }

    public Path artifactPath() {
        return artifactPath;
    }

    public List<Path> compileClasspath() {
        return compileClasspath;
    }

    public List<Path> processorClasspath() {
        return processorClasspath;
    }

    public List<Path> friendPaths() {
        return friendPaths;
    }

    public List<Path> runtimeClasspath() {
        return runtimeClasspath;
    }

    public List<PackageIo.RuntimeEntry> entries() {
        return entries;
    }

    public List<Path> sources() {
        return sources;
    }

    public List<String> args() {
        return args;
    }

    public List<CompilerPlugin> compilerPlugins() {
        return compilerPlugins;
    }

    public Optional<Path> stepOutput(String step) {
        return Optional.ofNullable(stepOutputs.get(step));
    }

    public Optional<Path> extra(String name) {
        return Optional.ofNullable(extras.get(name));
    }

    public Optional<String> secret(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    public List<String> commandArgs() {
        return commandArgs;
    }
}
