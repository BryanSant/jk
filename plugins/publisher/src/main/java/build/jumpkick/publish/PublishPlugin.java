// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.publish;

import build.jumpkick.config.JkBuildParser;
import build.jumpkick.credential.RepoCredential;
import build.jumpkick.lock.Lockfile;
import build.jumpkick.lock.LockfileReader;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.ObjectStoreConfig;
import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginConfig;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.build.PackageIo;
import build.jumpkick.plugin.build.ProjectFacts;
import build.jumpkick.plugin.build.PublishContext;
import build.jumpkick.plugin.build.PublishExtension;
import build.jumpkick.plugin.build.PublishResult;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import build.jumpkick.publish.Checksums;
import build.jumpkick.publish.GpgSigner;
import build.jumpkick.publish.KeylessSigstoreSigner;
import build.jumpkick.publish.MavenPublisher;
import build.jumpkick.publish.PublishablePom;
import build.jumpkick.publish.Sbom;
import build.jumpkick.publish.SigningOptions;
import build.jumpkick.publish.SigstoreSigner;
import build.jumpkick.publish.SlsaProvenance;
import build.jumpkick.cache.SourcesJar;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code jk-publisher} worker: the terminal {@link PublishExtension} goal for Maven publishing.
 * Its worker entry ({@link #run}) reads the engine's spec and assembles a {@link PublishContext}
 * (main jar + repo/signing config + resolved secrets) which {@link #publish} consumes to assemble,
 * sign, and upload the Maven artifacts.
 *
 * <p>The spec is line-oriented ({@code PROJECT_DIR …}, {@code JAR …}, {@code REPO_URL …},
 * {@code SIGN_GPG …}, …); the reply is {@value #PREFIX}-prefixed NDJSON terminating in
 * {@code {"t":"result","ok":true,"files":N}} (or {@code "ok":false,"error":…}). Exit 0 success,
 * 1 publish error, 2 bad arguments.
 */
public final class PublishPlugin implements Plugin, PublishExtension {

    private static final String PREFIX = "##JKPU:";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-publisher", PREFIX);
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-publish-runner: expected spec file path as first argument");
            return 2;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-publish-runner: spec file not found: " + specFile);
            return 2;
        }

        // Parse spec.
        Path projectDir = null, jar = null;
        String repoUrl = null;
        String repoAuthType = "anonymous", repoUser = null, repoPass = null, repoToken = null;
        boolean dryRun = false, slsa = false, sbom = false;
        boolean signGpg = false, signSigstore = false;
        String gpgKeyFile = null, gpgPassphrase = null;
        String osRegion = null, osEndpoint = null;

        try {
            for (String line : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sp = line.indexOf(' ');
                if (sp < 0) continue;
                String key = line.substring(0, sp);
                String val = line.substring(sp + 1).strip();
                switch (key) {
                    case "PROJECT_DIR" -> projectDir = Path.of(val);
                    case "JAR" -> jar = Path.of(val);
                    case "REPO_URL" -> repoUrl = val;
                    case "REPO_AUTH_TYPE" -> repoAuthType = val;
                    case "REPO_USER" -> repoUser = val;
                    case "REPO_PASS" -> repoPass = val;
                    case "REPO_TOKEN" -> repoToken = val;
                    case "DRY_RUN" -> dryRun = "true".equalsIgnoreCase(val);
                    case "SLSA" -> slsa = "true".equalsIgnoreCase(val);
                    case "SBOM" -> sbom = "true".equalsIgnoreCase(val);
                    case "SIGN_GPG" -> {
                        signGpg = true;
                        gpgKeyFile = val;
                    }
                    case "SIGN_GPG_PASS" -> gpgPassphrase = val;
                    case "SIGN_SIGSTORE" -> signSigstore = "true".equalsIgnoreCase(val);
                    case "OBJECT_STORE_REGION" -> osRegion = val;
                    case "OBJECT_STORE_ENDPOINT" -> osEndpoint = val;
                    default -> {
                        // forward compatibility: ignore unknown keys
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("jk-publish-runner: could not read spec: " + e.getMessage());
            return 2;
        }
        if (projectDir == null || jar == null || repoUrl == null) {
            System.err.println("jk-publish-runner: spec missing PROJECT_DIR, JAR, or REPO_URL");
            return 2;
        }

        // Repo/signing settings ride config(); credentials + passphrase ride secret().
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("repoUrl", repoUrl);
        cfg.put("repoAuthType", repoAuthType);
        cfg.put("dryRun", dryRun);
        cfg.put("slsa", slsa);
        cfg.put("sbom", sbom);
        cfg.put("signGpg", signGpg);
        cfg.put("signSigstore", signSigstore);
        if (gpgKeyFile != null) cfg.put("gpgKeyFile", gpgKeyFile);
        if (osRegion != null) cfg.put("objectStoreRegion", osRegion);
        if (osEndpoint != null) cfg.put("objectStoreEndpoint", osEndpoint);

        Map<String, String> secrets = new LinkedHashMap<>();
        if (repoUser != null) secrets.put("repoUser", repoUser);
        if (repoPass != null) secrets.put("repoPass", repoPass);
        if (repoToken != null) secrets.put("repoToken", repoToken);
        if (gpgPassphrase != null) secrets.put("gpgPassphrase", gpgPassphrase);

        PublishContext ctx = new SpecPublishContext(
                new PluginConfig("jk-publisher", cfg),
                secrets,
                Optional.of(jar),
                projectDir,
                out);

        try {
            PublishResult result = publish(ctx);
            if (result.dryRun()) {
                out.emit("{\"t\":\"result\",\"ok\":true,\"dry_run\":true,\"files\":" + result.files() + "}");
            } else {
                out.emit("{\"t\":\"result\",\"ok\":true,\"files\":" + result.files() + "}");
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.emit("{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        } catch (Exception e) {
            out.emit("{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    @Override
    public PublishResult publish(PublishContext ctx) throws Exception {
        PluginConfig c = ctx.config();
        Path projectDir = ctx.moduleDir();
        Path jar = ctx.mainArtifact()
                .orElseThrow(() -> new IOException("publish goal needs a built main artifact"));
        URI repoUrl = URI.create(c.string("repoUrl"));

        JkBuild project = JkBuildParser.parse(projectDir.resolve("jk.toml"));

        // Assemble artifacts.
        List<MavenPublisher.Artifact> artifacts = new ArrayList<>();
        byte[] jarBytes = Files.readAllBytes(jar);
        artifacts.add(new MavenPublisher.Artifact(".jar", jarBytes));
        ctx.label("artifact " + jar.getFileName() + " (" + jarBytes.length + " bytes)");

        PublishablePom.Pom pom = PublishablePom.render(project, PublishablePom.Metadata.empty());
        byte[] pomBytes = pom.xml().getBytes(StandardCharsets.UTF_8);
        artifacts.add(new MavenPublisher.Artifact(".pom", pomBytes));
        ctx.label("artifact " + project.project().name() + "-" + project.project().version() + ".pom");

        if (project.project().sourcesMode().publishSources()) {
            byte[] sourcesBytes;
            build.jumpkick.layout.BuildLayout layout = build.jumpkick.layout.BuildLayout.of(projectDir, project);
            Path onDisk = layout.sourcesJar();
            if (Files.isRegularFile(onDisk)) {
                sourcesBytes = Files.readAllBytes(onDisk);
            } else {
                boolean compact = project.project().layout() == build.jumpkick.model.JkBuild.Layout.SIMPLE;
                List<Path> sourceRoots = compact
                        ? List.of(projectDir.resolve("src"))
                        : List.of(projectDir.resolve("src/main/java"), projectDir.resolve("src/main/kotlin"));
                sourcesBytes = SourcesJar.build(sourceRoots);
            }
            artifacts.add(new MavenPublisher.Artifact("-sources.jar", sourcesBytes));
            ctx.label("artifact " + project.project().name() + "-" + project.project().version() + "-sources.jar");
        }

        if (c.bool("slsa", false)) {
            String jarFilename = jar.getFileName().toString();
            SlsaProvenance.BuildContext buildCtx = new SlsaProvenance.BuildContext(
                    "https://github.com/buildjk/jk",
                    "https://buildjk.dev/jk-build/v1",
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Instant.now(),
                    Map.of("configRef", "jk.toml"),
                    Map.of(
                            "group", project.project().group(),
                            "artifact", project.project().name(),
                            "version", project.project().version(),
                            "jdk", project.project().jdk() == null ? "" : project.project().jdk()));
            byte[] provenance = SlsaProvenance.generate(
                    List.of(new SlsaProvenance.Subject(jarFilename, Checksums.sha256Hex(jarBytes))), buildCtx);
            artifacts.add(new MavenPublisher.Artifact(".intoto.json", provenance));
            ctx.label("artifact " + project.project().name() + "-" + project.project().version() + ".intoto.json");
        }

        if (c.bool("sbom", false)) {
            Path lockPath = projectDir.resolve("jk.lock");
            Lockfile lock = Files.exists(lockPath) ? LockfileReader.read(lockPath) : null;
            byte[] cdx = Sbom.cyclonedx(project, lock);
            byte[] spdxBytes = Sbom.spdx(project, lock);
            artifacts.add(new MavenPublisher.Artifact("-cyclonedx.json", cdx));
            artifacts.add(new MavenPublisher.Artifact("-spdx.json", spdxBytes));
            ctx.label("artifact cyclonedx+spdx (" + (cdx.length + spdxBytes.length) + " bytes)");
        }

        if (c.bool("dryRun", false)) {
            return PublishResult.dryRun(artifacts.size());
        }

        // Load signing.
        SigningOptions signing;
        GpgSigner gpg = c.bool("signGpg", false)
                ? GpgSigner.fromKeyFile(
                        Path.of(c.string("gpgKeyFile")),
                        ctx.secret("gpgPassphrase").map(String::toCharArray).orElse(new char[0]))
                : null;
        SigstoreSigner sigstoreSigner = c.bool("signSigstore", false) ? KeylessSigstoreSigner.sigstorePublic() : null;
        signing = new SigningOptions(gpg, sigstoreSigner);

        // Upload.
        try {
            RepoCredential cred =
                    switch (c.string("repoAuthType").toLowerCase()) {
                        case "basic" -> new RepoCredential.Basic(
                                ctx.secret("repoUser").orElse(""), ctx.secret("repoPass").orElse(""));
                        case "bearer" -> new RepoCredential.Bearer(ctx.secret("repoToken").orElse(""));
                        default -> new RepoCredential.Anonymous();
                    };
            ObjectStoreConfig objectStore = new ObjectStoreConfig(
                    c.stringOpt("objectStoreRegion").filter(s -> !s.isBlank()).orElse(null),
                    c.stringOpt("objectStoreEndpoint").filter(s -> !s.isBlank()).orElse(null),
                    null, null, null);
            MavenPublisher publisher = MavenPublisher.withObjectStore(repoUrl, cred, objectStore);

            MavenPublisher.Result result = publisher.publish(project.project(), artifacts, signing);
            for (Map.Entry<String, Integer> e : result.statusByPath().entrySet()) {
                ctx.label("upload " + e.getKey() + " → " + e.getValue());
            }
            if (!result.allOk()) {
                throw new IOException("partial upload failure");
            }
            return PublishResult.uploaded(result.statusByPath().size());
        } finally {
            if (signing.sigstore() instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    /** The generic terminal context assembled from the worker spec. */
    private record SpecPublishContext(
            PluginConfig config,
            Map<String, String> secrets,
            Optional<Path> mainArtifact,
            Path moduleDir,
            ProtocolWriter out)
            implements PublishContext {

        @Override
        public ProjectFacts project() {
            return new ProjectFacts("", "", "", 0, null, false, false, Map.of());
        }

        @Override
        public List<PackageIo.RuntimeEntry> runtimeEntries() {
            return List.of();
        }

        @Override
        public Path javaHome() {
            return null; // publishing forks no JDK tool
        }

        @Override
        public Optional<String> secret(String key) {
            return Optional.ofNullable(secrets.get(key));
        }

        @Override
        public void label(String text) {
            out.emit("{\"t\":\"progress\",\"msg\":" + Ndjson.quote(text) + "}");
        }
    }
}
