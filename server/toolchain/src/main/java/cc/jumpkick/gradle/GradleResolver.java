// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolDistribution;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the Gradle distribution to use for a project (PRD §24.1).
 *
 * <ol>
 *   <li>{@code gradle/wrapper/gradle-wrapper.properties} {@code distributionUrl} — honor what the
 *       project pinned.
 *   <li>Otherwise jk's bundled default ({@link #DEFAULT_VERSION}).
 * </ol>
 *
 * <p>Gradle distributions are always {@code .zip}.
 */
public final class GradleResolver {

    /** jk's bundled default when no wrapper is present. Matches {@code .sdkmanrc}. */
    public static final String DEFAULT_VERSION = "9.5.1";

    private static final String DEFAULT_BASE = "https://services.gradle.org/distributions/";

    // gradle-<version>-(bin|all).zip
    private static final Pattern FILENAME_VERSION = Pattern.compile("gradle-(?<v>[^/]+)-(?:bin|all)\\.zip$");

    public ToolDistribution resolve(Path projectDir) throws IOException {
        Path wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (Files.exists(wrapperProps)) {
            ToolDistribution fromWrapper = fromWrapperProperties(wrapperProps);
            if (fromWrapper != null) return fromWrapper;
        }
        return defaultDistribution();
    }

    public static ToolDistribution defaultDistribution() {
        URI uri = URI.create(DEFAULT_BASE + "gradle-" + DEFAULT_VERSION + "-bin.zip");
        return new ToolDistribution(BuildTool.GRADLE, DEFAULT_VERSION, uri, "zip");
    }

    static ToolDistribution fromWrapperProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        String url = props.getProperty("distributionUrl");
        if (url == null || url.isBlank()) return null;
        URI uri = URI.create(url.trim());
        String version = parseVersion(uri).orElse("wrapper");
        String sha256 = props.getProperty("distributionSha256Sum");
        return new ToolDistribution(
                BuildTool.GRADLE, version, uri, "zip", sha256 == null || sha256.isBlank() ? null : sha256.trim());
    }

    static Optional<String> parseVersion(URI uri) {
        String path = uri.getPath();
        if (path == null) return Optional.empty();
        Matcher m = FILENAME_VERSION.matcher(path);
        return m.find() ? Optional.of(m.group("v")) : Optional.empty();
    }
}
