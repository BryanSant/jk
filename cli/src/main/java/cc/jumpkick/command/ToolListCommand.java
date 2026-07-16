// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** {@code jk tool list} — print every CLI tool installed via {@code jk tool install}. */
public final class ToolListCommand implements CliCommand {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String description() {
        return "List installed CLI tools";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tool state directory. Default: $JK_STATE_DIR.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory. Default: $JK_BIN_DIR or ~/.jk/bin.", "--bin-dir")
                        .hide());
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path stateDir = in.value("state-dir").map(Path::of).orElse(null);
        Path binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);
        Path state = stateDir != null ? stateDir : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = state.resolve("tools").resolve("envs");
        if (!Files.isDirectory(envsRoot)) {
            CliOutput.out("No tools installed. Try `jk tool install <coord>`.");
            return 0;
        }
        List<Path> envs = new ArrayList<>();
        try (var stream = Files.list(envsRoot)) {
            stream.filter(Files::isDirectory).forEach(envs::add);
        }
        if (envs.isEmpty()) {
            CliOutput.out("No tools installed. Try `jk tool install <coord>`.");
            return 0;
        }
        envs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        Theme t = Theme.active();
        for (Path envDir : envs) {
            String bin = envDir.getFileName().toString();
            Path envJson = envDir.resolve("env.json");
            String coord = readField(envJson, "primary").orElse("(unknown coord)");
            Path launcher = binDir.resolve(bin);
            CliOutput.stdout().printf("%-24s %s%n", Theme.colorize(bin, t.cyan()), coord);
            // Provenance: how this tool was installed (kind + the spec the user typed).
            Optional<String> kind = readField(envJson, "kind");
            Optional<String> spec = readField(envJson, "spec");
            if (kind.isPresent() && spec.isPresent() && !spec.get().equals(coord)) {
                CliOutput.stdout()
                        .printf("%-24s %s%n", "", Theme.colorize(kind.get() + " " + spec.get(), t.darkGray()));
            }
            if (Files.exists(launcher))
                CliOutput.stdout()
                        .printf(
                                "%-24s %s %s%n",
                                "", Theme.colorize("→", t.darkGray()), Theme.colorize(launcher.toString(), t.path()));
        }
        return 0;
    }

    private static Optional<String> readField(Path envJson, String field) {
        if (!Files.exists(envJson)) return Optional.empty();
        try {
            String body = Files.readString(envJson);
            int i = body.indexOf("\"" + field + "\"");
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
