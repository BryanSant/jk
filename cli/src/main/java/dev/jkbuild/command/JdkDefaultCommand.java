// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkLts;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** {@code jk jdk default [<spec> | --lts]} — set an installed JDK as the system default. */
public final class JdkDefaultCommand implements CliCommand {

    @Override public String name() { return "default"; }
    @Override public String description() { return "Set an installed JDK as the system default"; }

    @Override public List<Opt> options() {
        return List.of(
                Opt.flag("Pick the latest installed LTS JDK (Temurin preferred).", "--lts"),
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir").hide());
    }
    @Override public List<Param> parameters() {
        return List.of(Param.of("spec", Arity.ZERO_OR_ONE, "JDK spec to resolve (e.g. 25, temurin-25, corretto-25.0.3)."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        boolean lts = in.isSet("lts");
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();

        if (lts) {
            if (spec != null && !spec.isBlank()) { System.err.println("jk jdk default: --lts and <spec> are mutually exclusive."); return 64; }
            return applyLts(registry, defaults, System.out, System.err) ? 0 : 1;
        }
        if (spec == null || spec.isBlank()) { System.err.println("jk jdk default: <spec> required (or pass --lts)."); return 64; }
        Optional<JdkHit> match = registry.findHitBySpec(spec);
        if (match.isEmpty()) {
            System.err.println("jk jdk default: no installed JDK matches `" + spec + "` (try `jk jdk list` or `jk jdk install " + spec + "`)"); return 1;
        }
        applyDefault(match.get(), defaults, System.out);
        return 0;
    }

    static boolean applyLts(JdkRegistry registry, GlobalDefaultJdk defaults, PrintStream out, PrintStream err) throws IOException {
        List<JdkHit> hits = registry.listHits();
        List<JdkHit> ltsHits = new ArrayList<>();
        for (JdkHit hit : hits) {
            Integer m = majorOf(hit.version());
            if (m != null && JdkLts.isLtsMajor(m)) ltsHits.add(hit);
        }
        if (ltsHits.isEmpty()) { err.println("jk jdk default --lts: no LTS JDK installed (try `jk jdk install --lts`)."); return false; }
        ltsHits.sort(Comparator.comparingInt((JdkHit h) -> majorOf(h.version()) == null ? 0 : majorOf(h.version())).reversed()
                .thenComparing(h -> h.vendor() == JdkVendor.TEMURIN ? 0 : 1)
                .thenComparing((JdkHit h) -> h.version() == null ? "" : h.version(), Comparator.reverseOrder()));
        applyDefault(ltsHits.getFirst(), defaults, out);
        return true;
    }

    private static void applyDefault(JdkHit hit, GlobalDefaultJdk defaults, PrintStream out) throws IOException {
        String identifier = JdkRegistry.identifierFor(hit.home());
        defaults.set(new InstalledJdk(identifier, hit.home()));
        dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath().touch(identifier, "default-set");
        String name    = Theme.colorize(renderDisplayName(hit), Theme.active().focused());
        String message = "Default JDK set to " + name + ": " + JdkRender.coord(hit.source(), identifier);
        out.println(GoalWedge.chipLine(Glyphs.CHECK, "JDK", GlobalConfig.nerdfont(), message));
    }

    private static String renderDisplayName(JdkHit hit) {
        Integer major = majorOf(hit.version());
        if (hit.vendor() == JdkVendor.UNKNOWN) return major != null ? "JDK " + major : "JDK " + hit.version();
        String name = hit.vendor().displayName();
        return major != null ? name + " " + major : name + " " + hit.version();
    }

    static Integer majorOf(String version) {
        if (version == null || version.isEmpty()) return null;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        if (end == 0) return null;
        try { return Integer.parseInt(version.substring(0, end)); } catch (NumberFormatException e) { return null; }
    }
}
