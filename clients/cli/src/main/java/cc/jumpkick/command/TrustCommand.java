// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.tool.TrustedPlugins;
import cc.jumpkick.tool.TrustedSources;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk trust} parent command — manage the URL prefixes allowed to supply runnable code for
 * {@code jk tool run|install} (docs/tool-targets-plan.md §7). Prefix-matched, JBang-style:
 * trusting {@code https://github.com/acme/} covers everything under it. The store is
 * {@code $JK_STATE_DIR/trusted-sources.toml}.
 */
public final class TrustCommand extends GroupCommand {

    @Override
    public String name() {
        return "trust";
    }

    @Override
    public String description() {
        return "Manage trusted sources for running remote code";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new AddCmd(), new PluginCmd(), new ListCmd(), new RemoveCmd(), new ImportCmd());
    }

    private static Path stateDir(Invocation in) {
        return in.value("state-dir").map(Path::of).orElseGet(JkDirs::state);
    }

    private static Opt stateDirOpt() {
        return Opt.value("<dir>", "Override the jk state directory.", "--state-dir")
                .hide();
    }

    static final class AddCmd implements CliCommand {
        @Override
        public String name() {
            return "add";
        }

        @Override
        public String description() {
            return "Trust a URL prefix (e.g. https://github.com/acme/)";
        }

        @Override
        public List<Opt> options() {
            return List.of(stateDirOpt());
        }

        @Override
        public List<Param> parameters() {
            return List.of(Param.of("prefix", Arity.ONE, "URL prefix to trust."));
        }

        @Override
        public int run(Invocation in) throws IOException {
            String prefix = in.positionals().get(0);
            if (!prefix.contains("://")) {
                CliOutput.err("jk trust add: not a URL prefix: " + prefix);
                return Exit.USAGE;
            }
            boolean added = TrustedSources.load(stateDir(in)).add(prefix);
            if (!GlobalOptions.from(in).outputIsJson()) {
                CliOutput.out(added ? "Trusted " + prefix : prefix + " is already trusted");
            }
            return 0;
        }
    }

    static final class PluginCmd implements CliCommand {
        @Override
        public String name() {
            return "plugin";
        }

        @Override
        public String description() {
            return "Trust a build plugin's coordinate (group:artifact, or group: for the whole group)";
        }

        @Override
        public List<Opt> options() {
            return List.of(stateDirOpt());
        }

        @Override
        public List<Param> parameters() {
            return List.of(Param.of("coordinate", Arity.ONE, "Plugin coordinate to trust."));
        }

        @Override
        public int run(Invocation in) throws IOException {
            String coordinate = in.positionals().get(0);
            if (coordinate.contains("://") || !coordinate.contains(":")) {
                CliOutput.err("jk trust plugin: expected group:artifact (or a group: prefix), got: " + coordinate);
                return Exit.USAGE;
            }
            boolean added = TrustedPlugins.load(stateDir(in)).add(coordinate);
            if (!GlobalOptions.from(in).outputIsJson()) {
                CliOutput.out(
                        added
                                ? "Trusted plugin " + coordinate + " — its build code may now run in worker JVMs"
                                : coordinate + " is already trusted");
            }
            return 0;
        }
    }

    static final class ListCmd implements CliCommand {
        @Override
        public String name() {
            return "list";
        }

        @Override
        public String description() {
            return "List trusted URL prefixes";
        }

        @Override
        public List<Opt> options() {
            return List.of(stateDirOpt());
        }

        @Override
        public int run(Invocation in) throws IOException {
            List<String> prefixes = TrustedSources.load(stateDir(in)).list();
            List<String> plugins = TrustedPlugins.load(stateDir(in)).list();
            if (prefixes.isEmpty() && plugins.isEmpty()) {
                CliOutput.out("No trusted sources. Add one with `jk trust add <url-prefix>`"
                        + " or `jk trust plugin <coordinate>`.");
            } else {
                for (String p : prefixes) CliOutput.out(p);
                for (String p : plugins) CliOutput.out("plugin " + p);
            }
            return 0;
        }
    }

    static final class RemoveCmd implements CliCommand {
        @Override
        public String name() {
            return "remove";
        }

        @Override
        public String description() {
            return "Stop trusting a URL prefix";
        }

        @Override
        public List<Opt> options() {
            return List.of(stateDirOpt());
        }

        @Override
        public List<Param> parameters() {
            return List.of(Param.of("prefix", Arity.ONE, "URL prefix to remove."));
        }

        @Override
        public int run(Invocation in) throws IOException {
            String prefix = in.positionals().get(0);
            boolean removed = TrustedSources.load(stateDir(in)).remove(prefix)
                    || TrustedPlugins.load(stateDir(in)).remove(prefix);
            if (!removed) {
                CliOutput.err("jk trust remove: not in the trusted list: " + prefix);
                return Exit.USAGE;
            }
            if (!GlobalOptions.from(in).outputIsJson()) {
                CliOutput.out("Removed " + prefix);
            }
            return 0;
        }
    }

    static final class ImportCmd implements CliCommand {
        @Override
        public String name() {
            return "import";
        }

        @Override
        public String description() {
            return "Import trusted sources from JBang (~/.jbang/trusted-sources.json)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.flag("Import from JBang's trusted-sources.json.", "--jbang"),
                    Opt.value("<file>", "Override the file to import (for tests).", "--file")
                            .hide(),
                    stateDirOpt());
        }

        @Override
        public int run(Invocation in) throws IOException {
            if (!in.isSet("jbang") && in.value("file").isEmpty()) {
                CliOutput.err("jk trust import: pass --jbang to import JBang's trusted sources.");
                return Exit.USAGE;
            }
            Path source = in.value("file")
                    .map(Path::of)
                    .orElseGet(() -> Path.of(System.getProperty("user.home"), ".jbang", "trusted-sources.json"));
            if (!Files.isRegularFile(source)) {
                CliOutput.err("jk trust import: " + source + " not found.");
                return Exit.NO_INPUT;
            }
            List<String> imported = TrustedSources.parseJBang(Files.readString(source));
            TrustedSources store = TrustedSources.load(stateDir(in));
            int added = 0;
            for (String p : imported) {
                if (store.add(p)) added++;
            }
            if (!GlobalOptions.from(in).outputIsJson()) {
                CliOutput.out("Imported " + added + " trusted source" + (added == 1 ? "" : "s") + " from " + source);
            }
            return 0;
        }
    }
}
