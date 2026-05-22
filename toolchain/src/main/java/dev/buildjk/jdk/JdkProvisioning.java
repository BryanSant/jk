// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import dev.buildjk.discovery.DiscoveredTool;
import dev.buildjk.discovery.SymlinkProvisioner;
import dev.buildjk.discovery.ToolHealth;
import dev.buildjk.discovery.ToolProvisioner;
import dev.buildjk.discovery.ToolSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Discover-then-link pipeline for JDKs. Mirrors {@code ToolProvisioning}
 * for build tools, but speaks the SDKMAN-style {@code <version>-<dist>}
 * identifier scheme that {@link JdkResolver} and {@link JdkInstallCommand}
 * already use.
 *
 * <p>Pin format: {@code 21.0.5-tem}, {@code 21-graalce}, etc. The last
 * hyphen-segment is the distribution suffix; everything before it is the
 * version (anchored at the first dot or kept whole for major-only pins
 * like {@code 21}).
 */
public final class JdkProvisioning {

    public record Result(InstalledJdk jdk, Source source, String detail) {
        public enum Source { CACHED, LINKED }
        public Result {
            if (jdk == null) throw new IllegalArgumentException("jdk");
            if (source == null) throw new IllegalArgumentException("source");
            if (detail == null) detail = "";
        }
    }

    private final JdkRegistry registry;
    private final ToolProvisioner discovery;

    public JdkProvisioning(JdkRegistry registry) {
        this(registry, new ToolProvisioner());
    }

    public JdkProvisioning(JdkRegistry registry, ToolProvisioner discovery) {
        this.registry = registry;
        this.discovery = discovery;
    }

    /**
     * Resolve a JDK by SDKMAN-style pin, attempting probe-and-link before
     * giving up. Returns empty when neither {@code ~/.jk/jdks/} has the
     * JDK nor any probe finds it locally — caller decides whether to
     * trigger an explicit {@code jk jdk install}.
     */
    public Optional<Result> resolve(String pin) throws IOException {
        // 1. Exact match in our own dir wins.
        Optional<InstalledJdk> existing = registry.find(pin);
        if (existing.isEmpty()) {
            existing = registry.findByPrefix(pin);
        }
        if (existing.isPresent() && isHealthy(pin, existing.get())) {
            return Optional.of(new Result(existing.get(), Result.Source.CACHED, ""));
        }
        if (existing.isPresent()) {
            // Broken / wrong-version cache entry — clean up before probing.
            Path home = existing.get().home();
            if (Files.isSymbolicLink(home)) {
                SymlinkProvisioner.unlink(home);
            }
        }

        // 2. Probe the host.
        if (!SymlinkProvisioner.canSymlink()) return Optional.empty();
        ToolSpec spec = specFromPin(pin);
        Optional<DiscoveredTool> hit = discovery.discover(spec);
        if (hit.isEmpty()) return Optional.empty();

        // 3. Link into ~/.jk/jdks/<full-id>/.
        String identifier = identifierFor(spec);
        Path link = registry.jdksRoot().resolve(identifier);
        SymlinkProvisioner.link(link, hit.get().home());
        return Optional.of(new Result(
                new InstalledJdk(identifier, link),
                Result.Source.LINKED,
                hit.get().source() + " → " + hit.get().home()));
    }

    private boolean isHealthy(String pin, InstalledJdk jdk) {
        ToolSpec spec = specFromPin(pin);
        return ToolHealth.isHealthy(spec, jdk.home());
    }

    /**
     * Split a SDKMAN-style pin ({@code 21.0.5-tem}, {@code 21-tem},
     * {@code 21.0.5}) into version + distribution.
     */
    static ToolSpec specFromPin(String pin) {
        int lastDash = pin.lastIndexOf('-');
        if (lastDash <= 0) {
            return ToolSpec.jdk(pin, null);
        }
        // The dash-suffix is the distribution unless it looks numeric
        // (e.g. `21.0.5+11` is not SDKMAN-style, but use the plus marker
        // to recognise it).
        String version = pin.substring(0, lastDash);
        String suffix = pin.substring(lastDash + 1);
        if (looksLikeVersion(suffix)) {
            return ToolSpec.jdk(pin, null);
        }
        return ToolSpec.jdk(version, suffix);
    }

    private static boolean looksLikeVersion(String s) {
        if (s.isEmpty()) return false;
        char first = s.charAt(0);
        return first >= '0' && first <= '9';
    }

    private static String identifierFor(ToolSpec spec) {
        String arch = Platform.currentArchitecture();
        String os = Platform.currentOperatingSystem();
        String base = spec.distribution() == null
                ? spec.version()
                : spec.version() + "-" + spec.distribution();
        return base + "-" + arch + "-" + os;
    }
}
