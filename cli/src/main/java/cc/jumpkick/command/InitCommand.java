// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk init} — initialize a jk project in the current directory. Delegates to {@link
 * NewCommand} with directory pinned to {@code "."}.
 */
public final class InitCommand implements CliCommand {

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String description() {
        return "Initialize the current directory into a project (or module)";
    }

    @Override
    public List<Opt> options() {
        // Same options as NewCommand (minus the positional directory parameter)
        return new NewCommand().options();
    }

    @Override
    public int run(Invocation in) throws IOException {
        // Delegate to NewCommand with directory pinned to "." so its target-
        // resolution, wizard-preset, and existing-project logic all fire
        // correctly against the current directory. NewCommand's own
        // emitProjectExistsError() handles the already-exists case.
        NewCommand delegate = new NewCommand();
        delegate.name = in.value("name").orElse(null);
        delegate.group = in.value("group").orElse(null);
        delegate.jdk = in.value("jdk").orElse(null);
        delegate.lang = in.value("lang").orElse(null);
        delegate.executable = in.flag("executable").orElse(null);
        delegate.shadow = in.isSet("shadow");
        delegate.nativeImage = in.isSet("native");
        delegate.depsCsv = in.value("deps").orElse(null);
        delegate.layoutFlag = in.value("layout").orElse(null);
        delegate.kotlinModule = in.value("kotlin-module").orElse(null);
        delegate.noModule = in.isSet("no-module");
        delegate.directory = Path.of(".");
        delegate.global = GlobalOptions.from(in);
        return delegate.callBody();
    }

}
