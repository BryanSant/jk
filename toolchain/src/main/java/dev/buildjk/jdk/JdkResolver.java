// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the JDK a project should use. Lookup order per PRD §5.4 / §5.5:
 * <ol>
 *   <li>{@code .jk-version} (exact identifier, or version-vendor prefix).</li>
 *   <li>{@code .sdkmanrc} ({@code java=<sdkman-identifier>} line).</li>
 *   <li>None — caller falls back to {@code JAVA_HOME} / the running JVM.</li>
 * </ol>
 *
 * <p>{@code .jk-version} wins on conflict.
 */
public final class JdkResolver {

    private final JdkRegistry registry;

    public JdkResolver(JdkRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Find the pinned JDK for {@code projectDir}, if any. When the JDK
     * isn't already under {@code ~/.jk/jdks/}, runs the discovery probe
     * chain ({@code JAVA_HOME} / SDKMAN / JBang / asdf / jenv / Homebrew
     * / system) and links the first match into {@code ~/.jk/jdks/}
     * before returning. Falls through to {@code Optional.empty()} only
     * if neither the registry nor the host has a copy.
     */
    public Optional<InstalledJdk> resolve(Path projectDir) throws IOException {
        Path jkVersion = projectDir.resolve(".jk-version");
        if (Files.exists(jkVersion)) {
            String pin = Files.readString(jkVersion).trim();
            if (!pin.isEmpty()) {
                Optional<InstalledJdk> match = registry.find(pin)
                        .or(() -> findByPrefixQuiet(pin));
                if (match.isPresent()) return match;
                Optional<JdkProvisioning.Result> provisioned =
                        new JdkProvisioning(registry).resolve(pin);
                if (provisioned.isPresent()) {
                    return Optional.of(provisioned.get().jdk());
                }
            }
        }
        Path sdkmanrc = projectDir.resolve(".sdkmanrc");
        if (Files.exists(sdkmanrc)) {
            String javaPin = parseSdkmanrcJavaLine(Files.readString(sdkmanrc));
            if (javaPin != null) {
                Optional<InstalledJdk> match = findByPrefixQuiet(javaPin);
                if (match.isPresent()) return match;
                Optional<JdkProvisioning.Result> provisioned =
                        new JdkProvisioning(registry).resolve(javaPin);
                if (provisioned.isPresent()) {
                    return Optional.of(provisioned.get().jdk());
                }
            }
        }
        return Optional.empty();
    }

    /** The pin string from {@code .jk-version}, raw and untrimmed-of-trailing-newlines. */
    public static Optional<String> readJkVersion(Path projectDir) throws IOException {
        Path file = projectDir.resolve(".jk-version");
        if (!Files.exists(file)) return Optional.empty();
        String body = Files.readString(file).trim();
        return body.isEmpty() ? Optional.empty() : Optional.of(body);
    }

    /** Parse the {@code java=...} line from a {@code .sdkmanrc}. */
    static String parseSdkmanrcJavaLine(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) continue;
            if (trimmed.startsWith("java=")) {
                return trimmed.substring("java=".length()).trim();
            }
        }
        return null;
    }

    private Optional<InstalledJdk> findByPrefixQuiet(String pin) {
        try {
            return registry.findByPrefix(pin);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
