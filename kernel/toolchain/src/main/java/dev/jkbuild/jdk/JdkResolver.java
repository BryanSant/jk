// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the JDK a project should use. Lookup order:
 * <ol>
 *   <li>{@code .jdk-version} — must name a {@code <vendor>-<major>} (e.g.
 *       {@code temurin-25}); resolved against installed JDKs (the flexible
 *       vendor+major matcher) and, failing that, provisioned.</li>
 *   <li>None — caller falls back to {@code JAVA_HOME} / the running JVM.</li>
 * </ol>
 *
 * <p>A patch-level pin ({@code temurin-25.0.2}) or a vendorless one ({@code 25})
 * is rejected with an {@link IllegalArgumentException}: jk keeps the patch
 * current via the stable {@code <vendor>-<major>} pointer, so a patch pin would
 * fight its aggressive point-release upgrades.
 */
public final class JdkResolver {

    private final JdkRegistry registry;

    public JdkResolver(JdkRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Optional<InstalledJdk> resolve(Path projectDir) throws IOException {
        Optional<String> pin = readJdkVersion(projectDir);
        if (pin.isEmpty()) return Optional.empty();
        String spec = validatePin(pin.get());
        Optional<InstalledJdk> direct = registry.findBySpec(spec);
        if (direct.isPresent()) return direct;
        return new JdkProvisioning(registry)
                .resolve(JdkSpec.parse(spec))
                .map(JdkProvisioning.Result::jdk);
    }

    /**
     * Enforce the {@code <vendor>-<major>} pin format. Returns the trimmed spec;
     * throws {@link IllegalArgumentException} for patch-level or vendorless pins.
     */
    static String validatePin(String raw) {
        String pin = raw.trim();
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(pin);
        if (q.major().isEmpty() || q.exactVersion().isPresent() || q.hints().isEmpty()) {
            throw new IllegalArgumentException(
                    ".jdk-version must be <vendor>-<major> (e.g. \"temurin-25\"), not \"" + pin
                    + "\". Pin a vendor and major release — jk keeps the patch version current.");
        }
        return pin;
    }

    public static Optional<String> readJdkVersion(Path projectDir) throws IOException {
        Path file = projectDir.resolve(".jdk-version");
        if (!Files.exists(file)) return Optional.empty();
        String body = Files.readString(file).trim();
        return body.isEmpty() ? Optional.empty() : Optional.of(body);
    }

    /**
     * Convenience for CLI commands that want the project's pinned JDK with
     * an optional {@code --jdks-dir} override. {@code jdksDirOverride} may
     * be {@code null} to use the IntelliJ JDK directory default.
     */
    public static Optional<InstalledJdk> forProject(Path projectDir, Path jdksDirOverride)
            throws IOException {
        JdkRegistry registry = jdksDirOverride != null
                ? new JdkRegistry(jdksDirOverride)
                : new JdkRegistry();
        Optional<InstalledJdk> resolved = new JdkResolver(registry).resolve(projectDir);
        // Record the "this JDK was used by a project build / run / test"
        // signal. Best-effort; downstream wizards lean on this for
        // most-recently-used ordering and uninstall recommendations.
        resolved.ifPresent(jdk -> JdkAccessLedger.atDefaultPath().touch(jdk.identifier(), "resolve"));
        return resolved;
    }
}
