// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.mvn;

import dev.jkbuild.compat.BuildTool;
import dev.jkbuild.compat.ToolDistribution;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the Maven distribution to use for a project (PRD §24.1).
 *
 * <ol>
 *   <li>{@code .mvn/wrapper/maven-wrapper.properties} {@code distributionUrl} — honor what the
 *       project pinned.
 *   <li>Otherwise jk's bundled default ({@link #DEFAULT_VERSION}).
 * </ol>
 */
public final class MavenResolver {

    /** jk's bundled default when no wrapper is present. */
    public static final String DEFAULT_VERSION = "3.9.9";

    private static final String DEFAULT_BASE = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/";

    // apache-maven-<version>-bin.<zip|tar.gz>
    private static final Pattern FILENAME_VERSION = Pattern.compile("apache-maven-(?<v>[^/]+)-bin\\.(?:zip|tar\\.gz)$");

    public ToolDistribution resolve(Path projectDir) throws IOException {
        Path wrapperProps = projectDir.resolve(".mvn/wrapper/maven-wrapper.properties");
        if (Files.exists(wrapperProps)) {
            ToolDistribution fromWrapper = fromWrapperProperties(wrapperProps);
            if (fromWrapper != null) return fromWrapper;
        }
        return defaultDistribution();
    }

    public static ToolDistribution defaultDistribution() {
        URI uri = URI.create(DEFAULT_BASE + DEFAULT_VERSION + "/apache-maven-" + DEFAULT_VERSION + "-bin.zip");
        return new ToolDistribution(BuildTool.MAVEN, DEFAULT_VERSION, uri, "zip");
    }

    static ToolDistribution fromWrapperProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        String url = props.getProperty("distributionUrl");
        if (url == null || url.isBlank()) return null;
        URI uri = URI.create(url.trim());
        String archiveType = url.endsWith(".tar.gz") ? "tar.gz" : "zip";
        String version = parseVersion(uri).orElse("wrapper");
        String sha256 = props.getProperty("distributionSha256Sum");
        return new ToolDistribution(
                BuildTool.MAVEN, version, uri, archiveType, sha256 == null || sha256.isBlank() ? null : sha256.trim());
    }

    static Optional<String> parseVersion(URI uri) {
        String path = uri.getPath();
        if (path == null) return Optional.empty();
        Matcher m = FILENAME_VERSION.matcher(path);
        return m.find() ? Optional.of(m.group("v")) : Optional.empty();
    }
}
