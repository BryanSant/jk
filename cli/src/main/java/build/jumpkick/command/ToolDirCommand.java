// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.List;

/** {@code jk tool dir} — print the tools install root. */
public final class ToolDirCommand implements CliCommand {

    @Override
    public String name() {
        return "dir";
    }

    @Override
    public String description() {
        return "Print the tools install root";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root. Default: $JK_CACHE_DIR/tools.", "--tools-dir")
                        .hide());
    }

    @Override
    public int run(Invocation in) {
        Path toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        CliOutput.out(String.valueOf(toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools")));
        return 0;
    }
}
