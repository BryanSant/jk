// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.plugin.protocol.Ndjson;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepKind;
import cc.jumpkick.run.StepNames;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * The shared {@code jk publish} pipeline — validate the project, then assemble/sign/upload via the
 * {@code jk-publisher} plugin — hoisted out of the CLI so the resident engine can host the command
 * (Wave 2 of {@code docs/architecture/slim-client.md}) while the command's test-only in-process
 * path builds the exact same pipeline.
 *
 * <p>Interactivity split: everything env/keychain-shaped is resolved <em>client-side</em> and
 * arrives in the {@link Request} — the repository credential and the GPG passphrase — because the
 * engine's environment belongs to whichever invocation first spawned it, not to this one. Secrets
 * reach the plugin via the same 0600 spec file the CLI fork used; they are never logged.
 */
public final class PublishPipelines {

    private PublishPipelines() {}

    /**
     * Everything the publish pipeline needs beyond the project directory — the command's validated
     * flags plus the client-resolved credential/passphrase. {@code keyFile} is non-null only when
     * {@code --sign} was set (the command already enforced {@code --sign} ⇒ {@code --key-file}).
     */
    public record Request(
            URI repoUrl,
            String region,
            String endpoint,
            Path jarPath,
            boolean allowSnapshot,
            boolean dryRun,
            Path keyFile,
            String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            RepoCredential credential) {}

    public static final PipelineKey<JkBuild> PROJECT = PipelineKey.of("project", JkBuild.class);
    public static final PipelineKey<Path> JAR = PipelineKey.of("jar", Path.class);

    /** The plugin's uploaded-file count (0 for {@code --dry-run}), populated by the publish step. */
    public static final PipelineKey<Integer> FILES = PipelineKey.of("pub-files", Integer.class);

    /** Build the publish pipeline for {@code projectDir}. Locates the plugin jar eagerly (fail fast, with side-load hints). */
    public static Pipeline publishPipeline(Path projectDir, Path cache, Request req) {
        Path workerJar = PluginJar.PUBLISHER.locate(new Cas(cache));
        Path jkBuildPath = projectDir.resolve("jk.toml");

        Step parseBuild = Step.builder(StepNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + validate version");
                    JkBuild project = JkBuildParser.parse(jkBuildPath);
                    if (project.project().version().endsWith("-SNAPSHOT") && !req.allowSnapshot()) {
                        ctx.error(
                                "snapshot",
                                "refusing to publish a SNAPSHOT version "
                                        + "(use --allow-snapshot, or rename to -dev.N / -rc.N "
                                        + "per PRD §21.4).");
                        throw new RuntimeException("snapshot refused");
                    }
                    // A branch-tracked git dep is locked in jk.lock, but its pin moves on the next
                    // `jk update --git`/`jk fetch` — not a stable reference for external consumers
                    // of the published artifact. Refuse until it's pinned to a tag/rev instead.
                    Dependency branchGit = firstBranchGitDep(project);
                    if (branchGit != null) {
                        ctx.error(
                                "branch-git-dep",
                                "refusing to publish: dependency `"
                                        + branchGit.module()
                                        + "` tracks a git branch, which is not a stable reference for"
                                        + " published consumers. Pin it to an immutable git tag/rev"
                                        + " (or a released `version = \"…\"`) before publishing.");
                        throw new RuntimeException("branch git dependency refused");
                    }
                    BuildLayout layout = BuildLayout.of(projectDir, project);
                    Path jar = req.jarPath() != null ? req.jarPath() : layout.mainJar();
                    if (!Files.exists(jar)) {
                        ctx.error(
                                "missing-jar",
                                "main jar not found at " + jar + " — run `jk build` first or pass --jar.");
                        throw new RuntimeException("missing jar");
                    }
                    ctx.put(PROJECT, project);
                    ctx.put(JAR, jar);
                    ctx.progress(1);
                })
                .build();

