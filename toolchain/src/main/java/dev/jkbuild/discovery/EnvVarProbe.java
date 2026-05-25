// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads {@code JAVA_HOME} / {@code KOTLIN_HOME} / {@code M2_HOME} /
 * {@code GRADLE_HOME} (and {@code MAVEN_HOME} as a synonym). Honours
 * the user's explicit "use this tool" hint when the version matches.
 */
public final class EnvVarProbe implements LocalToolProbe {

    private final Function<String, String> env;

    public EnvVarProbe() { this(System::getenv); }
    EnvVarProbe(Function<String, String> env) { this.env = env; }

    @Override
    public String name() { return "java-home"; }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        String[] vars = switch (spec.kind()) {
            case "java" -> new String[] {"JAVA_HOME"};
            case "kotlin" -> new String[] {"KOTLIN_HOME"};
            case "maven" -> new String[] {"M2_HOME", "MAVEN_HOME"};
            case "gradle" -> new String[] {"GRADLE_HOME"};
            default -> new String[0];
        };
        for (String var : vars) {
            String value = env.apply(var);
            if (value == null || value.isBlank()) continue;
            Path home = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(home)) continue;
            Optional<String> version = ToolHealth.detectVersion(spec, home);
            if (version.isEmpty() || !version.get().equals(spec.version())) continue;
            if (spec.distribution() != null) {
                Optional<String> dist = ToolHealth.detectDistribution(spec, home);
                if (dist.isPresent() && !dist.get().equalsIgnoreCase(spec.distribution())) continue;
            }
            return Optional.of(new DiscoveredTool(home, version.get(), name() + ":" + var));
        }
        return Optional.empty();
    }

    @Override
    public List<JdkHit> discoverAllJdks() {
        String value = env.apply("JAVA_HOME");
        if (value == null || value.isBlank()) return List.of();
        return ProbeSupport.discoverJdk(Path.of(value), name())
                .map(List::of)
                .orElseGet(List::of);
    }
}
