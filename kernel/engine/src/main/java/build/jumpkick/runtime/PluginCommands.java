// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.cache.Cas;
import build.jumpkick.config.JkBuildParser;
import build.jumpkick.engine.protocol.PluginCommandReport;
import build.jumpkick.layout.BuildLayout;
import build.jumpkick.model.JkBuild;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.worker.WorkerClient;
import build.jumpkick.worker.WorkerJar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine-hosted plugin commands (build-plugins plan row 11): when the CLI sees a command it doesn't
 * own, the project's active code plugin may — the command runs in the plugin's worker JVM over the
 * existing spec/NDJSON protocol, its {@code command-out} lines stream back as the command's output,
 * and its exit code is the command's. {@code found=false} (no jk.toml / no plugin / no such
 * command) sends the client back to its normal unknown-command help.
 */
public final class PluginCommands {

    private PluginCommands() {}

    public static PluginCommandReport run(Path dir, Path cache, String command, List<String> args) {
        return run(dir, cache, command, args, "", java.util.Map.of());
    }

    /**
     * As above with the request's variant selection — a deploy command after {@code jk run --release}
     * must resolve the release packaging (the AAB) and its config, not the debug default's.
     */
    public static PluginCommandReport run(
            Path dir, Path cache, String command, List<String> args,
            String variant, java.util.Map<String, String> clientEnv) {
        try {
            Path buildFile = dir.resolve("jk.toml");
            if (!Files.isRegularFile(buildFile)) return PluginCommandReport.notFound();
            JkBuild project = JkBuildParser.parse(buildFile);
            if (!project.plugins().isEmpty() && PluginDescriptorOps.ensureMaterialized(dir, cache)) {
                project = JkBuildParser.reparse(buildFile);
            }
            project = build.jumpkick.plugin.manifest.VariantApply.applyLenient(
                            project, dir, build.jumpkick.model.Variants.Selection.parse(variant), clientEnv)
                    .build();
            var active = PluginBuild.activeCodePlugin(project, dir).orElse(null);
            if (active == null) return PluginCommandReport.notFound();

            BuildLayout layout = BuildLayout.of(dir, project);
            PluginBuild.Declarations decls =
                    PluginBuild.declarations(active, project, dir, cache, layout.moduleTargetDir());
            if (decls.command(command) == null) return PluginCommandReport.notFound();

            Path scratch = layout.moduleTargetDir().resolve("plugin").resolve("command-" + command);
            Files.createDirectories(scratch);
            PluginBuild.SpecWriter specWriter = new PluginBuild.SpecWriter()
                    .op("command", command, active.manifest().id())
                    .config(active.config())
                    .project(project, project.mainClass())
                    .layout(layout.classesDir(), dir, scratch)
                    .artifact(PluginBuild.mainArtifactPath(layout, active))
                    .commandArgs(args);
            // Commands get the same declared tool artifacts steps do (adb from an SDK component) —
            // best-effort: `jk android licenses` must run BEFORE licenses gate provisioning, so
            // an unprovisionable tool is absent and only a command that needs it complains.
            for (var tool : PluginBuild.fetchStepDependencies(
                    project, dir, new build.jumpkick.cache.Cas(cache),
                    PluginBuild.sdkPins(dir.resolve("jk.lock")), true)
                    .entrySet()) {
                specWriter.extra(tool.getKey(), tool.getValue());
            }
            Path spec = specWriter.write();
            try {
                Path jar = PluginBuild.workerJarFor(active, cache);
                List<String> output = new ArrayList<>();
                String[] error = new String[1];
                WorkerClient client = new WorkerClient(active.manifest().code().protocolPrefix())
                        .on("command-out", line -> output.add(Ndjson.str(line, "line")))
                        .on("error", line -> error[0] = Ndjson.str(line, "message"))
                        .onOther(line -> {
                            // labels/done — not part of the command's user-facing output
                        });
                int exit = client.run(WorkerCommands.javaCommand(jar, spec));
                if (error[0] != null) return PluginCommandReport.error(error[0]);
                return new PluginCommandReport(null, true, exit, output);
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            return PluginCommandReport.error(String.valueOf(e.getMessage()));
        }
    }
}
