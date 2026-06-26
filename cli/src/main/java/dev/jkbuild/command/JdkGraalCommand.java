// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk jdk graal [<spec>]} — set the default GraalVM-based JDK (Oracle
 * GraalVM or GraalVM CE). It's tracked separately from the default <em>java</em>
 * JDK and backs {@code GRAALVM_HOME} (via the {@code jk activate} hook) and
 * {@code jk native}. With no spec, the newest installed GraalVM is chosen,
 * preferring Oracle GraalVM over GraalVM CE.
 */
public final class JdkGraalCommand implements CliCommand {

    @Override public String name() { return "graal"; }
    @Override public String description() { return "Set a specific GraalVM JDK to be the native-image default"; }

    @Override public List<Opt> options() {
        return List.of(Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir").hide());
    }
    @Override public List<Param> parameters() {
        return List.of(Param.of("spec", Arity.ZERO_OR_ONE,
                "The vendor/version of GraalVM you'd like to make the default\n"
                + "  (ex: 25, lts, latest, graal-25, graalce-25)"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();

        List<JdkHit> graals = registry.listHits().stream().filter(JdkGraalCommand::isGraal).toList();
        if (graals.isEmpty()) {
            System.err.println("jk jdk graal: no GraalVM JDK installed — install one with "
                    + "`jk jdk install native` (or `jk jdk install graalvm-25`).");
            return 1;
        }

        JdkHit chosen;
        if (spec == null || spec.isBlank()) {
            chosen = graals.stream().sorted(byGraalPreference()).findFirst().orElseThrow();
        } else {
            Optional<JdkHit> match = dev.jkbuild.jdk.JdkKeywords.isKeyword(spec)
                    ? dev.jkbuild.jdk.JdkKeywords.bestInstalledMatch(spec, graals)
                    : registry.findHitBySpec(spec).filter(JdkGraalCommand::isGraal);
            if (match.isEmpty()) {
                System.err.println("jk jdk graal: no installed GraalVM matches `" + spec
                        + "` (try `jk jdk list`).");
                return 1;
            }
            chosen = match.get();
        }

        String identifier = JdkRegistry.identifierFor(chosen.home());
        defaults.setGraal(new InstalledJdk(identifier, chosen.home()));
        System.out.println(Theme.colorize("➜", Theme.active().brightGreen())
                + " The " + Theme.colorize("native", Theme.active().focused())
                + " (default GraalVM) JDK is now set to " + Theme.colorize(display(chosen), Theme.active().focused())
                + " " + Theme.colorize("(" + identifier + ")", Theme.active().darkGray()));
        return 0;
    }

    static boolean isGraal(JdkHit h) {
        return h.vendor() == JdkVendor.ORACLE_GRAALVM || h.vendor() == JdkVendor.GRAALVM_CE;
    }

    /** Oracle GraalVM before GraalVM CE; newer version first within a flavour. */
    private static Comparator<JdkHit> byGraalPreference() {
        return Comparator
                .comparingInt((JdkHit h) -> {
                    int i = JdkVendor.GRAAL_PREFERENCE.indexOf(h.vendor());
                    return i >= 0 ? i : Integer.MAX_VALUE;
                })
                .thenComparing(h -> h.version() == null ? ""
                        : dev.jkbuild.jdk.JdkSelector.versionKey(h.version()), Comparator.reverseOrder());
    }

    private static String display(JdkHit hit) {
        Integer major = JdkDefaultCommand.majorOf(hit.version());
        String name = hit.vendor().displayName();
        return major != null ? name + " " + major : name + " " + hit.version();
    }
}
