// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** {@code jk jdk pin <spec>} — pin the project to an installed JDK. */
public final class JdkPinCommand implements CliCommand {

    @Override public String name() { return "pin"; }
    @Override public String description() { return "Pin to a specific JDK version"; }

    @Override public List<Opt> options() {
        return List.of(Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir").hide());
    }
    @Override public List<Param> parameters() {
        return List.of(Param.of("spec", Arity.ONE, "Installed JDK identifier or prefix (e.g. temurin-21)."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String spec = in.positionals().get(0);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        Path projectDir = GlobalOptions.from(in).workingDir();
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Optional<InstalledJdk> jdk = registry.find(spec)
                .or(() -> { try { return registry.findByPrefix(spec); } catch (IOException e) { throw new RuntimeException(e); } });
        if (jdk.isEmpty()) {
            System.err.println("jk jdk pin: no installed JDK matches `" + spec + "` (try `jk jdk list`)"); return 1;
        }
        String identifier = jdk.get().identifier();
        Files.writeString(projectDir.resolve(".jdk-version"), identifier + "\n", StandardCharsets.UTF_8);
        dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath().touch(identifier, "pin");
        System.out.println("Pinned project to " + identifier);
        return 0;
    }
}
