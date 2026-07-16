// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.command.ide.IdeGenerator;
import cc.jumpkick.command.ide.IdeModel;
import cc.jumpkick.command.ide.IdeSupport;
import cc.jumpkick.command.ide.IdeTarget;
import cc.jumpkick.command.ide.IntellijIdeGenerator;
import cc.jumpkick.command.ide.VscodeIdeGenerator;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@code jk ide} — generate IDE project files for a workspace or single project. By default it emits
 * configuration for <b>both</b> IntelliJ IDEA and VS Code; {@code --idea} / {@code --vscode} narrow it
 * to one.
 *
 * <p>Structured as a strategy pattern: {@link IdeSupport#build} computes one IDE-agnostic {@link
 * IdeModel} (workspace, deps, per-module JDKs), then each selected {@link IdeGenerator} turns it into
 * that IDE's files. The hidden {@code jk idea} and the {@code jk vscode} aliases delegate here with a
 * fixed target.
 *
 * <p><b>Engine-hosted</b> (Wave 4 of the slim-client migration): the dependency <em>sync</em> half
 * — the inventory's hidden heavy hitter, previously an in-process {@code CacheSync}+{@code Http}
 * fetch per module — now rides Wave 1's hosted {@code jk sync} verbatim (best-effort, exactly like
 * the old in-line sync); the model computation and file <em>generation</em> stay client-side, fed
 * from the lockfiles and the already-synced cache. The test-only in-process path keeps the in-line
 * fetch (see {@link IdeSupport#build}).
 */
public final class IdeCommand implements CliCommand {

    /** The generators, in a stable emit order. */
    private static final List<IdeGenerator> GENERATORS = List.of(new IntellijIdeGenerator(), new VscodeIdeGenerator());

    /** When non-null, the command runs exactly these targets and ignores the {@code --idea/--vscode} flags. */
    private final Set<IdeTarget> forced;

    public IdeCommand() {
        this.forced = null;
    }

    public IdeCommand(Set<IdeTarget> forced) {
        this.forced = forced;
    }

    @Override
    public String name() {
        return "ide";
    }

    @Override
    public String description() {
        return "Generate IDE project files (IntelliJ + VS Code)";
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>();
        if (forced == null) {
            opts.add(Opt.flag("Only generate IntelliJ IDEA files (.idea/ + *.iml).", "--idea"));
            opts.add(Opt.flag("Only generate VS Code files (.vscode/ + Eclipse metadata).", "--vscode"));
        }
        opts.add(Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                .hide());
        opts.add(Opt.value("<dir>", "Override the JDK install root (for tests).", "--jdks-dir")
                .hide());
        opts.add(
                Opt.value("<dir>", "Override the IDE config root for SDK registration (for tests).", "--ide-config-dir")
                        .hide());
        return opts;
    }

    @Override
    public int run(Invocation in) throws Exception {
        Set<IdeTarget> targets = selectTargets(in);

        IdeModel model;
        try {
            model = IdeSupport.build(in);
        } catch (IdeSupport.IdeException e) {
            CliOutput.err("jk ide: " + e.getMessage());
            return e.code();
        }

        for (IdeGenerator gen : GENERATORS) {
            if (!targets.contains(gen.target())) continue;
            try {
                gen.generate(model);
            } catch (IdeSupport.IdeException e) {
                CliOutput.err("jk ide: " + e.getMessage());
                return e.code();
            }
        }
        return 0;
    }

    /** Resolve which IDEs to generate: the forced set, else the flags, else both. */
    private Set<IdeTarget> selectTargets(Invocation in) {
        if (forced != null) return forced;
        boolean idea = in.has("idea");
        boolean vscode = in.has("vscode");
        if (idea && !vscode) return EnumSet.of(IdeTarget.IDEA);
        if (vscode && !idea) return EnumSet.of(IdeTarget.VSCODE);
        return EnumSet.allOf(IdeTarget.class);
    }
}
