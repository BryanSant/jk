// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.StepNames;

import build.jumpkick.cache.Cas;
import build.jumpkick.config.JkBuildParser;
import build.jumpkick.credential.RepoCredential;
import build.jumpkick.layout.BuildLayout;
import build.jumpkick.model.Dependency;
import build.jumpkick.model.GitRefSpec;
import build.jumpkick.model.JkBuild;
import build.jumpkick.plugin.build.Phase;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineKey;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepKind;
import build.jumpkick.worker.WorkerClient;
import build.jumpkick.worker.WorkerJar;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared {@code jk publish} pipeline — validate the project, then assemble/sign/upload via the
 * {@code jk-publisher} worker — hoisted out of the CLI so the resident engine can host the command
 * (Wave 2 of {@code docs/architecture/slim-client.md}) while the command's test-only in-process
 * path builds the exact same pipeline.
 *
 * <p>Interactivity split: everything env/keychain-shaped is resolved <em>client-side</em> and
 * arrives in the {@link Request} — the repository credential and the GPG passphrase — because the
 * engine's environment belongs to whichever invocation first spawned it, not to this one. Secrets
 * reach the worker via the same 0600 spec file the CLI fork used; they are never logged.
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

    /** The worker's uploaded-file count (0 for {@code --dry-run}), populated by the publish step. */
    public static final PipelineKey<Integer> FILES = PipelineKey.of("pub-files", Integer.class);

    /** Build the publish pipeline for {@code projectDir}. Locates the worker jar eagerly (fail fast, with side-load hints). */
    public static Pipeline publishPipeline(Path projectDir, Path cache, Request req) {
        Path workerJar = WorkerJar.PUBLISHER.locate(new Cas(cache));
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

    /** Fork the {@code jk-publisher} worker; returns the uploaded-file count. */
    private static int runWorker(Path workerJar, Path projectDir, Path jar, Request req) {
        try {
            Path spec = writeSpec(projectDir, jar, req);
            try {
                int[] files = {0};
                String[] error = {null};
                StringBuilder workerDiag = new StringBuilder();
                int exit = new WorkerClient("##JKPU:")
                        .on("result", json -> {
                            files[0] = Ndjson.intValue(json, "files", 0);
                            error[0] = Ndjson.str(json, "error");
                        })
                        .passthrough(line -> workerDiag.append(line).append('\n'))
                        .run(WorkerCommands.javaCommand(workerJar, spec));
                if (exit != 0) {
                    String diag = workerDiag.length() > 0 ? workerDiag.toString().trim() : null;
                    throw new RuntimeException("publish worker failed"
                            + (error[0] != null ? ": " + error[0] : diag != null ? ": " + diag : " (exit " + exit + ")"));
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
        List<String> lines = new ArrayList<>();
        lines.add("PROJECT_DIR " + projectDir.toAbsolutePath());
        lines.add("JAR " + jar.toAbsolutePath());
        lines.add("REPO_URL " + req.repoUrl());
        lines.add("DRY_RUN " + req.dryRun());
        lines.add("SLSA " + req.slsa());
        lines.add("SBOM " + req.sbom());
        lines.add("SIGN_SIGSTORE " + req.sigstore());

        // Credential (resolved client-side so neither the engine nor the worker needs env/keychain access).
        if (req.credential() instanceof RepoCredential.Basic b) {
            lines.add("REPO_AUTH_TYPE basic");
            lines.add("REPO_USER " + b.username());
            lines.add("REPO_PASS " + b.password());
        } else if (req.credential() instanceof RepoCredential.Bearer b) {
            lines.add("REPO_AUTH_TYPE bearer");
            lines.add("REPO_TOKEN " + b.token());
        } else {
            lines.add("REPO_AUTH_TYPE anonymous");
        }

        if (req.keyFile() != null) {
            lines.add("SIGN_GPG " + req.keyFile().toAbsolutePath());
            if (req.gpgPassphrase() != null) lines.add("SIGN_GPG_PASS " + req.gpgPassphrase());
        }
        if (req.region() != null && !req.region().isBlank()) lines.add("OBJECT_STORE_REGION " + req.region());
        if (req.endpoint() != null && !req.endpoint().isBlank()) {
            lines.add("OBJECT_STORE_ENDPOINT " + req.endpoint());
        }

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
        Files.write(spec, lines, StandardCharsets.UTF_8);
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
