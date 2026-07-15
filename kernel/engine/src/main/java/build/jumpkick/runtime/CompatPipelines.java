// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.cache.Cas;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineKey;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepKind;
import build.jumpkick.engine.plugin.PluginClient;
import build.jumpkick.engine.plugin.PluginJar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared compat-bridge worker drivers — {@code jk import}'s conversion pipeline and {@code jk mvn}/
 * {@code jk gradle}'s distribution provisioning — hoisted out of the CLI so the resident engine can
 * host them (Wave 2 of {@code docs/architecture/slim-client.md}) while the commands' test-only
 * in-process paths run the exact same code.
 *
 * <p>Import is a single-step pipeline streaming plain {@code wrote}/{@code note} strings through the
 * {@link NoteObserver}; the worker's exit code is a <em>result</em> (carried on {@link #EXIT}), not
 * a pipeline failure. Provisioning is a plain one-shot call — no pipeline, no events, just a {@link
 * Provision} outcome — because the passthrough <em>exec</em> of the provisioned tool (which needs
 * the client's inherited stdio/terminal) stays client-side by design.
 */
public final class CompatPipelines {

    private CompatPipelines() {}

    /** Receives each import progress note ({@code kind} = {@code wrote}/{@code note}) as the worker streams it. */
    public interface NoteObserver {
        void onNote(String kind, String text);
    }

    /** The import worker's exit code (0 = converted cleanly). */
    public static final PipelineKey<Integer> EXIT = PipelineKey.of("import-exit", Integer.class);

    /** The import worker's reported issue count (rendered as "Import notes: N issue(s)"). */
    public static final PipelineKey<Integer> WARNINGS = PipelineKey.of("import-warnings", Integer.class);

    /** The import worker's terminal error text, if any. */
    public static final PipelineKey<String> ERROR = PipelineKey.of("import-error", String.class);

    /** The worker's passthrough chatter, kept only when it exited non-zero. */
    public static final PipelineKey<String> DIAG = PipelineKey.of("import-diag", String.class);

    /**
     * Build the import pipeline. All paths arrive absolute (the command pre-flighted source detection
     * and overwrite checks); {@code report} may be {@code null}. Locates the worker jar eagerly, so
     * a missing worker fails here with side-load instructions rather than mid-pipeline.
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

        List<String> specLines = new ArrayList<>();
        specLines.add("COMMAND import");
        specLines.add("SOURCE " + source.toAbsolutePath());
        specLines.add("OUT " + out.toAbsolutePath());
        specLines.add("BASE_DIR " + baseDir.toAbsolutePath());
        specLines.add("TMP_DIR " + tmpDir.toAbsolutePath());
        specLines.add("FORCE " + force);
        if (report != null) specLines.add("REPORT " + report.toAbsolutePath());

        Step convert = Step.builder("import")
                .kind(StepKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("convert " + source.getFileName() + " via compat worker");
                    Path spec = Files.createTempFile("jk-compat-", ".spec");
                    try {
                        Files.write(spec, specLines, StandardCharsets.UTF_8);
                        StringBuilder diag = new StringBuilder();
                        int exit = new PluginClient("##JKCMP:")
                                .on("wrote", json -> observer.onNote("wrote", Ndjson.str(json, "path")))
                                .on("note", json -> observer.onNote("note", Ndjson.str(json, "msg")))
                                .on("result", json -> {
                                    String err = Ndjson.str(json, "error");
                                    if (err != null) ctx.put(ERROR, err);
                                    ctx.put(WARNINGS, Ndjson.intValue(json, "warnings", 0));
                                })
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
     * Provision a Maven/Gradle distribution via the compat-bridge worker: link a discovered install
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
                    List.of(
                            "COMMAND " + (isGradle ? "provision_gradle" : "provision_mvn"),
                            "PROJECT_DIR " + projectDir.toAbsolutePath(),
                            "TOOLS_ROOT " + toolsRoot.toAbsolutePath(),
                            "NO_DISCOVER " + noDiscover),
                    StandardCharsets.UTF_8);

            String[] bin = {null};
            String[] version = {null};
            String[] source = {null};
            String[] error = {null};
            StringBuilder diag = new StringBuilder();
            int exit = new PluginClient("##JKCMP:")
                    .on("result", json -> {
                        bin[0] = Ndjson.str(json, "bin");
                        version[0] = Ndjson.str(json, "version");
                        source[0] = Ndjson.str(json, "source");
                        error[0] = Ndjson.str(json, "error");
                    })
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
