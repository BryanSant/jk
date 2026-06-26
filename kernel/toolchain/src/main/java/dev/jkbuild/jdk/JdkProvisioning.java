// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * Resolves a {@link JdkSpec} to an already-on-disk {@link InstalledJdk},
 * without downloading. Lookup order:
 *
 * <ol>
 *   <li>An installed JDK matching the spec, discovered via {@link JdkRegistry}'s
 *       probe chain (jk / IntelliJ / Gradle / SDKMAN / mise / asdf / jenv /
 *       Homebrew / system).</li>
 *   <li>{@code JAVA_HOME} — accepted when its {@code release} file's
 *       {@code IMPLEMENTOR} + {@code JAVA_VERSION} are consistent with
 *       the spec.</li>
 *   <li>Empty — caller decides whether to invoke {@link JdkInstaller}.</li>
 * </ol>
 */
public final class JdkProvisioning {

    public record Result(InstalledJdk jdk, Source source, String detail) {
        public enum Source {
            CACHED,
            JAVA_HOME
        }

        public Result {
            if (jdk == null) throw new IllegalArgumentException("jdk");
            if (source == null) throw new IllegalArgumentException("source");
            if (detail == null) detail = "";
        }
    }

    private final JdkRegistry registry;
    private final JdkCatalogClient catalogClient;
    private final Function<String, String> env;
    private final String os;
    private final String arch;

    public JdkProvisioning(JdkRegistry registry) {
        this(registry, new JdkCatalogClient(), System::getenv, HostPlatform.currentOs(), HostPlatform.currentArch());
    }

    public JdkProvisioning(
            JdkRegistry registry,
            JdkCatalogClient catalogClient,
            Function<String, String> env,
            String os,
            String arch) {
        this.registry = registry;
        this.catalogClient = catalogClient;
        this.env = env;
        this.os = os;
        this.arch = arch;
    }

    public Optional<Result> resolve(JdkSpec spec) throws IOException {
        Optional<JdkCatalog.Entry> entry = selectFromCatalog(spec);

        if (entry.isPresent()) {
            Optional<InstalledJdk> existing = registry.find(entry.get().installFolderName());
            if (existing.isPresent()) {
                return Optional.of(new Result(existing.get(), Result.Source.CACHED, ""));
            }
        }

        Optional<InstalledJdk> javaHome = matchJavaHome(spec, entry);
        if (javaHome.isPresent()) {
            return Optional.of(new Result(
                    javaHome.get(),
                    Result.Source.JAVA_HOME,
                    "JAVA_HOME=" + javaHome.get().home()));
        }
        return Optional.empty();
    }

    private Optional<JdkCatalog.Entry> selectFromCatalog(JdkSpec spec) {
        try {
            JdkCatalog catalog = catalogClient.fetch();
            return JdkSelector.select(catalog, spec, os, arch);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Optional<InstalledJdk> matchJavaHome(JdkSpec spec, Optional<JdkCatalog.Entry> entry) {
        String value = env.apply("JAVA_HOME");
        if (value == null || value.isBlank()) return Optional.empty();
        Path home = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(home)) return Optional.empty();
        if (!Files.exists(home.resolve("bin"))) return Optional.empty();

        Properties release = readRelease(home);
        if (release.isEmpty()) return Optional.empty();
        String version = stripQuotes(release.getProperty("JAVA_VERSION", ""));
        String implementor = stripQuotes(release.getProperty("IMPLEMENTOR", ""));
        if (!matchesSpec(spec, entry, version, implementor)) return Optional.empty();

        String identifier = home.getFileName() != null ? home.getFileName().toString() : "java-home";
        return Optional.of(new InstalledJdk(identifier, home));
    }

    private static boolean matchesSpec(
            JdkSpec spec, Optional<JdkCatalog.Entry> entry, String version, String implementor) {
        if (version.isEmpty()) return false;
        if (entry.isPresent()) {
            JdkCatalog.Entry e = entry.get();
            return version.startsWith(String.valueOf(e.majorVersion()))
                    && (implementor.isEmpty()
                            || implementor
                                    .toLowerCase(Locale.ROOT)
                                    .contains(e.product().toLowerCase(Locale.ROOT)));
        }
        // No catalog match — accept JAVA_HOME if its version satisfies a
        // bare-version spec (e.g., `.jdk-version` says `21` and JAVA_HOME is JDK 21).
        if (!spec.bareVersion()) return false;
        return version.equals(spec.value()) || version.startsWith(spec.value() + ".");
    }

    private static Properties readRelease(Path home) {
        Properties props = new Properties();
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) return props;
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException ignored) {
            // Treat unreadable release as no JDK metadata.
        }
        return props;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Convenience to read installed JDKs without going through the catalog. */
    public List<InstalledJdk> installed() throws IOException {
        return registry.list();
    }
}
