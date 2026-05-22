// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.model.BuildJk;
import dev.buildjk.publish.GpgSigner;
import dev.buildjk.publish.KeylessSigstoreSigner;
import dev.buildjk.publish.MavenPublisher;
import dev.buildjk.publish.PublishablePom;
import dev.buildjk.publish.SigningOptions;
import dev.buildjk.publish.SigstoreSigner;
import dev.buildjk.publish.SourcesJar;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk publish} — first cut (PRD §21). Assembles the publish bundle
 * (main jar, generated POM, sources jar + four checksum files each) and
 * uploads it to a Maven HTTP repository.
 *
 * <p>v0.6 slice C-1 limitations: no GPG signing, no Sigstore, no SLSA
 * attestation, no SBOM, no Maven Central staging. Those layer on in
 * follow-up sub-slices. SNAPSHOT versions are refused per PRD §21.4 unless
 * {@code --allow-snapshot} is set.
 */
@Command(name = "publish", description = "Publish artifacts to a Maven repository.")
public final class PublishCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory containing build.jk. Default: current directory.")
    Path directory;

    @Option(names = "--repo-url", required = true,
            description = "Target Maven repository base URL.")
    URI repoUrl;

    @Option(names = "--user", description = "HTTP Basic auth username (or via PUBLISH_USER env).")
    String username;

    @Option(names = "--password", description = "HTTP Basic auth password (or via PUBLISH_PASSWORD env).",
            interactive = false, arity = "0..1")
    String password;

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

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory.toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();
        Path buildJkPath = projectDir.resolve("build.jk");
        if (!Files.exists(buildJkPath)) {
            System.err.println("jk publish: " + buildJkPath + " not found.");
            return 66; // EX_NOINPUT
        }
        BuildJk project = BuildJkParser.parse(buildJkPath);

        if (project.project().version().endsWith("-SNAPSHOT") && !allowSnapshot) {
            System.err.println("jk publish: refusing to publish a SNAPSHOT version "
                    + "(use --allow-snapshot, or rename to -dev.N / -rc.N per PRD §21.4).");
            return 65; // EX_DATAERR
        }

        Path jar = jarPath != null ? jarPath
                : projectDir.resolve("target").resolve(
                        project.project().artifact() + "-" + project.project().version() + ".jar");
        if (!Files.exists(jar)) {
            System.err.println("jk publish: main jar not found at " + jar
                    + " — run `jk build` first or pass --jar.");
            return 66;
        }

        List<MavenPublisher.Artifact> artifacts = new ArrayList<>();
        artifacts.add(new MavenPublisher.Artifact(".jar", Files.readAllBytes(jar)));

        PublishablePom.Pom pom = PublishablePom.render(project, PublishablePom.Metadata.empty());
        artifacts.add(new MavenPublisher.Artifact(".pom",
                pom.xml().getBytes(StandardCharsets.UTF_8)));

        if (!noSources) {
            Path srcRoot = projectDir.resolve("src/main/java");
            byte[] sourcesJar = SourcesJar.build(List.of(srcRoot));
            artifacts.add(new MavenPublisher.Artifact("-sources.jar", sourcesJar));
        }

        String user = username != null ? username : System.getenv("PUBLISH_USER");
        String pass = password != null ? password : System.getenv("PUBLISH_PASSWORD");

        if (dryRun) {
            String groupPath = project.project().group().replace('.', '/');
            String prefix = repoUrl + (repoUrl.toString().endsWith("/") ? "" : "/")
                    + groupPath + "/" + project.project().artifact() + "/"
                    + project.project().version() + "/";
            System.out.println("Would PUT to " + prefix + ":");
            for (MavenPublisher.Artifact a : artifacts) {
                String name = project.project().artifact() + "-"
                        + project.project().version() + a.filenameSuffix();
                System.out.println("  " + name + " (" + a.body().length + " bytes)");
                System.out.println("  " + name + ".md5 / .sha1 / .sha256 / .sha512");
                if (sign) System.out.println("  " + name + ".asc + four checksums");
                if (sigstore) System.out.println("  " + name + ".sigstore + four checksums");
            }
            return 0;
        }

        SigningOptions signing = buildSigningOptions();
        try {
            MavenPublisher publisher = new MavenPublisher(repoUrl, user, pass);
            MavenPublisher.Result result = publisher.publish(project.project(), artifacts, signing);
            System.out.println("Published " + project.project().group() + ":"
                    + project.project().artifact() + ":" + project.project().version()
                    + " (" + result.statusByPath().size() + " files"
                    + (signing.isNoop() ? "" : ", signed") + ")");
            return result.allOk() ? 0 : 1;
        } finally {
            closeSigningOptions(signing);
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

    private static void closeSigningOptions(SigningOptions signing) {
        if (signing.sigstore() instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) { /* best effort */ }
        }
    }
}
