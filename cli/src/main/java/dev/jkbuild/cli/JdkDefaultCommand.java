// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkLts;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk default [<spec> | --lts]} — promote an already-installed
 * JDK to system-wide default ({@code JAVA_HOME} source).
 *
 * <p>Two modes:
 * <ul>
 *   <li><strong>Spec mode</strong> — pass a JDK spec the same way
 *       {@code jk jdk install} accepts it ({@code 25}, {@code temurin-25},
 *       {@code corretto-25.0.3}, {@code 26.0.1-librca}). Walks the
 *       registry in probe-chain order and picks the first match.</li>
 *   <li><strong>{@code --lts} mode</strong> — auto-pick the latest LTS
 *       installed (Eclipse Temurin preferred when multiple vendors share
 *       the LTS major). Used both interactively and as the post-uninstall
 *       reconciliation hook when the default is removed.</li>
 * </ul>
 */
@Command(name = "default", description = "Set an installed JDK as the system default")
public final class JdkDefaultCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<spec>",
            description = "JDK spec to resolve (e.g. 25, temurin-25, corretto-25.0.3).")
    String spec;

    @Option(names = "--lts",
            description = "Pick the latest installed LTS JDK (Temurin preferred).")
    boolean lts;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();

        if (lts) {
            if (spec != null && !spec.isBlank()) {
                System.err.println("jk jdk default: --lts and <spec> are mutually exclusive.");
                return 64;
            }
            return applyLts(registry, defaults, System.out, System.err) ? 0 : 1;
        }
        if (spec == null || spec.isBlank()) {
            System.err.println("jk jdk default: <spec> required (or pass --lts).");
            return 64;
        }

        Optional<JdkHit> match = registry.findHitBySpec(spec);
        if (match.isEmpty()) {
            System.err.println("jk jdk default: no installed JDK matches `" + spec
                    + "` (try `jk jdk list` to see what's available, or "
                    + "`jk jdk install " + spec + "` to install one)");
            return 1;
        }

        applyDefault(match.get(), defaults, System.out);
        return 0;
    }

    /**
     * Pick the latest-LTS installed JDK and set it as the system default.
     * Among ties at the same LTS major, Eclipse Temurin wins; otherwise
     * the highest version sorts first. Public so {@code jk jdk uninstall}
     * can call into it after removing the current default.
     *
     * @return {@code true} when an LTS JDK was found and installed as
     * default; {@code false} when no installed JDK is at an LTS major
     * (caller decides whether that's an error).
     */
    static boolean applyLts(JdkRegistry registry, GlobalDefaultJdk defaults,
                            PrintStream out, PrintStream err) throws IOException {
        List<JdkHit> hits = registry.listHits();
        List<JdkHit> ltsHits = new ArrayList<>();
        for (JdkHit hit : hits) {
            Integer m = majorOf(hit.version());
            if (m != null && JdkLts.isLtsMajor(m)) ltsHits.add(hit);
        }
        if (ltsHits.isEmpty()) {
            err.println("jk jdk default --lts: no LTS JDK installed "
                    + "(try `jk jdk install --lts`).");
            return false;
        }
        // Sort: highest LTS major first; Eclipse Temurin wins ties; then
        // highest version.
        ltsHits.sort(Comparator
                .comparingInt((JdkHit h) -> majorOf(h.version()) == null ? 0 : majorOf(h.version()))
                        .reversed()
                .thenComparing(h -> h.vendor() == JdkVendor.TEMURIN ? 0 : 1)
                .thenComparing((JdkHit h) -> h.version() == null ? "" : h.version(),
                        Comparator.reverseOrder()));
        JdkHit picked = ltsHits.getFirst();
        applyDefault(picked, defaults, out);
        return true;
    }

    private static void applyDefault(JdkHit hit, GlobalDefaultJdk defaults, PrintStream out)
            throws IOException {
        String identifier = JdkRegistry.identifierFor(hit.home());
        InstalledJdk chosen = new InstalledJdk(identifier, hit.home());
        defaults.set(chosen);
        String displayName = renderDisplayName(hit);
        out.println(Theme.colorize("➜", Theme.brightGreen())
                + " The " + Theme.colorize("default", Theme.focused())
                + " JDK is now set to " + Theme.colorize(displayName, Theme.focused())
                + " " + Theme.colorize("(" + identifier + ")", Theme.darkGray()));
    }

    /**
     * "{@code Eclipse Temurin 25}" — vendor display name plus major version.
     * Falls back to the raw version when we can't classify the vendor (e.g.
     * a JDK whose {@code release} file is empty).
     */
    private static String renderDisplayName(JdkHit hit) {
        Integer major = majorOf(hit.version());
        if (hit.vendor() == JdkVendor.UNKNOWN) {
            return major != null ? "JDK " + major : "JDK " + hit.version();
        }
        String name = hit.vendor().displayName();
        return major != null ? name + " " + major : name + " " + hit.version();
    }

    static Integer majorOf(String version) {
        if (version == null || version.isEmpty()) return null;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        if (end == 0) return null;
        try { return Integer.parseInt(version.substring(0, end)); }
        catch (NumberFormatException e) { return null; }
    }
}
