// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
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
import java.util.Locale;
import java.util.Optional;

/** {@code jk jdk pin <spec>} — pin the project to an installed JDK. */
public final class JdkPinCommand implements CliCommand {

    @Override
    public String name() {
        return "pin";
    }

    @Override
    public String description() {
        return "Pin to a specific Java Development Kit";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "spec",
                Arity.ONE,
                "The vendor/version of JDK you'd like to pin to\n"
                        + "  (ex: 25, lts, latest, temurin-25, openjdk-26)"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String spec = in.positionals().get(0);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        Path projectDir = GlobalOptions.from(in).workingDir();
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Optional<JdkHit> hit = dev.jkbuild.jdk.JdkKeywords.isKeyword(spec)
                ? dev.jkbuild.jdk.JdkKeywords.bestInstalledMatch(spec, registry.listHits())
                : registry.findHitBySpec(spec);
        if (hit.isEmpty()) {
            System.err.println("jk jdk pin: no installed JDK matches `" + spec + "` (try `jk jdk list`)");
            return 1;
        }
        // .jdk-version pins <vendor>-<major>, never a patch — jk keeps the patch
        // version current via the stable pointer.
        String pin = pinName(hit.get());
        if (pin == null) {
            System.err.println("jk jdk pin: could not derive a <vendor>-<major> name from `" + spec + "`");
            return 1;
        }
        Files.writeString(projectDir.resolve(".jdk-version"), pin + "\n", StandardCharsets.UTF_8);
        dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath().touch(pin, "pin");
        Theme t = Theme.active();
        System.out.println(Theme.colorize(Glyphs.CHECK, t.success())
                + " Pinned project to "
                + Theme.colorize(pin, t.focused()));
        return 0;
    }

    /** {@code <vendor>-<major>} for a hit (e.g. {@code temurin-25}); null if the major is unknown. */
    private static String pinName(JdkHit hit) {
        Optional<Integer> major = JdkSelector.parseFlexible(hit.version() == null ? "" : hit.version())
                .major();
        if (major.isEmpty()) return null;
        String vendor =
                hit.vendor().jbPrefix().orElseGet(() -> hit.vendor().vendor().toLowerCase(Locale.ROOT));
        return vendor + "-" + major.get();
    }
}
