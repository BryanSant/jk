// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.resolver.Versions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk list} — installed JDKs (under the IntelliJ JDK directory)
 * alongside the catalog entries available for download from the
 * JetBrains feed for the current OS / arch.
 *
 * <p>Network failure or {@code --offline} skips the catalog half.
 */
@Command(name = "list", description = "List the available JDK installations")
public final class JdkListCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Option(names = "--offline",
            description = "Skip the JetBrains catalog fetch; show only installed JDKs.")
    boolean offline;

    @Option(names = "--feed-url", hidden = true,
            description = "Override the JetBrains JDK feed URL (for tests).")
    URI feedUrl;

    @Option(names = "--cache-file", hidden = true,
            description = "Override the catalog cache path (for tests).")
    Path cacheFile;

    @Override
    public Integer call() throws Exception {
        Path jdksRoot = jdksDir != null ? jdksDir : IntellijJdkDir.root();
        List<InstalledJdk> installed = new JdkRegistry(jdksRoot).list();
        List<InstalledJdk> installedSorted = installed.stream()
                .sorted(Comparator.comparing(InstalledJdk::identifier).reversed())
                .toList();

        List<JdkCatalog.Entry> remoteOnly = offline ? List.of() : fetchRemoteOnly(installed);

        if (installedSorted.isEmpty() && remoteOnly.isEmpty()) {
            System.out.println("(no JDKs installed under " + jdksRoot
                    + (offline ? "" : ", no remote JDKs found") + ")");
            return 0;
        }

        int width = Math.max(
                installedSorted.stream().mapToInt(j -> j.identifier().length()).max().orElse(0),
                remoteOnly.stream().mapToInt(e -> e.installFolderName().length()).max().orElse(0));

        for (InstalledJdk jdk : installedSorted) {
            System.out.printf("%-" + width + "s  %s%n", jdk.identifier(), jdk.home());
        }
        for (JdkCatalog.Entry pkg : remoteOnly) {
            System.out.printf("%-" + width + "s  %s%n",
                    pkg.installFolderName(), "<download available>");
        }
        return 0;
    }

    private List<JdkCatalog.Entry> fetchRemoteOnly(List<InstalledJdk> installed) {
        if (!HostPlatform.supported()) {
            return List.of();
        }
        try {
            JdkCatalogClient client = feedUrl != null
                    ? new JdkCatalogClient(new Http(), feedUrl,
                            cacheFile != null ? cacheFile : ephemeralCachePath(),
                            java.time.Duration.ZERO)
                    : new JdkCatalogClient();
            JdkCatalog catalog = client.fetch();
            String os = HostPlatform.currentOs();
            String arch = HostPlatform.currentArch();

            Set<String> installedIds = new HashSet<>();
            for (InstalledJdk j : installed) installedIds.add(j.identifier());

            // Collapse to one row per suggested_sdk_name × major version
            // (current host only). Newest version first within each row.
            Map<String, JdkCatalog.Entry> byName = new LinkedHashMap<>();
            for (JdkCatalog.Entry e : catalog.entries()) {
                if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
                if (installedIds.contains(e.installFolderName())) continue;
                String key = e.suggestedSdkName();
                JdkCatalog.Entry existing = byName.get(key);
                if (existing == null
                        || Versions.compare(e.version(), existing.version()) > 0) {
                    byName.put(key, e);
                }
            }
            List<JdkCatalog.Entry> out = new ArrayList<>(byName.values());
            out.sort(Comparator
                    .comparing(JdkCatalog.Entry::preview)
                    .thenComparing(JdkCatalog.Entry::majorVersion, Comparator.reverseOrder())
                    .thenComparing(JdkCatalog.Entry::vendor));
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("jk jdk list: JetBrains feed unreachable ("
                    + e.getMessage() + "); showing installed JDKs only.");
            return List.of();
        }
    }

    private static Path ephemeralCachePath() throws IOException {
        Path tmp = java.nio.file.Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        java.nio.file.Files.delete(tmp);
        return tmp;
    }
}
