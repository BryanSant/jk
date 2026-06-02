// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk tool list} — print every CLI tool installed via
 * {@code jk tool install}.
 *
 * <p>Reads {@code $JK_STATE_DIR/tools/envs/<bin>/env.json}; each directory
 * under {@code envs/} is one installed tool. The launcher path
 * ({@code $JK_BIN_DIR/<bin>}) is shown alongside so users can copy-paste
 * into shell config.
 */
@Command(name = "list", description = "List installed CLI tools")
public final class ToolListCommand implements Callable<Integer> {

    @Option(names = "--state-dir", hidden = true,
            description = "Override the tool state directory. Default: $JK_STATE_DIR.")
    Path stateDir;

    @Option(names = "--bin-dir", hidden = true,
            description = "Override the bin directory. Default: $JK_BIN_DIR or ~/.local/bin.")
    Path binDirOverride;

    @Override
    public Integer call() throws IOException {
        Path state = stateDir != null ? stateDir : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = state.resolve("tools").resolve("envs");
        if (!Files.isDirectory(envsRoot)) {
            System.out.println("No tools installed. Try `jk tool install <coord>`.");
            return 0;
        }
        List<Path> envs = new ArrayList<>();
        try (var stream = Files.list(envsRoot)) {
            stream.filter(Files::isDirectory).forEach(envs::add);
        }
        if (envs.isEmpty()) {
            System.out.println("No tools installed. Try `jk tool install <coord>`.");
            return 0;
        }
        envs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path envDir : envs) {
            String bin = envDir.getFileName().toString();
            String coord = readCoord(envDir.resolve("env.json")).orElse("(unknown coord)");
            Path launcher = binDir.resolve(bin);
            System.out.printf("%-24s %s%n", bin, coord);
            if (Files.exists(launcher)) {
                System.out.printf("%-24s → %s%n", "", launcher);
            }
        }
        return 0;
    }

    /** Tiny inline reader: pull the "primary" field out of env.json without a JSON dep. */
    private static Optional<String> readCoord(Path envJson) {
        if (!Files.exists(envJson)) return Optional.empty();
        try {
            String body = Files.readString(envJson);
            int i = body.indexOf("\"primary\"");
            if (i < 0) return Optional.empty();
            int colon = body.indexOf(':', i);
            int q1 = body.indexOf('"', colon + 1);
            int q2 = body.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) return Optional.empty();
            return Optional.of(body.substring(q1 + 1, q2));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
