// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.repo.RepoCredentialResolver;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerClient;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk publish} — assembles, signs, and uploads Maven artifacts via the {@code
 * jk-publish-runner} worker subprocess (PRD §21). BouncyCastle, sigstore-java, and the upload HTTP
 * logic live in the worker, not in the main jk binary.
 *
 * <p>The parent process validates inputs and resolves credentials, then forks the worker which
 * streams {@code ##JKPU:} NDJSON progress back. SNAPSHOT versions are refused unless {@code
 * --allow-snapshot} is set (PRD §21.4).
 */
public final class PublishCommand implements CliCommand {

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public String description() {
        return "Publish artifacts to a package repository";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<url>", "Target Maven repository base URL.", "--repo-url")
                        .require(),
                Opt.value("<user>", "HTTP Basic username (PUBLISH_USER env).", "--user"),
                Opt.value("<pass>", "HTTP Basic password (PUBLISH_PASSWORD env).", "--password"),
                Opt.value("<REGION>", "Object-store region for s3:// / gs://.", "--region"),
                Opt.value("<URL>", "Object-store endpoint override for s3://.", "--endpoint"),
                Opt.value("<file>", "Override the main jar path.", "--jar"),
                Opt.flag("Permit publishing -SNAPSHOT versions.", "--allow-snapshot"),
                Opt.flag("Print the upload plan; no HTTP requests.", "--dry-run"),
                Opt.flag("Detached .asc GPG signature per artifact.", "--sign"),
                Opt.value("<file>", "GPG secret key path. Required with --sign.", "--key-file"),
                Opt.value("<pass>", "Key passphrase (JK_GPG_PASSPHRASE env).", "--key-passphrase"),
                Opt.flag("Sign with Sigstore keyless OIDC (.sigstore).", "--sigstore"),
                Opt.flag("Emit a SLSA v1 in-toto provenance statement.", "--slsa"),
                Opt.flag("Emit CycloneDX 1.6 and SPDX 2.3 SBOMs.", "--sbom"));
    }

    URI repoUrl;
    String username;
    String password;
    String region;
    String endpoint;
    Path jarPath;
    boolean allowSnapshot;
    boolean dryRun;
    boolean sign;
    Path keyFile;
    String keyPassphrase;
    boolean sigstore;
    boolean slsa;
    boolean sbom;
    GlobalOptions global;

    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    private static final GoalKey<Path> JAR = GoalKey.of("jar", Path.class);
    private static final GoalKey<String> PUB_SUMMARY = GoalKey.of("pub-summary", String.class);

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.username = in.value("user").orElse(null);
        this.password = in.value("password").orElse(null);
        this.region = in.value("region").orElse(null);
        this.endpoint = in.value("endpoint").orElse(null);
        this.jarPath = in.value("jar").map(Path::of).orElse(null);
        this.allowSnapshot = in.isSet("allow-snapshot");
        this.dryRun = in.isSet("dry-run");
        this.sign = in.isSet("sign");
        this.keyFile = in.value("key-file").map(Path::of).orElse(null);
        this.keyPassphrase = in.value("key-passphrase").orElse(null);
        this.sigstore = in.isSet("sigstore");
        this.slsa = in.isSet("slsa");
        this.sbom = in.isSet("sbom");
        this.global = GlobalOptions.from(in);

        Path projectDir = global.workingDir();
        Path jkBuildPath = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuildPath)) {
            System.err.println("jk publish: " + jkBuildPath + " not found.");
            return 66;
        }
        if (sign && keyFile == null) {
            System.err.println("jk publish: --sign requires --key-file <path>.");
            return Exit.USAGE;
        }
        Path cache = JkDirs.cache();

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + validate version");
                    JkBuild project = JkBuildParser.parse(jkBuildPath);
                    if (project.project().version().endsWith("-SNAPSHOT") && !allowSnapshot) {
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
                    Path jar = jarPath != null ? jarPath : layout.mainJar();
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

        Phase publish = Phase.builder("publish")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    Path jar = ctx.require(JAR);
                    ctx.label(dryRun ? "dry-run — assembling publish bundle" : "publish to " + repoUrl);
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

        Goal goal =
                Goal.builder("publish").addPhase(parseBuild).addPhase(publish).build();

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
                    + Coords.gav(
                            project.project().group(),
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
            Path workerJar = WorkerJar.PUBLISHER.locate(new Cas(cache));
            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin")
                    .resolve(HostPlatform.isWindows() ? "java.exe" : "java");
            List<String> cmd = dev.jkbuild.worker.JvmOptions.javaCommand(
                    javaExe.toString(),
                    1,
                    List.of("-jar", workerJar.toString(), spec.toAbsolutePath().toString()));

            int[] files = {0};
            String[] error = {null};
            StringBuilder workerDiag = new StringBuilder();
            int exit = new WorkerClient("##JKPU:")
                    .on("result", json -> {
                        files[0] = Ndjson.intValue(json, "files", 0);
                        error[0] = Ndjson.str(json, "error");
                    })
                    .passthrough(line -> workerDiag.append(line).append('\n'))
                    .run(cmd);
            if (exit != 0) {
                String diag = workerDiag.length() > 0 ? workerDiag.toString().trim() : null;
                throw new RuntimeException("publish worker failed"
                        + (error[0] != null ? ": " + error[0] : diag != null ? ": " + diag : " (exit " + exit + ")"));
            }
            return dryRun ? "(dry-run)" : "(" + files[0] + " files)";
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private Path writeSpec(Path projectDir, Path jar, RepoCredential cred) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("PROJECT_DIR " + projectDir.toAbsolutePath());
        lines.add("JAR " + jar.toAbsolutePath());
        lines.add("REPO_URL " + repoUrl);
        lines.add("DRY_RUN " + dryRun);
        lines.add("SLSA " + slsa);
        lines.add("SBOM " + sbom);
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
            String pass = keyPassphrase != null ? keyPassphrase : System.getenv("JK_GPG_PASSPHRASE");
            if (pass != null) lines.add("SIGN_GPG_PASS " + pass);
        }
        if (region != null && !region.isBlank()) lines.add("OBJECT_STORE_REGION " + region);
        if (endpoint != null && !endpoint.isBlank()) lines.add("OBJECT_STORE_ENDPOINT " + endpoint);

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
