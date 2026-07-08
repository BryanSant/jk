// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.repo.RepoCredentialResolver;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.PublishGoals;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk publish} — assembles, signs, and uploads Maven artifacts via the {@code
 * jk-publish-runner} worker subprocess (PRD §21). BouncyCastle, sigstore-java, and the upload HTTP
 * logic live in the worker, not in the main jk binary.
 *
 * <p><b>Engine-hosted</b> (Wave 2 of the slim-client migration): the worker forks inside the
 * resident engine ({@link dev.jkbuild.cli.engine.EngineClient#runPublish}). Interactivity audit:
 * this command resolves everything env/keychain-shaped <em>client-side</em> — the repository
 * credential ({@code PUBLISH_USER}/{@code PUBLISH_PASSWORD}, keychain, inline repo credential) and
 * the GPG passphrase ({@code --key-passphrase} / {@code JK_GPG_PASSPHRASE}) — and passes them in
 * the request over the user-owned socket, because the engine's environment belongs to whichever
 * invocation first spawned it. SNAPSHOT versions are refused unless {@code --allow-snapshot} is set
 * (PRD §21.4). The goal machinery lives in {@link PublishGoals} so the test-only in-process path
 * (see {@link #engineDisabledForTests}) builds the identical goal.
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

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk publish} invocation always
     * engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

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
            CliOutput.err("jk publish: " + jkBuildPath + " not found.");
            return Exit.NO_INPUT;
        }
        if (sign && keyFile == null) {
            CliOutput.err("jk publish: --sign requires --key-file <path>.");
            return Exit.USAGE;
        }
        Path cache = JkDirs.cache();

        // Client-side parse: the credential match (declared repositories) and the summary line need
        // the project; the goal's own parse-build phase re-parses for validation either way.
        JkBuild project;
        try {
            project = JkBuildParser.parse(jkBuildPath);
        } catch (RuntimeException e) {
            CliOutput.err("jk publish: " + e.getMessage());
            return Exit.CONFIG;
        }

        // Resolve everything env/keychain-shaped here — never inside the engine.
        RepoCredential cred = resolvePublishCredential(project);
        String gpgPass =
                sign ? (keyPassphrase != null ? keyPassphrase : System.getenv("JK_GPG_PASSPHRASE")) : null;

        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        GoalResult result;
        int files;
        if (engineDisabledForTests()) {
            Goal goal = PublishGoals.publishGoal(
                    projectDir,
                    cache,
                    new PublishGoals.Request(
                            repoUrl,
                            region,
                            endpoint,
                            jarPath,
                            allowSnapshot,
                            dryRun,
                            sign ? keyFile : null,
                            gpgPass,
                            sigstore,
                            slsa,
                            sbom,
                            cred));
            result = GoalConsole.run(goal, mode, cache);
            files = goal.get(PublishGoals.FILES).orElse(0);
        } else {
            dev.jkbuild.cli.engine.EngineClient.PublishOutcome outcome;
            try {
                outcome = dev.jkbuild.cli.engine.EngineClient.runPublish(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.PublishRequest(
                                projectDir,
                                cache,
                                repoUrl,
                                region,
                                endpoint,
                                jarPath,
                                allowSnapshot,
                                dryRun,
                                sign ? keyFile : null,
                                gpgPass,
                                sigstore,
                                slsa,
                                sbom,
                                cred,
                                global.verbose),
                        phases -> GoalConsole.chooseConsoleListener("publish", phases, mode));
            } catch (IOException e) {
                CliOutput.err("jk publish: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            result = outcome.result();
            files = outcome.files();
        }

        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("snapshot".equals(d.code())) return Exit.DATA_ERR;
                if ("missing-jar".equals(d.code())) return Exit.NO_INPUT;
            }
            return 1;
        }

        if (!global.outputIsJson()) {
            String summary = dryRun ? "(dry-run)" : "(" + files + " files)";
            CliOutput.out("Published "
                    + Coords.gav(
                            project.project().group(),
                            project.project().name(),
                            project.project().version())
                    + " " + summary);
        }
        return 0;
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
}
