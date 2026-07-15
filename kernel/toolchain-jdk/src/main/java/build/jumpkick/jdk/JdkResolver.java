// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and resolves the project's {@code .jdk-version} pin — the {@code .jdk-version}
 * <em>tier</em> of the resolution order. The full cross-tier order (switch / env / {@code
 * .jdk-version} / lock / {@code [project].jdk} / current / default / …) lives in {@link
 * JdkResolution}; this class only owns the file: read it ({@link #readJdkVersion}), validate it
 * ({@link #validatePin}), and resolve it to an installed (or provisioned) JDK.
 *
 * <p>A patch-level pin ({@code temurin-25.0.2}) or a vendorless one ({@code 25}) is rejected with
 * an {@link IllegalArgumentException}: jk keeps the patch current via the stable {@code
 * <vendor>-<major>} pointer, so a patch pin would fight its aggressive point-release upgrades.
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
        return new JdkProvisioning(registry).resolve(JdkSpec.parse(spec)).map(JdkProvisioning.Result::jdk);
    }

    /**
     * Enforce the {@code <vendor>-<major>} pin format. Returns the trimmed spec; throws {@link
     * IllegalArgumentException} for patch-level or vendorless pins.
     */
    static String validatePin(String raw) {
        String pin = raw.trim();
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(pin);
        if (q.major().isEmpty() || q.exactVersion().isPresent() || q.hints().isEmpty()) {
            throw new IllegalArgumentException(".jdk-version must be <vendor>-<major> (e.g. \"temurin-25\"), not \""
                    + pin
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
     * Convenience for CLI commands that want the project's pinned JDK with an optional {@code
     * --jdks-dir} override. {@code jdksDirOverride} may be {@code null} to use the IntelliJ JDK
     * directory default.
     */
    public static Optional<InstalledJdk> forProject(Path projectDir, Path jdksDirOverride) throws IOException {
        JdkRegistry registry = jdksDirOverride != null ? new JdkRegistry(jdksDirOverride) : new JdkRegistry();
        Optional<InstalledJdk> resolved = new JdkResolver(registry).resolve(projectDir);
        // Record the "this JDK was used by a project build / run / test"
        // signal. Best-effort; downstream wizards lean on this for
        // most-recently-used ordering and uninstall recommendations.
        resolved.ifPresent(jdk -> JdkAccessLedger.atDefaultPath().touch(jdk.identifier(), "resolve"));
        return resolved;
    }
}
