// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish.runner;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.ObjectStoreConfig;
import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.plugin.protocol.ProtocolWriter;
import dev.jkbuild.publish.Checksums;
import dev.jkbuild.publish.GpgSigner;
import dev.jkbuild.publish.KeylessSigstoreSigner;
import dev.jkbuild.publish.MavenPublisher;
import dev.jkbuild.publish.PublishablePom;
import dev.jkbuild.publish.Sbom;
import dev.jkbuild.publish.SigningOptions;
import dev.jkbuild.publish.SigstoreSigner;
import dev.jkbuild.publish.SlsaProvenance;
import dev.jkbuild.publish.SourcesJar;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point for the {@code jk-publish-runner} worker subprocess.
 *
 * <p>Receives a single argument: the path to a line-oriented spec file:
 * <pre>
 * PROJECT_DIR /absolute/path/to/project
 * JAR         /absolute/path/to/app-1.0.jar
 * REPO_URL    https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/
 * REPO_AUTH_TYPE  basic|bearer|anonymous
 * REPO_USER   username          # basic only
 * REPO_PASS   password          # basic only
 * REPO_TOKEN  token             # bearer only
 * DRY_RUN     false
 * NO_SOURCES  false
 * ALLOW_SNAPSHOT false
 * SLSA        false
 * SBOM        false
 * SIGN_GPG         /path/to/key.asc   # omit if not signing with GPG
 * SIGN_GPG_PASS    passphrase          # omit if not signing with GPG
 * SIGN_SIGSTORE    false
 * OBJECT_STORE_REGION  us-east-1       # optional
 * OBJECT_STORE_ENDPOINT https://...    # optional
 * </pre>
 *
 * <p>Streams {@value #PREFIX}-prefixed NDJSON to stdout:
 * <pre>
 * ##JKPU:{"t":"artifact","name":"app-1.0.jar","size":12345}
 * ##JKPU:{"t":"upload","name":"app-1.0.jar","status":200}
 * ##JKPU:{"t":"result","ok":true,"files":8}
 * ##JKPU:{"t":"result","ok":false,"error":"HTTP 401 Unauthorized"}
 * </pre>
 *
 * <p>Exit codes: 0 success, 1 publish error, 2 bad arguments.
 */
public final class PublishRunner implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-publish-runner", "##JKPU:");
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
        URI repoUrl = null;
        String repoAuthType = "anonymous", repoUser = null, repoPass = null, repoToken = null;
        boolean dryRun = false, noSources = false, slsa = false, sbom = false;
        boolean signGpg = false, signSigstore = false;
        Path gpgKeyFile = null;
        String gpgPassphrase = null;
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
                    case "PROJECT_DIR"           -> projectDir = Path.of(val);
                    case "JAR"                   -> jar = Path.of(val);
                    case "REPO_URL"              -> repoUrl = URI.create(val);
                    case "REPO_AUTH_TYPE"        -> repoAuthType = val;
                    case "REPO_USER"             -> repoUser = val;
                    case "REPO_PASS"             -> repoPass = val;
                    case "REPO_TOKEN"            -> repoToken = val;
                    case "DRY_RUN"               -> dryRun = "true".equalsIgnoreCase(val);
                    case "NO_SOURCES"            -> noSources = "true".equalsIgnoreCase(val);
                    case "SLSA"                  -> slsa = "true".equalsIgnoreCase(val);
                    case "SBOM"                  -> sbom = "true".equalsIgnoreCase(val);
                    case "SIGN_GPG"              -> { signGpg = true; gpgKeyFile = Path.of(val); }
                    case "SIGN_GPG_PASS"         -> gpgPassphrase = val;
                    case "SIGN_SIGSTORE"         -> signSigstore = "true".equalsIgnoreCase(val);
                    case "OBJECT_STORE_REGION"   -> osRegion = val;
                    case "OBJECT_STORE_ENDPOINT" -> osEndpoint = val;
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

        // Read project.
        JkBuild project;
        try {
            project = JkBuildParser.parse(projectDir.resolve("jk.toml"));
        } catch (IOException e) {
            System.err.println("jk-publish-runner: could not read jk.toml: " + e.getMessage());
            return 1;
        }

        // Assemble artifacts.
        List<MavenPublisher.Artifact> artifacts = new ArrayList<>();
        try {
            byte[] jarBytes = Files.readAllBytes(jar);
            artifacts.add(new MavenPublisher.Artifact(".jar", jarBytes));
            out.emit("{\"t\":\"artifact\",\"name\":" + Ndjson.quote(jar.getFileName().toString())
                    + ",\"size\":" + jarBytes.length + "}");

            PublishablePom.Pom pom = PublishablePom.render(project, PublishablePom.Metadata.empty());
            byte[] pomBytes = pom.xml().getBytes(StandardCharsets.UTF_8);
            artifacts.add(new MavenPublisher.Artifact(".pom", pomBytes));
            out.emit("{\"t\":\"artifact\",\"name\":" + Ndjson.quote(project.project().name()
                    + "-" + project.project().version() + ".pom")
                    + ",\"size\":" + pomBytes.length + "}");

            if (!noSources) {
                Path srcRoot = projectDir.resolve("src/main/java");
                byte[] sourcesJar = SourcesJar.build(List.of(srcRoot));
                artifacts.add(new MavenPublisher.Artifact("-sources.jar", sourcesJar));
                out.emit("{\"t\":\"artifact\",\"name\":" + Ndjson.quote(project.project().name()
                        + "-" + project.project().version() + "-sources.jar")
                        + ",\"size\":" + sourcesJar.length + "}");
            }

            if (slsa) {
                String jarFilename = jar.getFileName().toString();
                SlsaProvenance.BuildContext ctx = new SlsaProvenance.BuildContext(
                        "https://github.com/buildjk/jk",
                        "https://buildjk.dev/jk-build/v1",
                        UUID.randomUUID().toString(),
                        Instant.now(), Instant.now(),
                        Map.of("configRef", "jk.toml"),
                        Map.of("group", project.project().group(),
                                "artifact", project.project().name(),
                                "version", project.project().version(),
                                "jdk", String.valueOf(project.project().jdk())));
                byte[] provenance = SlsaProvenance.generate(
                        List.of(new SlsaProvenance.Subject(jarFilename, Checksums.sha256Hex(jarBytes))), ctx);
                artifacts.add(new MavenPublisher.Artifact(".intoto.json", provenance));
                out.emit("{\"t\":\"artifact\",\"name\":" + Ndjson.quote(project.project().name()
                        + "-" + project.project().version() + ".intoto.json")
                        + ",\"size\":" + provenance.length + "}");
            }

            if (sbom) {
                Path lockPath = projectDir.resolve("jk.lock");
                Lockfile lock = Files.exists(lockPath) ? LockfileReader.read(lockPath) : null;
                byte[] cdx = Sbom.cyclonedx(project, lock);
                byte[] spdxBytes = Sbom.spdx(project, lock);
                artifacts.add(new MavenPublisher.Artifact("-cyclonedx.json", cdx));
                artifacts.add(new MavenPublisher.Artifact("-spdx.json", spdxBytes));
                out.emit("{\"t\":\"artifact\",\"name\":\"cyclonedx+spdx\",\"size\":"
                        + (cdx.length + spdxBytes.length) + "}");
            }
        } catch (IOException e) {
            System.err.println("jk-publish-runner: artifact assembly failed: " + e.getMessage());
            return 1;
        }

        if (dryRun) {
            out.emit("{\"t\":\"result\",\"ok\":true,\"dry_run\":true,\"files\":"
                    + artifacts.size() + "}");
            return 0;
        }

        // Load signing.
        SigningOptions signing;
        try {
            GpgSigner gpg = signGpg
                    ? GpgSigner.fromKeyFile(gpgKeyFile,
                            gpgPassphrase != null ? gpgPassphrase.toCharArray() : new char[0])
                    : null;
            SigstoreSigner sigstoreSigner = signSigstore
                    ? KeylessSigstoreSigner.sigstorePublic()
                    : null;
            signing = new SigningOptions(gpg, sigstoreSigner);
        } catch (IOException e) {
            System.err.println("jk-publish-runner: signing setup failed: " + e.getMessage());
            return 1;
        }

        // Upload.
        try {
            RepoCredential cred = switch (repoAuthType.toLowerCase()) {
                case "basic"   -> new RepoCredential.Basic(repoUser != null ? repoUser : "",
                                          repoPass != null ? repoPass : "");
                case "bearer"  -> new RepoCredential.Bearer(repoToken != null ? repoToken : "");
                default        -> new RepoCredential.Anonymous();
            };
            ObjectStoreConfig objectStore = new ObjectStoreConfig(
                    blankToNull(osRegion), blankToNull(osEndpoint), null, null, null);
            MavenPublisher publisher = MavenPublisher.withObjectStore(repoUrl, cred, objectStore);

            MavenPublisher.Result result = publisher.publish(project.project(), artifacts, signing);
            for (Map.Entry<String, Integer> e : result.statusByPath().entrySet()) {
                out.emit("{\"t\":\"upload\",\"name\":" + Ndjson.quote(e.getKey())
                        + ",\"status\":" + e.getValue() + "}");
            }

            if (!result.allOk()) {
                out.emit("{\"t\":\"result\",\"ok\":false,\"error\":\"partial upload failure\"}");
                return 1;
            }
            out.emit("{\"t\":\"result\",\"ok\":true,\"files\":"
                    + result.statusByPath().size() + "}");
            return 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            out.emit("{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        } finally {
            if (signing != null && signing.sigstore() instanceof AutoCloseable c) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

}
