// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.forge.CliTokenProbe;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.forge.ForgeRemote;
import dev.jkbuild.forge.GitForgeDetector;
import dev.jkbuild.forge.TokenStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk auth} parent verb — authenticate jk against a git forge so it
 * can call that forge's API. Provider-neutral: the positional
 * {@code <provider>} selects the forge software (github / gitlab / gitea /
 * bitbucket) and {@code --host} the concrete instance. See
 * docs/forge-auth.md.
 *
 * <p>Subcommands mirror {@code gh auth}: {@code login} / {@code logout} /
 * {@code status} / {@code token}.
 */
@Command(name = "auth",
        description = "Authenticate with a git forge (GitHub, GitLab, Gitea/Forgejo, Bitbucket)",
        subcommands = {
                AuthLoginCommand.class,
                AuthLogoutCommand.class,
                AuthStatusCommand.class,
                AuthTokenCommand.class,
        })
public final class AuthCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 64;
    }

    /**
     * Resolve a positional {@code <provider>} to a {@link ForgeKind}, raising
     * a picocli usage error (exit 2) on an unknown id. Shared by the leaf
     * subcommands so the diagnostic is identical everywhere.
     */
    static ForgeKind requireKind(CommandSpec spec, String raw) {
        return ForgeKind.fromId(raw).orElseThrow(() -> new ParameterException(
                spec.commandLine(),
                "Unknown provider '" + raw + "'. Expected one of: "
                        + "github, gitlab, gitea (forgejo/codeberg), bitbucket."));
    }

    /**
     * Build a {@link ForgeAuth} against the real environment and native-CLI
     * probe. A non-null {@code credentialsDir} points the token store at an
     * override directory (hidden {@code --credentials-dir}, used by tests);
     * otherwise it defaults to {@code ~/.jk/credentials}.
     */
    static ForgeAuth authFor(Path credentialsDir) {
        return credentialsDir != null
                ? new ForgeAuth(new TokenStore(credentialsDir), System::getenv, CliTokenProbe.REAL)
                : new ForgeAuth();
    }

    /** A provider + (possibly null) host the caller will pass to {@link ForgeAuth#resolveHost}. */
    record Target(ForgeKind kind, String host) {}

    /**
     * Resolve the provider/host a command should act on. An explicit
     * {@code provider} wins; otherwise auto-detect from the git {@code origin}
     * remote in {@code workingDir} (mapping well-known hosts, resolving
     * {@code ~/.ssh/config} aliases). An explicit {@code --host} always
     * overrides the detected host. Raises a usage error (exit 2) when no
     * provider is given and none can be detected.
     */
    static Target resolveTarget(CommandSpec spec, String provider, String host, Path workingDir) {
        if (provider != null) {
            return new Target(requireKind(spec, provider), host);
        }
        ForgeRemote detected = GitForgeDetector.detect(workingDir).orElseThrow(() ->
                new ParameterException(spec.commandLine(),
                        "Could not detect a forge from this repo's git remote. "
                                + "Name the provider explicitly (e.g. `github`)."));
        return new Target(detected.kind(), host != null ? host : detected.host());
    }
}
