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
import build.jumpkick.plugin.protocol.PluginReply;
import build.jumpkick.plugin.protocol.PluginSpec;
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
 * The {@code jk-publisher} plugin: the terminal {@link PublishExtension} goal for Maven publishing.
 * Its plugin entry ({@link #run}) reads the engine's spec and assembles a {@link PublishContext}
 * (main jar + repo/signing config + resolved secrets) which {@link #publish} consumes to assemble,
 * sign, and upload the Maven artifacts.
 *
 * <p>The spec is line-oriented ({@code PROJECT_DIR …}, {@code JAR …}, {@code REPO_URL …},
 * {@code SIGN_GPG …}, …); the reply is {@value #PREFIX}-prefixed NDJSON terminating in
 * {@code {"t":"result","ok":true,"files":N}} (or {@code "ok":false,"error":…}). Exit 0 success,
 * 1 publish error, 2 bad arguments.
 */
public final class Publisher implements Plugin, PublishExtension {

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
            System.err.println("jk-publisher: spec file not found: " + specFile);
            return 2;
        }
        PluginSpec spec;
        try {
            spec = PluginSpec.read(specFile);
        } catch (IOException e) {
            System.err.println("jk-publisher: could not read spec: " + e.getMessage());
            return 2;
        }

        try {
            PublishResult result = publish(new SpecPublishContext(spec, out));
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("files", result.files());
            if (result.dryRun()) fields.put("dry_run", true);
            out.emit(PluginReply.result(fields));
            out.emit(PluginReply.done(0));
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.emit(PluginReply.error("publish", e.getMessage()));
            return 1;
        } catch (Exception e) {
            out.emit(PluginReply.error("publish", e.getMessage()));
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

    /** The generic terminal context assembled from the plugin spec. */
    private record SpecPublishContext(PluginSpec spec, ProtocolWriter out) implements PublishContext {
        @Override
        public PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Path moduleDir() {
            return spec.moduleDir();
        }

        @Override
        public Optional<Path> mainArtifact() {
            return Optional.ofNullable(spec.artifactPath());
        }

        @Override
        public List<PackageIo.RuntimeEntry> runtimeEntries() {
            return spec.entries();
        }

        @Override
        public Path javaHome() {
            return spec.javaHome();
        }

        @Override
        public Optional<String> secret(String key) {
            return spec.secret(key);
        }

        @Override
        public void label(String text) {
            out.emit(PluginReply.label(text));
        }
    }
}