        Step publish = Step.builder("publish")
                .phase(Phase.PUBLISH)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    Path jar = ctx.require(JAR);
                    ctx.label(req.dryRun() ? "dry-run — assembling publish bundle" : "publish to " + req.repoUrl());
                    try {
                        ctx.put(FILES, runWorker(workerJar, projectDir, jar, req));
                    } catch (RuntimeException e) {
                        ctx.error("publish", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("publish").addStep(parseBuild).addStep(publish).build();
    }

    /** Fork the {@code jk-publisher} plugin; returns the uploaded-file count. */
    private static int runWorker(Path workerJar, Path projectDir, Path jar, Request req) {
        try {
            Path spec = writeSpec(projectDir, jar, req);
            try {
                int[] files = {0};
                String[] error = {null};
                StringBuilder workerDiag = new StringBuilder();
                int exit = new PluginClient("##JKPU:")
                        .on(PluginProtocol.RESULT, json -> files[0] = Ndjson.intValue(json, "files", 0))
                        .on(PluginProtocol.ERROR, json -> error[0] = Ndjson.str(json, PluginProtocol.MESSAGE))
                        .passthrough(line -> workerDiag.append(line).append('\n'))
                        .run(PluginLaunch.javaCommand(workerJar, spec));
                if (exit != 0) {
                    String diag =
                            workerDiag.length() > 0 ? workerDiag.toString().trim() : null;
                    throw new RuntimeException("publish worker failed"
                            + (error[0] != null
                                    ? ": " + error[0]
                                    : diag != null ? ": " + diag : " (exit " + exit + ")"));
                }
                return files[0];
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("publish worker interrupted", e);
        }
    }

    private static Path writeSpec(Path projectDir, Path jar, Request req) throws IOException {
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                .configString("repoUrl", req.repoUrl().toString())
                .configBool("dryRun", req.dryRun())
                .configBool("slsa", req.slsa())
                .configBool("sbom", req.sbom())
                .configBool("signSigstore", req.sigstore());

        // Credential (resolved client-side so neither the engine nor the plugin needs env/keychain access).
        if (req.credential() instanceof RepoCredential.Basic b) {
            sw.configString("repoAuthType", "basic")
                    .secret("repoUser", b.username())
                    .secret("repoPass", b.password());
        } else if (req.credential() instanceof RepoCredential.Bearer b) {
            sw.configString("repoAuthType", "bearer").secret("repoToken", b.token());
        } else {
            sw.configString("repoAuthType", "anonymous");
        }

        if (req.keyFile() != null) {
            sw.configBool("signGpg", true)
                    .configString("gpgKeyFile", req.keyFile().toAbsolutePath().toString());
            if (req.gpgPassphrase() != null) sw.secret("gpgPassphrase", req.gpgPassphrase());
        }
        if (req.region() != null && !req.region().isBlank()) sw.configString("objectStoreRegion", req.region());
        if (req.endpoint() != null && !req.endpoint().isBlank()) sw.configString("objectStoreEndpoint", req.endpoint());
        sw.artifact(jar);
        sw.layout(java.util.Map.of("moduleDir", projectDir));

        // Use a 0600 temp file so credentials aren't world-readable.
        Path spec;
        try {
            spec = Files.createTempFile(
                    "jk-publish-",
                    ".spec",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (Windows): fall back to default permissions.
            spec = Files.createTempFile("jk-publish-", ".spec");
        }
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        return spec;
    }

    /**
     * The first branch-tracked git dependency declared in any scope, or {@code null} if none. Its
     * lockfile pin moves whenever the branch tip is re-resolved, so it cannot appear in a published
     * POM. A tag/rev git dep is materialized to a real, stable coordinate and is fine.
     */
    private static Dependency firstBranchGitDep(JkBuild project) {
        for (List<Dependency> deps : project.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                if (d.isGit() && d.gitSource().ref() instanceof GitRefSpec.Branch) {
                    return d;
                }
            }
        }
        return null;
    }
}
