// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk default <spec>} — pick an already-installed JDK matching
 * {@code <spec>} and make it the system-wide default ({@code JAVA_HOME}
 * source). The spec parser is the same one {@code jk jdk install} uses
 * (see {@link dev.jkbuild.jdk.JdkSelector#parseFlexible}), so inputs like
 * {@code 25}, {@code temurin-25}, {@code corretto-25.0.3}, and
 * {@code 26.0.1-librca} all resolve.
 *
 * <p>Resolution walks the JDK registry in probe-chain order and returns
 * the first install satisfying every spec constraint — the "natural
 * find-the-first-one" precedence the user expects.
 */
@Command(name = "default", description = "Set an installed JDK as the system default")
public final class JdkDefaultCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<spec>",
            description = "JDK spec to resolve (e.g. 25, temurin-25, corretto-25.0.3).")
    String spec;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Optional<JdkHit> match = registry.findHitBySpec(spec);
        if (match.isEmpty()) {
            System.err.println("jk jdk default: no installed JDK matches `" + spec
                    + "` (try `jk jdk list` to see what's available, or "
                    + "`jk jdk install " + spec + "` to install one)");
            return 1;
        }

        JdkHit hit = match.get();
        String identifier = JdkRegistry.identifierFor(hit.home());
        InstalledJdk chosen = new InstalledJdk(identifier, hit.home());
        GlobalDefaultJdk.current().set(chosen);

        // "➜ The default JDK is now set to <Eclipse Temurin 25> (<25.0.3-tem>)"
        // — "default" and the human-readable name in bold-white, the
        // disambiguating registry identifier in dark gray.
        String displayName = renderDisplayName(hit);
        System.out.println(Theme.colorize("➜", Theme.brightGreen())
                + " The " + Theme.colorize("default", Theme.focused())
                + " JDK is now set to " + Theme.colorize(displayName, Theme.focused())
                + " " + Theme.colorize("(" + identifier + ")", Theme.darkGray()));
        return 0;
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

    private static Integer majorOf(String version) {
        if (version == null || version.isEmpty()) return null;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        if (end == 0) return null;
        try { return Integer.parseInt(version.substring(0, end)); }
        catch (NumberFormatException e) { return null; }
    }
}
