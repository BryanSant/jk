// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.gradle.GradleImporter;
import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.mvn.MavenResolver;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code jk-compat-bridge} plugin (op {@code command}, name {@code import}/{@code provision_mvn}/
 * {@code provision_gradle}): Maven/Gradle import + tool provisioning, isolated in a forked plugin JVM
 * so the Maven/Gradle machinery never loads in jk's own process. Speaks the unified wire — JSONL
 * config spec, {@code wrote}/{@code result} replies + {@code error} on failure. Exit 0 success, 1
 * operation error, 2 bad arguments, 64 unrecognised source, 73 overwrite-without-force.
 */
public final class CompatBridge implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-compat-bridge", "##JKCMP:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-compat-bridge: expected spec file path");
            return 2;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-compat-bridge: spec file not found: " + specFile);
            return 2;
        }
        PluginSpec spec;
        try {
            spec = PluginSpec.read(specFile);
        } catch (IOException e) {
            System.err.println("jk-compat-bridge: could not read spec: " + e.getMessage());
            return 2;
        }
        String command = spec.name().orElse("");
        PluginConfig config = spec.config();
        return switch (command) {
            case "import" -> runImport(out, config);
            case "provision_mvn" -> runProvision(out, config, false);
            case "provision_gradle" -> runProvision(out, config, true);
            default -> {
                System.err.println("jk-compat-bridge: unknown command: " + command);
                yield 2;
            }
        };
    }

    private static int runImport(ProtocolWriter out, PluginConfig c) {
        Path source = c.stringOpt("source").map(Path::of).orElse(null);
        Path outPath = c.stringOpt("out").map(Path::of).orElse(null);
        Path report = c.stringOpt("report").map(Path::of).orElse(null);
        Path tmpDir = c.stringOpt("tmpDir").map(Path::of).orElse(null);
        Path baseDir = c.stringOpt("baseDir").map(Path::of).orElse(null);
        boolean force = c.bool("force", false);
        if (source == null || outPath == null) {
            System.err.println("jk-compat-bridge: import requires source and out");
            return 2;
        }
        try {
            String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
            JkBuild root;
            Map<String, JkBuild> modules = new LinkedHashMap<>();
            ImportReport importReport;

            if (filename.endsWith("pom.xml")) {
                PomImporter.WorkspaceImportResult result = PomImporter.importWorkspace(source);
                root = result.root();
                modules.putAll(result.modules());
                importReport = result.report();
            } else if (filename.equals("build.gradle") || filename.equals("build.gradle.kts")) {
                GradleImporter.Result result = GradleImporter.importFrom(source);
                root = result.jkBuild();
                importReport = result.report();
            } else {
                System.err.println("jk-compat-bridge: unrecognised source: " + source.getFileName());
                return 64;
            }

            Files.writeString(outPath, JkBuildRenderer.render(root), StandardCharsets.UTF_8);
            out.emit(PluginReply.wrote(outPath.toString()));

            Path effectiveBaseDir = baseDir != null ? baseDir : source.getParent();
            for (Map.Entry<String, JkBuild> e : modules.entrySet()) {
                Path moduleJkBuild = effectiveBaseDir.resolve(e.getKey()).resolve("jk.toml");
                if (Files.exists(moduleJkBuild) && !force) {
                    out.emit(PluginReply.error("overwrite", "would overwrite " + moduleJkBuild + " — pass --force"));
                    return 73;
                }
                Files.writeString(moduleJkBuild, JkBuildRenderer.render(e.getValue()), StandardCharsets.UTF_8);
                out.emit(PluginReply.wrote(moduleJkBuild.toString()));
            }

            Path reportTarget = report;
            if (reportTarget == null && tmpDir != null) {
                var proj = root.project();
                String coord = proj.group() + "-" + proj.name() + "-" + proj.version();
                for (int n = 1; ; n++) {
                    Path candidate = tmpDir.resolve(coord + "-" + n + "-" + source.getFileName() + "-import.md");
                    if (!Files.exists(candidate)) {
                        reportTarget = candidate;
                        break;
                    }
                }
            }
            if (reportTarget != null) {
                Path rDir = reportTarget.getParent();
                if (rDir != null) Files.createDirectories(rDir);
                Files.writeString(reportTarget, importReport.renderMarkdown(source.toString()), StandardCharsets.UTF_8);
                out.emit(PluginReply.wrote(reportTarget.toString()));
            }

            out.emit(PluginReply.result(Map.of("warnings", importReport.issues().size())));
            out.emit(PluginReply.done(0));
            return 0;
        } catch (IOException e) {
            out.emit(PluginReply.error("import", e.getMessage()));
            return 1;
        }
    }

    private static int runProvision(ProtocolWriter out, PluginConfig c, boolean isGradle) {
        Path projectDir = c.stringOpt("projectDir").map(Path::of).orElse(null);
        Path toolsRoot = c.stringOpt("toolsRoot").map(Path::of).orElse(null);
        boolean noDiscover = c.bool("noDiscover", false);
        if (projectDir == null || toolsRoot == null) {
            System.err.println("jk-compat-bridge: provision requires projectDir and toolsRoot");
            return 2;
        }
        try {
            ToolRegistry registry = new ToolRegistry(toolsRoot);
            ToolDistribution dist =
                    isGradle ? new GradleResolver().resolve(projectDir) : new MavenResolver().resolve(projectDir);
            ToolProvisioning.Result result = ToolProvisioning.provision(dist, registry, new Http(), noDiscover);
            InstalledTool tool = result.tool();
            out.emit(PluginReply.result(Map.of(
                    "bin", tool.binary().toString(),
                    "version", dist.version(),
                    "source", result.source().name())));
            out.emit(PluginReply.done(0));
            return 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            out.emit(PluginReply.error("provision", e.getMessage()));
            return 1;
        }
    }
}
