// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk update-shell} — append a line to the user's shell rc file
 * so {@code ~/.jk/bin} (where {@code jk install} and {@code jk tool install}
 * write launchers) ends up on the {@code PATH}.
 *
 * <p>Modelled after {@code uv python update-shell}. Idempotent: re-runs are
 * a no-op once the line is present. Detects the shell from {@code $SHELL}
 * (overridable via {@code --shell} for tests):
 *
 * <ul>
 *   <li><b>bash</b> → {@code ~/.bashrc}</li>
 *   <li><b>zsh</b>  → {@code ~/.zshenv} (loaded for every zsh shell, not
 *       only interactive ones — matches uv's choice)</li>
 *   <li><b>fish</b> → {@code ~/.config/fish/conf.d/jk.fish} (uses
 *       {@code fish_add_path} so the user's existing path management
 *       conventions are honored)</li>
 * </ul>
 */
@Command(name = "update-shell",
        description = "Ensure that the jk executable directory is on the `PATH`")
public final class JdkUpdateShellCommand implements Callable<Integer> {

    /** Sentinel comment so we can detect prior writes idempotently. */
    static final String MARKER = "# Added by `jk jdk update-shell`";

    @Option(names = "--shell",
            description = "Force a specific shell: bash | zsh | fish. "
                    + "Default: detected from $SHELL.")
    String shellOverride;

    @Option(names = "--home", hidden = true,
            description = "Override the user home root (for tests). Default: $HOME.")
    Path homeOverride;

    @Override
    public Integer call() throws IOException {
        Path home = homeOverride != null
                ? homeOverride : Path.of(System.getProperty("user.home"));
        Shell shell = detectShell(shellOverride);
        if (shell == null) {
            System.err.println("jk jdk update-shell: could not detect shell (set --shell, "
                    + "or add `" + bashLine(home) + "` to your rc file manually).");
            return 64; // EX_USAGE
        }

        Path rcFile = shell.rcFile(home);
        String addition = shell.exportLine(home);
        // Already present? Match either the export line or our marker so the
        // user's hand-edits don't get duplicated.
        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (existing.contains(MARKER) || existing.contains(addition)) {
                System.out.println(rcFile + ": already on PATH");
                return 0;
            }
        } else {
            Files.createDirectories(rcFile.getParent());
        }

        String toAppend = "\n" + MARKER + "\n" + addition + "\n";
        Files.writeString(rcFile, toAppend, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        System.out.println("Updated " + rcFile);
        System.out.println("Restart your shell or run: source " + rcFile);
        return 0;
    }

    static Shell detectShell(String override) {
        String raw = override != null ? override : System.getenv("SHELL");
        if (raw == null || raw.isBlank()) return null;
        // Strip path: /bin/bash → bash.
        int slash = raw.lastIndexOf('/');
        String name = (slash >= 0 ? raw.substring(slash + 1) : raw).toLowerCase(Locale.ROOT);
        return switch (name) {
            case "bash" -> Shell.BASH;
            case "zsh" -> Shell.ZSH;
            case "fish" -> Shell.FISH;
            default -> null;
        };
    }

    private static String bashLine(Path home) {
        return "export PATH=\"" + home + "/.jk/bin:$PATH\"";
    }

    enum Shell {
        BASH {
            @Override
            Path rcFile(Path home) { return home.resolve(".bashrc"); }
            @Override
            String exportLine(Path home) { return bashLine(home); }
        },
        ZSH {
            @Override
            // .zshenv is loaded for every zsh shell, not just interactive
            // ones — so non-interactive subprocesses also see the PATH.
            Path rcFile(Path home) { return home.resolve(".zshenv"); }
            @Override
            String exportLine(Path home) { return bashLine(home); }
        },
        FISH {
            @Override
            Path rcFile(Path home) {
                return home.resolve(".config").resolve("fish").resolve("conf.d").resolve("jk.fish");
            }
            @Override
            String exportLine(Path home) {
                return "fish_add_path \"" + home + "/.jk/bin\"";
            }
        };

        abstract Path rcFile(Path home);
        abstract String exportLine(Path home);

        private static String bashLine(Path home) {
            return "export PATH=\"" + home + "/.jk/bin:$PATH\"";
        }
    }
}
