// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.util.JkDirs;

import java.nio.file.Path;
import java.util.List;

/** {@code jk tool dir} — print the tools install root. */
public final class ToolDirCommand implements CliCommand {

    @Override public String name() { return "dir"; }
    @Override public String description() { return "Print the tools install root"; }

    @Override public List<Opt> options() {
        return List.of(Opt.value("<dir>", "Override the tools install root. Default: $JK_CACHE_DIR/tools.", "--tools-dir").hide());
    }

    @Override
    public int run(Invocation in) {
        Path toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        System.out.println(toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools"));
        return 0;
    }
}
