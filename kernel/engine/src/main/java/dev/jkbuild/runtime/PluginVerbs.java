// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.engine.protocol.PluginVerbReport;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.worker.WorkerClient;
import dev.jkbuild.worker.WorkerJar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine-hosted plugin verbs (build-plugins plan row 11): when the CLI sees a verb it doesn't
 * own, the project's active code plugin may — the verb runs in the plugin's worker JVM over the
 * existing spec/NDJSON protocol, its {@code verb-out} lines stream back as the command's output,
 * and its exit code is the command's. {@code found=false} (no jk.toml / no plugin / no such
 * verb) sends the client back to its normal unknown-command help.
 */
public final class PluginVerbs {

    private PluginVerbs() {}

    public static PluginVerbReport run(Path dir, Path cache, String verb, List<String> args) {
        try {
            Path buildFile = dir.resolve("jk.toml");
            if (!Files.isRegularFile(buildFile)) return PluginVerbReport.notFound();
            JkBuild project = JkBuildParser.parse(buildFile);
            if (!project.plugins().isEmpty() && PluginManifestOps.ensureMaterialized(dir, cache)) {
                project = JkBuildParser.reparse(buildFile);
            }
            var active = PluginBuild.activeCodePlugin(project, dir).orElse(null);
            if (active == null) return PluginVerbReport.notFound();

            BuildLayout layout = BuildLayout.of(dir, project);
            PluginBuild.Declarations decls =
                    PluginBuild.declarations(active, project, dir, cache, layout.moduleTargetDir());
            if (decls.verb(verb) == null) return PluginVerbReport.notFound();

            Path scratch = layout.moduleTargetDir().resolve("plugin").resolve("verb-" + verb);
            Files.createDirectories(scratch);
            Path spec = new PluginBuild.SpecWriter()
                    .op("verb", verb, active.manifest().id())
                    .config(active.config())
                    .project(project, project.mainClass())
                    .layout(layout.classesDir(), dir, scratch)
                    .verbArgs(args)
                    .write();
            try {
                Path jar = PluginBuild.workerJarFor(active, cache);
                List<String> output = new ArrayList<>();
                String[] error = new String[1];
                WorkerClient client = new WorkerClient(active.manifest().code().protocolPrefix())
                        .on("verb-out", line -> output.add(Ndjson.str(line, "line")))
                        .on("error", line -> error[0] = Ndjson.str(line, "message"))
                        .onOther(line -> {
                            // labels/done — not part of the verb's user-facing output
                        });
                int exit = client.run(WorkerCommands.javaCommand(jar, spec));
                if (error[0] != null) return PluginVerbReport.error(error[0]);
                return new PluginVerbReport(null, true, exit, output);
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            return PluginVerbReport.error(String.valueOf(e.getMessage()));
        }
    }
}
