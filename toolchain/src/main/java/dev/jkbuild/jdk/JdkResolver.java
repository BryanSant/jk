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
 *   <li>{@code .jk-version} — its content is parsed as a {@link JdkSpec}
 *       and resolved through {@link JdkProvisioning} (which consults the
 *       IntelliJ JDK directory and {@code JAVA_HOME}).</li>
 *   <li>None — caller falls back to {@code JAVA_HOME} / the running JVM.</li>
 * </ol>
 */
public final class JdkResolver {

    private final JdkRegistry registry;

    public JdkResolver(JdkRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Optional<InstalledJdk> resolve(Path projectDir) throws IOException {
        Optional<String> pin = readJkVersion(projectDir);
        if (pin.isEmpty()) return Optional.empty();
        JdkSpec spec = JdkSpec.parse(pin.get());
        Optional<InstalledJdk> direct = registry.find(spec.value())
                .or(() -> {
                    try { return registry.findByPrefix(spec.value()); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
        if (direct.isPresent()) return direct;
        return new JdkProvisioning(registry)
                .resolve(spec)
                .map(JdkProvisioning.Result::jdk);
    }

    public static Optional<String> readJkVersion(Path projectDir) throws IOException {
        Path file = projectDir.resolve(".jk-version");
        if (!Files.exists(file)) return Optional.empty();
        String body = Files.readString(file).trim();
        return body.isEmpty() ? Optional.empty() : Optional.of(body);
    }
}
