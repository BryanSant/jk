// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.jdk.GlobalDefaultJdk;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkLts;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkVendor;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** {@code jk jdk default [<spec> | --lts]} — set an installed JDK as the system default. */
public final class JdkDefaultCommand implements CliCommand {

    @Override
    public String name() {
        return "default";
    }

    @Override
    public String description() {
        return "Set a specific Java Development Kit to be the default";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Pick the latest installed LTS JDK (Temurin preferred).", "--lts"),
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "spec",
                Arity.ZERO_OR_ONE,
                "The vendor/version of JDK you'd like to make the default\n"
                        + "  (ex: 25, lts, latest, temurin-25, openjdk-26)"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        boolean lts = in.isSet("lts");
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();

        if (lts) {
            if (spec != null && !spec.isBlank()) {
                CliOutput.err("jk jdk default: --lts and <spec> are mutually exclusive.");
                return Exit.USAGE;
            }
            return applyLts(registry, defaults, CliOutput.stdout(), CliOutput.stderr()) ? 0 : 1;
        }
        if (spec == null || spec.isBlank()) {
            CliOutput.err("jk jdk default: <spec> required (or pass --lts).");
            return Exit.USAGE;
        }
        Optional<JdkHit> match = cc.jumpkick.jdk.JdkKeywords.isKeyword(spec)
                ? cc.jumpkick.jdk.JdkKeywords.bestInstalledMatch(spec, registry.listHits())
                : registry.findHitBySpec(spec);
        if (match.isEmpty()) {
            CliOutput.err("jk jdk default: no installed JDK matches `"
                    + spec
                    + "` (try `jk jdk list` or `jk jdk install "
                    + spec
                    + "`)");
            return 1;
        }
        applyDefault(match.get(), defaults, CliOutput.stdout());
        return 0;
    }

    static boolean applyLts(JdkRegistry registry, GlobalDefaultJdk defaults, PrintStream out, PrintStream err)
            throws IOException {
        List<JdkHit> hits = registry.listHits();
        List<JdkHit> ltsHits = new ArrayList<>();
        for (JdkHit hit : hits) {
            Integer m = majorOf(hit.version());
            if (m != null && JdkLts.isLtsMajor(m)) ltsHits.add(hit);
        }
        if (ltsHits.isEmpty()) {
            err.println("jk jdk default --lts: no LTS JDK installed (try `jk jdk install --lts`).");
            return false;
        }
        ltsHits.sort(Comparator.comparingInt((JdkHit h) -> majorOf(h.version()) == null ? 0 : majorOf(h.version()))
                .reversed()
                .thenComparing(h -> h.vendor() == JdkVendor.TEMURIN ? 0 : 1)
                .thenComparing((JdkHit h) -> h.version() == null ? "" : h.version(), Comparator.reverseOrder()));
        applyDefault(ltsHits.getFirst(), defaults, out);
        return true;
    }

    private static void applyDefault(JdkHit hit, GlobalDefaultJdk defaults, PrintStream out) throws IOException {
        String identifier = JdkRegistry.identifierFor(hit.home());
        defaults.set(new InstalledJdk(identifier, hit.home()));
        cc.jumpkick.jdk.JdkAccessLedger.atDefaultPath().touch(identifier, "default-set");
        String name = Theme.colorize(renderDisplayName(hit), Theme.active().focused());
        String message = "Default JDK set to " + name + ": " + JdkRender.coord(hit.source(), identifier);
        out.println(PipelineWedge.chipLine(Glyphs.CHECK, "JDK", GlobalConfig.nerdfont(), message));
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
        try {
            return Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
