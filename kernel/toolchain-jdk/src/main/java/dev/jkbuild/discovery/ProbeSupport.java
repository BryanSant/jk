// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkVendor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Shared helpers for {@link LocalToolProbe} implementations enumerating JDK installs via {@code
 * discoverAllJdks}.
 *
 * <p>{@link #discoverJdk} parses the {@code release} file exactly once and extracts both the {@code
 * JAVA_VERSION} (validation) and the {@code IMPLEMENTOR} / {@code IMPLEMENTOR_VERSION} (vendor
 * lookup) from that single read.
 */
public final class ProbeSupport {

    /** Spec used only to look up the platform-correct {@code bin/java} path. */
    private static final ToolSpec JDK_SPEC = ToolSpec.jdk("?", null);

    private ProbeSupport() {}

    /**
     * Validate a JDK home directory and produce a {@link JdkHit}: resolves symlinks, confirms {@code
     * bin/java} exists, then reads the {@code release} file once to derive both version and vendor.
     * Returns empty when the candidate isn't a runnable JDK (or the {@code release} file is missing —
     * mandatory since JDK 7u72).
     */
    public static Optional<JdkHit> discoverJdk(Path home, String source) {
        try {
            if (!Files.isDirectory(home)) return Optional.empty();
            Path canonical = home.toRealPath();
            if (!Files.exists(ToolHealth.requiredBinary(JDK_SPEC, canonical))) return Optional.empty();

            Path release = canonical.resolve("release");
            if (!Files.isRegularFile(release)) return Optional.empty();
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(release)) {
                props.load(in);
            }
            String version = stripQuotes(props.getProperty("JAVA_VERSION", ""));
            if (version.isEmpty()) return Optional.empty();
            JdkVendor vendor = JdkVendor.fromProperties(props);
            return Optional.of(new JdkHit(canonical, version, vendor, source));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static String stripQuotes(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }
}
