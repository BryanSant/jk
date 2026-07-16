// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.plugin.protocol.Ndjson;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shared compat-bridge plugin drivers — {@code jk import}'s conversion pipeline and {@code jk mvn}/
 * {@code jk gradle}'s distribution provisioning — hoisted out of the CLI so the resident engine can
 * host them (Wave 2 of {@code docs/architecture/slim-client.md}) while the commands' test-only
 * in-process paths run the exact same code.
 *
 * <p>Import is a single-step pipeline streaming plain {@code wrote}/{@code note} strings through the
 * {@link NoteObserver}; the plugin's exit code is a <em>result</em> (carried on {@link #EXIT}), not
 * a pipeline failure. Provisioning is a plain one-shot call — no pipeline, no events, just a {@link
 * Provision} outcome — because the passthrough <em>exec</em> of the provisioned tool (which needs
 * the client's inherited stdio/terminal) stays client-side by design.
 */
public final class CompatPipelines {

    private CompatPipelines() {}

    /** Receives each import progress note ({@code kind} = {@code wrote}/{@code note}) as the plugin streams it. */
    public interface NoteObserver {
        void onNote(String kind, String text);
    }

    /** The import plugin's exit code (0 = converted cleanly). */
    public static final PipelineKey<Integer> EXIT = PipelineKey.of("import-exit", Integer.class);

    /** The import plugin's reported issue count (rendered as "Import notes: N issue(s)"). */
    public static final PipelineKey<Integer> WARNINGS = PipelineKey.of("import-warnings", Integer.class);

    /** The import plugin's terminal error text, if any. */
    public static final PipelineKey<String> ERROR = PipelineKey.of("import-error", String.class);

    /** The plugin's passthrough chatter, kept only when it exited non-zero. */
    public static final PipelineKey<String> DIAG = PipelineKey.of("import-diag", String.class);

    /**
     * Build the import pipeline. All paths arrive absolute (the command pre-flighted source detection
     * and overwrite checks); {@code report} may be {@code null}. Locates the plugin jar eagerly, so
     * a missing plugin fails here with side-load instructions rather than mid-pipeline.
     */
    public static Pipeline importPipeline(
            Path source,
            Path out,
            Path baseDir,
            Path tmpDir,
            boolean force,
            Path report,
            Path cache,
            NoteObserver observer) {
        Path workerJar = PluginJar.COMPAT_BRIDGE.locate(new Cas(cache));

        SpecWriter specWriter = new SpecWriter()
                .op(PluginProtocol.OP_COMMAND, "import", "jk-compat-bridge")
                .configString("source", source.toAbsolutePath().toString())
                .configString("out", out.toAbsolutePath().toString())
                .configString("baseDir", baseDir.toAbsolutePath().toString())
                .configString("tmpDir", tmpDir.toAbsolutePath().toString())
                .configBool("force", force);
        if (report != null)
            specWriter.configString("report", report.toAbsolutePath().toString());

        Step convert = Step.builder("import")
                .kind(StepKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("convert " + source.getFileName() + " via compat plugin");
                    Path spec = Files.createTempFile("jk-compat-", ".spec");
                    try {
                        Files.write(spec, specWriter.lines(), StandardCharsets.UTF_8);
                        StringBuilder diag = new StringBuilder();
                        int exit = new PluginClient("##JKCMP:")
                                .on(PluginProtocol.WROTE, json -> observer.onNote("wrote", Ndjson.str(json, "path")))
                                .on(
                                        PluginProtocol.ERROR,
                                        json -> ctx.put(ERROR, Ndjson.str(json, PluginProtocol.MESSAGE)))
                                .on(
                                        PluginProtocol.RESULT,
                                        json -> ctx.put(WARNINGS, Ndjson.intValue(json, "warnings", 0)))
                                .passthrough(ln -> diag.append(ln).append('\n'))
                                .run(PluginLaunch.javaCommand(workerJar, spec));
                        ctx.put(EXIT, exit);
                        if (exit != 0 && diag.length() > 0) {
                            ctx.put(DIAG, diag.toString().trim());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("import worker interrupted", e);
                    } finally {
                        Files.deleteIfExists(spec);
                    }
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("import").addStep(convert).build();
    }

    /** A provisioning call's outcome — the flat fields {@code jk mvn}/{@code jk gradle} render from. */
    public record Provision(String bin, String version, String source, String error, int exit, String diag) {}

    /**
     * Provision a Maven/Gradle distribution via the compat-bridge plugin: link a discovered install
     * or download one, and return its launcher path. Non-interactive by construction — the exec of
     * the provisioned tool is the caller's business.
     */
    public static Provision provision(Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean isGradle)
            throws IOException, InterruptedException {
        Path workerJar = PluginJar.COMPAT_BRIDGE.locate(new Cas(cache));
        Path spec = Files.createTempFile("jk-compat-", ".spec");
        try {
            Files.write(
                    spec,
                    new SpecWriter()
                            .op(
                                    PluginProtocol.OP_COMMAND,
                                    isGradle ? "provision_gradle" : "provision_mvn",
                                    "jk-compat-bridge")
                            .configString(
                                    "projectDir", projectDir.toAbsolutePath().toString())
                            .configString(
                                    "toolsRoot", toolsRoot.toAbsolutePath().toString())
                            .configBool("noDiscover", noDiscover)
                            .lines(),
                    StandardCharsets.UTF_8);

            String[] bin = {null};
            String[] version = {null};
            String[] source = {null};
            String[] error = {null};
            StringBuilder diag = new StringBuilder();
            int exit = new PluginClient("##JKCMP:")
                    .on(PluginProtocol.RESULT, json -> {
                        bin[0] = Ndjson.str(json, "bin");
                        version[0] = Ndjson.str(json, "version");
                        source[0] = Ndjson.str(json, "source");
                    })
                    .on(PluginProtocol.ERROR, json -> error[0] = Ndjson.str(json, PluginProtocol.MESSAGE))
                    .passthrough(ln -> diag.append(ln).append('\n'))
                    .run(PluginLaunch.javaCommand(workerJar, spec));
            return new Provision(
                    bin[0],
                    version[0],
                    source[0],
                    error[0],
                    exit,
                    exit != 0 && diag.length() > 0 ? diag.toString().trim() : null);
        } finally {
            Files.deleteIfExists(spec);
        }
    }
}
