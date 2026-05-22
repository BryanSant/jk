// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk tool list} — print every CLI tool installed via
 * {@code jk tool install}.
 *
 * <p>Reads {@code ~/.jk/tools/envs/<bin>/env.json}; each directory under
 * {@code envs/} is one installed tool. The launcher path
 * ({@code ~/.jk/bin/<bin>}) is shown alongside so users can copy-paste
 * into shell config.
 */
@Command(name = "list", description = "List installed CLI tools")
public final class ToolListCommand implements Callable<Integer> {

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Override
    public Integer call() throws IOException {
        Path jkHome = home != null
                ? home : Path.of(System.getProperty("user.home"), ".jk");
        Path envsRoot = jkHome.resolve("tools").resolve("envs");
        Path binDir = jkHome.resolve("bin");
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
    private static java.util.Optional<String> readCoord(Path envJson) {
        if (!Files.exists(envJson)) return java.util.Optional.empty();
        try {
            String body = Files.readString(envJson);
            int i = body.indexOf("\"primary\"");
            if (i < 0) return java.util.Optional.empty();
            int colon = body.indexOf(':', i);
            int q1 = body.indexOf('"', colon + 1);
            int q2 = body.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) return java.util.Optional.empty();
            return java.util.Optional.of(body.substring(q1 + 1, q2));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }
}
