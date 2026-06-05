// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;

import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.RepoCredentialResolver;
import dev.jkbuild.publish.Checksums;
import dev.jkbuild.publish.GpgSigner;
import dev.jkbuild.publish.KeylessSigstoreSigner;
import dev.jkbuild.publish.MavenPublisher;
import dev.jkbuild.publish.PublishablePom;
import dev.jkbuild.publish.SigningOptions;
import dev.jkbuild.publish.SigstoreSigner;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.publish.Sbom;
import dev.jkbuild.publish.SlsaProvenance;
import dev.jkbuild.publish.SourcesJar;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * {@code jk publish} — first cut (PRD §21). Assembles the publish bundle
 * (main jar, generated POM, sources jar + four checksum files each) and
 * uploads it to a Maven HTTP repository.
 *
 * <p>Goal shape: {@code parse-build} → {@code assemble-artifacts} →
 * {@code sign} (only when signing is requested) → {@code upload}
 * (skipped on {@code --dry-run}). The signing options carry the GPG
 * key and Sigstore client; we close them in a finally block so the
 * Sigstore HTTP client doesn't leak on cancellation.
 *
 * <p>SNAPSHOT versions are refused per PRD §21.4 unless
 * {@code --allow-snapshot} is set.
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
            description = "Object-store region for s3:// / gs:// targets (else the AWS env).")
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
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> ARTIFACTS = GoalKey.of("artifacts", List.class);
    private static final GoalKey<SigningOptions> SIGNING = GoalKey.of("signing", SigningOptions.class);
    private static final GoalKey<MavenPublisher.Result> PUB_RESULT =
            GoalKey.of("publish-result", MavenPublisher.Result.class);

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = global.workingDir();
        Path jkBuildPath = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuildPath)) {
            System.err.println("jk publish: " + jkBuildPath + " not found.");
            return 66;
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

        Phase assembleArtifacts = Phase.builder("assemble-artifacts")
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    Path jar = ctx.require(JAR);
                    ctx.label("assemble publish bundle");
                    byte[] jarBytes = Files.readAllBytes(jar);
                    List<MavenPublisher.Artifact> artifacts = new ArrayList<>();
                    artifacts.add(new MavenPublisher.Artifact(".jar", jarBytes));

                    PublishablePom.Pom pom = PublishablePom.render(project,
                            PublishablePom.Metadata.empty());
                    artifacts.add(new MavenPublisher.Artifact(".pom",
                            pom.xml().getBytes(StandardCharsets.UTF_8)));

                    if (!noSources) {
                        Path srcRoot = projectDir.resolve("src/main/java");
                        byte[] sourcesJar = SourcesJar.build(List.of(srcRoot));
                        artifacts.add(new MavenPublisher.Artifact("-sources.jar", sourcesJar));
                    }

                    if (slsa) {
                        artifacts.add(new MavenPublisher.Artifact(".intoto.json",
                                buildSlsaProvenance(project.project(),
                                        jar.getFileName().toString(), jarBytes)));
                    }

                    if (sbom) {
                        Lockfile lock = loadLockfileIfPresent(projectDir);
                        artifacts.add(new MavenPublisher.Artifact("-cyclonedx.json",
                                Sbom.cyclonedx(project, lock)));
                        artifacts.add(new MavenPublisher.Artifact("-spdx.json",
                                Sbom.spdx(project, lock)));
                    }
                    ctx.put(ARTIFACTS, artifacts);
                    ctx.progress(1);
                })
                .build();

        Phase prepareSigning = Phase.builder("prepare-signing")
                .kind(PhaseKind.IO)
                .requires("assemble-artifacts")
                .scope(1)
                .execute(ctx -> {
                    if (!sign && !sigstore) {
                        ctx.label("no signing requested");
                        ctx.put(SIGNING, new SigningOptions(null, null));
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("load signing keys");
                    try {
                        ctx.put(SIGNING, buildSigningOptions());
                    } catch (RuntimeException | IOException e) {
                        ctx.error("signing", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase upload = Phase.builder("upload")
                .kind(PhaseKind.IO)
                .requires("prepare-signing")
                .scope(1)
                .execute(ctx -> {
                    if (dryRun) {
                        ctx.label("dry-run — printing upload plan");
                        if (!global.outputIsJson()) {
                            printDryRunPlan(ctx.require(PROJECT), ctx.require(ARTIFACTS));
                        }
                        ctx.progress(1);
                        return;
                    }
                    JkBuild project = ctx.require(PROJECT);
                    @SuppressWarnings("unchecked")
                    List<MavenPublisher.Artifact> artifacts =
                            (List<MavenPublisher.Artifact>) ctx.require(ARTIFACTS);
                    ctx.label("upload to " + repoUrl);
                    dev.jkbuild.model.ObjectStoreConfig objectStore =
                            new dev.jkbuild.model.ObjectStoreConfig(
                                    blankToNull(region), blankToNull(endpoint), null, null, null);
                    MavenPublisher publisher = MavenPublisher.withObjectStore(
                            repoUrl, resolvePublishCredential(project), objectStore);
                    try {
                        MavenPublisher.Result result = publisher.publish(
                                project.project(), artifacts, ctx.require(SIGNING));
                        ctx.put(PUB_RESULT, result);
                        if (!result.allOk()) {
                            ctx.error("upload", "publish reported partial failure");
                            throw new RuntimeException("upload failed");
                        }
                    } catch (IOException | InterruptedException e) {
                        ctx.error("upload", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("publish")
                .addPhase(parseBuild)
                .addPhase(assembleArtifacts)
                .addPhase(prepareSigning)
                .addPhase(upload)
                .build();

        GoalResult result;
        try {
            result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        } finally {
            goal.get(SIGNING).ifPresent(PublishCommand::closeSigningOptions);
        }

        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("snapshot".equals(d.code())) return 65;
                if ("missing-jar".equals(d.code())) return 66;
            }
            return 1;
        }

        if (dryRun) return 0;

        JkBuild project = goal.get(PROJECT).orElseThrow();
        MavenPublisher.Result pub = goal.get(PUB_RESULT).orElseThrow();
        SigningOptions signing = goal.get(SIGNING).orElseThrow();
        if (!global.outputIsJson()) {
            System.out.println("Published " + Coords.gav(
                    project.project().group(), project.project().name(), project.project().version())
                    + " (" + pub.statusByPath().size() + " files"
                    + (signing.isNoop() ? "" : ", signed") + ")");
        }
        return 0;
    }

    /**
     * Credential for the publish upload. Explicit {@code --user}/{@code --password}
     * (or {@code PUBLISH_USER}/{@code PUBLISH_PASSWORD}) win for back-compat;
     * otherwise resolve through the shared chain. If {@code --repo-url} matches
     * a repository declared in {@code jk.toml}, its name (for env/store/settings)
     * and inline credential apply; the forge-token bridge always applies by host
     * (so publishing to GitHub Packages reuses a `jk auth login` token).
     */
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

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    private void printDryRunPlan(JkBuild project, List<MavenPublisher.Artifact> artifacts) {
        String groupPath = project.project().group().replace('.', '/');
        String prefix = repoUrl + (repoUrl.toString().endsWith("/") ? "" : "/")
                + groupPath + "/" + project.project().name() + "/"
                + project.project().version() + "/";
        System.out.println("Would PUT to " + prefix + ":");
        for (MavenPublisher.Artifact a : artifacts) {
            String name = project.project().name() + "-"
                    + project.project().version() + a.filenameSuffix();
            System.out.println("  " + name + " (" + a.body().length + " bytes)");
            System.out.println("  " + name + ".md5 / .sha1 / .sha256 / .sha512");
            if (sign) System.out.println("  " + name + ".asc + four checksums");
            if (sigstore) System.out.println("  " + name + ".sigstore + four checksums");
            if (slsa && a.filenameSuffix().equals(".jar")) {
                System.out.println("  " + project.project().name() + "-"
                        + project.project().version() + ".intoto.json + four checksums");
            }
        }
    }

    private SigningOptions buildSigningOptions() throws IOException {
        GpgSigner gpg = loadGpgIfRequested();
        SigstoreSigner sigstoreSigner = sigstore ? KeylessSigstoreSigner.sigstorePublic() : null;
        return new SigningOptions(gpg, sigstoreSigner);
    }

    private GpgSigner loadGpgIfRequested() throws IOException {
        if (!sign) return null;
        if (keyFile == null) {
            throw new IllegalArgumentException(
                    "jk publish --sign requires --key-file <path>.");
        }
        String pass = keyPassphrase != null ? keyPassphrase : System.getenv("JK_GPG_PASSPHRASE");
        return GpgSigner.fromKeyFile(keyFile, pass == null ? new char[0] : pass.toCharArray());
    }

    private static Lockfile loadLockfileIfPresent(Path projectDir) throws IOException {
        Path lockPath = projectDir.resolve("jk.lock");
        return Files.exists(lockPath) ? LockfileReader.read(lockPath) : null;
    }

    private static byte[] buildSlsaProvenance(JkBuild.Project project, String jarFilename, byte[] jarBytes) {
        Instant now = Instant.now();
        SlsaProvenance.BuildContext ctx = new SlsaProvenance.BuildContext(
                "https://github.com/buildjk/jk",
                "https://buildjk.dev/jk-build/v1",
                UUID.randomUUID().toString(),
                now,
                now,
                Map.of("configRef", "jk.toml"),
                Map.of(
                        "group", project.group(),
                        "artifact", project.name(),
                        "version", project.version(),
                        "jdk", String.valueOf(project.jdk())));
        return SlsaProvenance.generate(
                List.of(new SlsaProvenance.Subject(jarFilename, Checksums.sha256Hex(jarBytes))),
                ctx);
    }

    private static void closeSigningOptions(SigningOptions signing) {
        if (signing.sigstore() instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) { /* best effort */ }
        }
    }
}
