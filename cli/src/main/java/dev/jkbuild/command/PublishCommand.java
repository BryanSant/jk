// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.RepoCredentialResolver;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerProcess;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk publish} — assembles, signs, and uploads Maven artifacts via the
 * {@code jk-publish-runner} worker subprocess (PRD §21). BouncyCastle, sigstore-java,
 * and the upload HTTP logic live in the worker, not in the main jk binary.
 *
 * <p>The parent process validates inputs and resolves credentials, then forks the
 * worker which streams {@code ##JKPU:} NDJSON progress back. SNAPSHOT versions are
 * refused unless {@code --allow-snapshot} is set (PRD §21.4).
 */
@Command(name = "publish", description = "Publish artifacts to a package repository")
public final class PublishCommand implements Callable<Integer> {

    @Option(names = "--repo-url", required = true,
            description = "Target Maven repository base URL.")
    URI repoUrl;

    @Option(names = "--user", description = "HTTP Basic auth username (or via PUBLISH_USER env).")
    String username;

    @Option(names = "--password", description = "HTTP Basic auth password (or via PUBLISH_PASSWORD env).",
            interactive = false, arity = "0..1")
    String password;

    @Option(names = "--region", paramLabel = "<REGION>",
            description = "Object-store region for s3:// / gs:// targets.")
    String region;

    @Option(names = "--endpoint", paramLabel = "<URL>",
            description = "Object-store endpoint override for s3:// (MinIO/S3-compatible).")
    String endpoint;

    @Option(names = "--jar",
            description = "Override the main jar path. Default: target/<artifact>-<version>.jar.")
    Path jarPath;

    @Option(names = "--no-sources", description = "Skip the sources jar.")
    boolean noSources;

    @Option(names = "--allow-snapshot",
            description = "Permit publishing -SNAPSHOT versions (refused by default per PRD §21.4).")
    boolean allowSnapshot;

    @Option(names = "--dry-run",
            description = "Assemble and print the upload plan without making HTTP requests.")
    boolean dryRun;

    @Option(names = "--sign", description = "Emit a detached .asc GPG signature for every artifact.")
    boolean sign;

    @Option(names = "--key-file",
            description = "Path to the GPG secret key (armored or binary). Required with --sign.")
    Path keyFile;

    @Option(names = "--key-passphrase",
            description = "Passphrase for the secret key, or via JK_GPG_PASSPHRASE env.")
    String keyPassphrase;

    @Option(names = "--sigstore",
            description = "Sign each artifact with Sigstore keyless OIDC (.sigstore file).")
    boolean sigstore;

    @Option(names = "--slsa",
            description = "Emit a SLSA v1 in-toto provenance statement (.intoto.json) for the main jar.")
    boolean slsa;

    @Option(names = "--sbom",
            description = "Emit CycloneDX 1.6 (-cyclonedx.json) and SPDX 2.3 (-spdx.json) SBOMs.")
    boolean sbom;

    @picocli.CommandLine.Mixin GlobalOptions global;

    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    private static final GoalKey<Path> JAR = GoalKey.of("jar", Path.class);
    private static final GoalKey<String> PUB_SUMMARY = GoalKey.of("pub-summary", String.class);

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = global.workingDir();
        Path jkBuildPath = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuildPath)) {
            System.err.println("jk publish: " + jkBuildPath + " not found.");
            return 66;
        }
        if (sign && keyFile == null) {
            System.err.println("jk publish: --sign requires --key-file <path>.");
            return 64; // EX_USAGE
        }
        Path cache = JkDirs.cache();

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + validate version");
                    JkBuild project = JkBuildParser.parse(jkBuildPath);
                    if (project.project().version().endsWith("-SNAPSHOT") && !allowSnapshot) {
                        ctx.error("snapshot", "refusing to publish a SNAPSHOT version "
                                + "(use --allow-snapshot, or rename to -dev.N / -rc.N "
                                + "per PRD §21.4).");
                        throw new RuntimeException("snapshot refused");
                    }
                    BuildLayout layout = BuildLayout.of(projectDir, project);
                    Path jar = jarPath != null ? jarPath : layout.mainJar();
                    if (!Files.exists(jar)) {
                        ctx.error("missing-jar", "main jar not found at " + jar
                                + " — run `jk build` first or pass --jar.");
                        throw new RuntimeException("missing jar");
                    }
                    ctx.put(PROJECT, project);
                    ctx.put(JAR, jar);
                    ctx.progress(1);
                })
                .build();

        Phase publish = Phase.builder("publish")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    Path jar = ctx.require(JAR);
                    ctx.label(dryRun ? "dry-run — assembling publish bundle"
                            : "publish to " + repoUrl);
                    try {
                        String summary = runWorker(projectDir, jar, project, cache);
                        ctx.put(PUB_SUMMARY, summary);
                    } catch (RuntimeException e) {
                        ctx.error("publish", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("publish")
                .addPhase(parseBuild)
                .addPhase(publish)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("snapshot".equals(d.code())) return 65;
                if ("missing-jar".equals(d.code())) return 66;
            }
            return 1;
        }

        if (!global.outputIsJson()) {
            JkBuild project = goal.get(PROJECT).orElseThrow();
            String summary = goal.get(PUB_SUMMARY).orElse("");
            System.out.println("Published "
                    + Coords.gav(project.project().group(),
                            project.project().name(),
                            project.project().version())
                    + (summary.isBlank() ? "" : " " + summary));
        }
        return 0;
    }

    private String runWorker(Path projectDir, Path jar, JkBuild project, Path cache)
            throws IOException, InterruptedException {
        // Resolve credential in the parent — needs env/keychain access.
        RepoCredential cred = resolvePublishCredential(project);

        Path spec = writeSpec(projectDir, jar, cred);
        try {
            Path workerJar = WorkerJar.PUBLISH_RUNNER.locate(new Cas(cache));
            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin")
                    .resolve(isWindows() ? "java.exe" : "java");
            List<String> cmd = List.of(javaExe.toString(), "-jar",
                    workerJar.toString(), spec.toAbsolutePath().toString());

            int[] files = {0};
            String[] error = {null};
            StringBuilder workerDiag = new StringBuilder();
            int exit = WorkerProcess.run(cmd, "##JKPU:", json -> {
                if ("result".equals(Ndjson.str(json, "t"))) {
                    files[0] = Ndjson.intValue(json, "files", 0);
                    error[0] = Ndjson.str(json, "error");
                }
            }, line -> workerDiag.append(line).append('\n'));
            if (exit != 0) {
                String diag = workerDiag.length() > 0 ? workerDiag.toString().trim() : null;
                throw new RuntimeException("publish worker failed"
                        + (error[0] != null ? ": " + error[0]
                                : diag != null ? ": " + diag
                                : " (exit " + exit + ")"));
            }
            return dryRun ? "(dry-run)" : "(" + files[0] + " files)";
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private Path writeSpec(Path projectDir, Path jar, RepoCredential cred)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("PROJECT_DIR " + projectDir.toAbsolutePath());
        lines.add("JAR "         + jar.toAbsolutePath());
        lines.add("REPO_URL "    + repoUrl);
        lines.add("DRY_RUN "     + dryRun);
        lines.add("NO_SOURCES "  + noSources);
        lines.add("SLSA "        + slsa);
        lines.add("SBOM "        + sbom);
        lines.add("SIGN_SIGSTORE " + sigstore);

        // Credential (resolved in parent so the worker doesn't need keychain access).
        if (cred instanceof RepoCredential.Basic b) {
            lines.add("REPO_AUTH_TYPE basic");
            lines.add("REPO_USER " + b.username());
            lines.add("REPO_PASS " + b.password());
        } else if (cred instanceof RepoCredential.Bearer b) {
            lines.add("REPO_AUTH_TYPE bearer");
            lines.add("REPO_TOKEN " + b.token());
        } else {
            lines.add("REPO_AUTH_TYPE anonymous");
        }

        if (sign && keyFile != null) {
            lines.add("SIGN_GPG " + keyFile.toAbsolutePath());
            String pass = keyPassphrase != null ? keyPassphrase
                    : System.getenv("JK_GPG_PASSPHRASE");
            if (pass != null) lines.add("SIGN_GPG_PASS " + pass);
        }
        if (region != null && !region.isBlank())   lines.add("OBJECT_STORE_REGION " + region);
        if (endpoint != null && !endpoint.isBlank()) lines.add("OBJECT_STORE_ENDPOINT " + endpoint);

        // Use a 0600 temp file so credentials aren't world-readable.
        Path spec;
        try {
            spec = Files.createTempFile("jk-publish-", ".spec",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (Windows): fall back to default permissions.
            spec = Files.createTempFile("jk-publish-", ".spec");
        }
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }

    private RepoCredential resolvePublishCredential(JkBuild project) {
        String user = username != null ? username : System.getenv("PUBLISH_USER");
        if (user != null && !user.isBlank()) {
            String pass = password != null ? password : System.getenv("PUBLISH_PASSWORD");
            return new RepoCredential.Basic(user, pass == null ? "" : pass);
        }
        String matchedName = null;
        Optional<RepoCredential> inline = Optional.empty();
        String target = repoUrl.toString();
        for (RepositorySpec spec : project.repositories()) {
            String base = spec.url().toString();
            String basePrefix = base.endsWith("/") ? base : base + "/";
            if (target.equals(base) || target.startsWith(basePrefix)) {
                matchedName = spec.name();
                inline = spec.credential();
                break;
            }
        }
        return new RepoCredentialResolver().resolve(matchedName, repoUrl, inline);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
