// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.forge;

import build.jumpkick.util.GitUrl;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort detection of which forge a project lives on, by reading its git {@code origin} remote
 * and mapping the host to a {@link ForgeKind}. Mirrors the file-walking approach of {@code
 * NewGroupGuess} (which reads {@code .gitconfig} for {@code jk new}) — no shell-out, no live git
 * needed, so it stays unit-testable.
 *
 * <p>SSH host aliases are resolved through {@code ~/.ssh/config}: a remote like {@code
 * git@work-gh:org/repo} whose {@code Host work-gh} block sets {@code HostName github.com} is
 * detected as GitHub. Only well-known public hosts map to a provider (see {@link
 * ForgeKind#inferFromHost}); a self-hosted instance on an unrecognised domain yields empty, and the
 * caller asks the user to name the provider explicitly.
 */
public final class GitForgeDetector {

    // [remote "origin"]  — section with a quoted subsection.
    private static final Pattern SECTION = Pattern.compile("^\\[(\\S+)(?:\\s+\"([^\"]*)\")?\\]$");
    // key = value
    private static final Pattern KEY_VALUE = Pattern.compile("^(\\w[\\w-]*)\\s*=\\s*(.*)$");
    // ssh_config keyword + value, e.g. "HostName github.com" (also "key=value").
    private static final Pattern SSH_LINE = Pattern.compile("^(\\S+)[\\s=]+(.+)$");

    private GitForgeDetector() {}

    /** Detect using the working dir and the real {@code ~/.ssh/config}. */
    public static Optional<ForgeRemote> detect(Path workingDir) {
        Path sshConfig = Optional.ofNullable(System.getProperty("user.home"))
                .map(h -> Path.of(h, ".ssh", "config"))
                .orElse(null);
        return detect(workingDir, sshConfig);
    }

    /** Package-private seam: caller supplies the ssh-config path (for tests). */
    static Optional<ForgeRemote> detect(Path workingDir, Path sshConfig) {
        Optional<String> url = readOriginUrl(workingDir);
        if (url.isEmpty()) return Optional.empty();

        Optional<String> host = hostOf(url.get());
        if (host.isEmpty()) return Optional.empty();

        String realHost = resolveSshAlias(host.get(), sshConfig).orElse(host.get());
        return ForgeKind.inferFromHost(realHost).map(kind -> new ForgeRemote(kind, realHost));
    }

    // -- git remote ------------------------------------------------------

    /** Walk up from {@code startDir} for a git dir and read {@code remote.origin.url}. */
    static Optional<String> readOriginUrl(Path startDir) {
        Path config = findGitConfig(startDir);
        if (config == null) return Optional.empty();

        try {
            String section = null;
            String subsection = null;
            for (String raw : Files.readAllLines(config)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                Matcher sm = SECTION.matcher(line);
                if (sm.matches()) {
                    section = sm.group(1).toLowerCase(Locale.ROOT);
                    subsection = sm.group(2); // case-sensitive per git
                    continue;
                }
                if (!"remote".equals(section) || !"origin".equals(subsection)) continue;
                Matcher kv = KEY_VALUE.matcher(line);
                if (kv.matches() && kv.group(1).equalsIgnoreCase("url")) {
                    String value = kv.group(2).trim();
                    if (!value.isEmpty()) return Optional.of(value);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return Optional.empty();
    }

    /**
     * Locate the git config file by walking up from {@code startDir}. Handles the normal {@code
     * .git/} directory and the {@code .git} file form (worktrees / submodules) that points at the
     * real git dir.
     */
    private static Path findGitConfig(Path startDir) {
        if (startDir == null) return null;
        for (Path p = startDir.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            Path dotGit = p.resolve(".git");
            if (Files.isDirectory(dotGit)) {
                Path config = dotGit.resolve("config");
                if (Files.isRegularFile(config)) return config;
            } else if (Files.isRegularFile(dotGit)) {
                Path config = resolveGitFile(dotGit);
                if (config != null) return config;
            }
        }
        return null;
    }

    /** Follow a {@code .git} file's {@code gitdir: <path>} pointer to its config. */
    private static Path resolveGitFile(Path dotGitFile) {
        try {
            for (String raw : Files.readAllLines(dotGitFile)) {
                String line = raw.trim();
                if (line.startsWith("gitdir:")) {
                    Path gitDir = dotGitFile
                            .getParent()
                            .resolve(line.substring("gitdir:".length()).trim());
                    Path config = gitDir.normalize().resolve("config");
                    return Files.isRegularFile(config) ? config : null;
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return null;
    }

    // -- url → host ------------------------------------------------------

    /** Extract the bare host from a git remote URL (https, ssh, or scp form). */
    static Optional<String> hostOf(String remoteUrl) {
        try {
            // GitUrl.canonicalize normalises scp-form (git@host:path) to ssh://,
            // so URI.getHost() works uniformly across remote URL shapes.
            URI uri = URI.create(GitUrl.canonicalize(remoteUrl));
            String host = uri.getHost();
            return (host == null || host.isBlank()) ? Optional.empty() : Optional.of(host.toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // -- ~/.ssh/config alias resolution ----------------------------------

    /**
     * If {@code host} is an alias defined by a {@code Host} block in {@code sshConfig}, return its
     * {@code HostName}. Exact (case-insensitive) pattern match only — wildcard patterns are ignored,
     * so detection stays predictable. Empty when the file is missing or has no matching alias.
     */
    static Optional<String> resolveSshAlias(String host, Path sshConfig) {
        if (sshConfig == null || !Files.isRegularFile(sshConfig)) return Optional.empty();
        try {
            boolean inMatchingBlock = false;
            for (String raw : Files.readAllLines(sshConfig)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Matcher m = SSH_LINE.matcher(line);
                if (!m.matches()) continue;
                String keyword = m.group(1).toLowerCase(Locale.ROOT);
                String value = m.group(2).trim();

                if (keyword.equals("host")) {
                    inMatchingBlock = false;
                    for (String pattern : value.split("\\s+")) {
                        if (pattern.equalsIgnoreCase(host)) { // exact alias, no globs
                            inMatchingBlock = true;
                            break;
                        }
                    }
                } else if (inMatchingBlock && keyword.equals("hostname")) {
                    return value.isBlank() ? Optional.empty() : Optional.of(value.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return Optional.empty();
    }
}
