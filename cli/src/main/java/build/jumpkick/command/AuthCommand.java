// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.forge.CliTokenProbe;
import build.jumpkick.forge.ForgeAuth;
import build.jumpkick.forge.ForgeKind;
import build.jumpkick.forge.ForgeRemote;
import build.jumpkick.forge.GitForgeDetector;
import build.jumpkick.forge.TokenStore;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.GroupCommand;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk auth} parent command — authenticate jk against a git forge so it can call that forge's
 * API.
 */
public final class AuthCommand extends GroupCommand {

    @Override
    public String name() {
        return "auth";
    }

    @Override
    public String description() {
        return "Authenticate with a git forge (GitHub, GitLab, etc.)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new AuthLoginCommand(), new AuthLogoutCommand(), new AuthStatusCommand(), new AuthTokenCommand());
    }

    /** Resolve a provider id to a {@link ForgeKind}, throwing on unknown. */
    static ForgeKind requireKind(String raw) {
        return ForgeKind.fromId(raw)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider '"
                        + raw
                        + "'. Expected one of: github, gitlab, gitea (forgejo/codeberg), bitbucket."));
    }

    static ForgeAuth authFor(Path credentialsDir) {
        return credentialsDir != null
                ? new ForgeAuth(new TokenStore(credentialsDir), System::getenv, CliTokenProbe.REAL)
                : new ForgeAuth();
    }

    record Target(ForgeKind kind, String host) {}

    static Target resolveTarget(String provider, String host, Path workingDir) {
        if (provider != null) return new Target(requireKind(provider), host);
        ForgeRemote detected = GitForgeDetector.detect(workingDir)
                .orElseThrow(() -> new IllegalArgumentException("Could not detect a forge from this repo's git remote. "
                        + "Name the provider explicitly (e.g. `github`)."));
        return new Target(detected.kind(), host != null ? host : detected.host());
    }
}
