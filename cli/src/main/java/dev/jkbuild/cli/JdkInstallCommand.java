// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.jdk.JdkSpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk install <spec>} — pull a JDK from the JetBrains JDK feed
 * and unpack it into the IntelliJ JDK directory ({@code ~/.jdks/} or
 * {@code ~/Library/Java/JavaVirtualMachines/}).
 *
 * <p>The {@code <spec>} matches the feed's vocabulary:
 * <ul>
 *   <li>{@code 21}, {@code 21.0.5} — bare version; picks whichever vendor
 *       the feed marks {@code default: true} for that major (OpenJDK,
 *       today).</li>
 *   <li>{@code temurin-21}, {@code openjdk-26}, {@code corretto-21.0.5} —
 *       a {@code suggested_sdk_name} from the feed.</li>
 * </ul>
 */
@Command(name = "install", aliases = {"add"},
        description = "Download and install a JDK from the JetBrains feed")
public final class JdkInstallCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<spec>",
            description = "Version or suggested-SDK-name (e.g. 21, 21.0.5, temurin-21, openjdk-26).")
    String spec;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Option(names = "--feed-url", hidden = true,
            description = "Override the JetBrains JDK feed URL (for tests).")
    URI feedUrl;

    @Option(names = "--cache-file", hidden = true,
            description = "Override the catalog cache path (for tests).")
    Path cacheFile;

    @Override
    public Integer call() throws Exception {
        if (!HostPlatform.supported()) {
            System.err.println("jk jdk install: host "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                    + " is not covered by the JetBrains JDK feed. Set JAVA_HOME explicitly.");
            return 1;
        }

        JdkSpec parsed = JdkSpec.parse(spec);
        JdkCatalogClient client = feedUrl != null
                ? new JdkCatalogClient(new Http(), feedUrl,
                        cacheFile != null ? cacheFile : ephemeralCachePath(),
                        java.time.Duration.ZERO)
                : new JdkCatalogClient();
        JdkCatalog catalog = client.fetch();

        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        Optional<JdkCatalog.Entry> entry = JdkSelector.select(catalog, parsed, os, arch);
        if (entry.isEmpty()) {
            System.err.println("jk jdk install: no JDK matches " + spec
                    + " on " + os + "/" + arch);
            return 1;
        }

        JdkCatalog.Entry e = entry.get();
        System.out.println("Installing " + e.vendor() + " " + e.product() + " " + e.version()
                + " (" + e.os() + "/" + e.arch() + ")...");

        Path jdksRoot = jdksDir != null ? jdksDir : IntellijJdkDir.root();
        JdkRegistry registry = new JdkRegistry(jdksRoot);
        InstalledJdk installed = new JdkInstaller(new Http(), registry).install(e);

        System.out.println("Installed " + installed.identifier() + " at " + installed.home());
        return 0;
    }

    private static Path ephemeralCachePath() throws java.io.IOException {
        Path tmp = java.nio.file.Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        java.nio.file.Files.delete(tmp);  // force a fresh fetch (file present but empty would parse-fail)
        return tmp;
    }
}
