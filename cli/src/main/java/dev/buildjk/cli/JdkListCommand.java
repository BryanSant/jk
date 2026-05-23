// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.discovery.SymlinkProvisioner;
import dev.buildjk.http.Http;
import dev.buildjk.jdk.DiscoClient;
import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.jdk.JdkPackage;
import dev.buildjk.jdk.JdkRegistry;
import dev.buildjk.jdk.Platform;
import dev.buildjk.resolver.Versions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk list} — show installed JDKs alongside the versions
 * available for download. Modelled after {@code uv python list}:
 *
 * <ul>
 *   <li>Installed entries first, newest version first, with the local
 *       install path (or symlink target) in the right column.</li>
 *   <li>Remote-only entries below, also newest first, marked
 *       {@code <download available>}. Remote queries to the foojay Disco
 *       API are filtered to the current OS + arch + lib-C runtime.</li>
 * </ul>
 *
 * <p>Network failure is non-fatal: a warning prints to stderr and the
 * command falls back to just listing the installed set.
 */
@Command(name = "list", description = "List the available JDK installations")
public final class JdkListCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Option(names = "--offline",
            description = "Skip the Disco API query; show only installed JDKs.")
    boolean offline;

    @Option(names = "--disco-url", hidden = true,
            description = "Override the foojay Disco API base URL (for tests).")
    URI discoUrl;

    @Override
    public Integer call() throws Exception {
        Path jdksRoot = jdksDir != null
                ? jdksDir
                : Path.of(System.getProperty("user.home"), ".jk", "jdks");

        List<InstalledJdk> installed = new JdkRegistry(jdksRoot).list();
        List<InstalledJdk> installedSorted = installed.stream()
                .sorted(Comparator.comparing((InstalledJdk j) -> extractVersion(j.identifier()),
                        Versions::compare).reversed())
                .toList();

        List<JdkPackage> remoteOnly = offline ? List.of() : fetchRemoteOnly(installed);

        if (installedSorted.isEmpty() && remoteOnly.isEmpty()) {
            System.out.println("(no JDKs installed under " + jdksRoot
                    + (offline ? "" : ", no remote JDKs found") + ")");
            return 0;
        }

        int width = Math.max(
                installedSorted.stream().mapToInt(j -> j.identifier().length()).max().orElse(0),
                remoteOnly.stream().mapToInt(p -> p.installIdentifier().length()).max().orElse(0));

        for (InstalledJdk jdk : installedSorted) {
            System.out.printf("%-" + width + "s  %s%n",
                    jdk.identifier(), localLabel(jdk));
        }
        for (JdkPackage pkg : remoteOnly) {
            System.out.printf("%-" + width + "s  %s%n",
                    pkg.installIdentifier(), "<download available>");
        }
        return 0;
    }

    private List<JdkPackage> fetchRemoteOnly(List<InstalledJdk> installed) {
        try {
            DiscoClient client = discoUrl != null
                    ? new DiscoClient(new Http(), discoUrl)
                    : new DiscoClient();
            DiscoClient.SearchQuery query = DiscoClient.SearchQuery.builder()
                    .architecture(Platform.currentArchitecture())
                    .operatingSystem(Platform.currentOperatingSystem())
                    .archiveType(Platform.currentArchiveType())
                    .libCType(Platform.currentLibCType())
                    .build();
            List<JdkPackage> remote = client.search(query);

            Set<String> installedIds = new HashSet<>();
            for (InstalledJdk j : installed) installedIds.add(j.identifier());

            return remote.stream()
                    .filter(p -> !installedIds.contains(p.installIdentifier()))
                    .sorted(Comparator.comparing(JdkPackage::version, Versions::compare).reversed())
                    .toList();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("jk jdk list: Disco API unreachable ("
                    + e.getMessage() + "); showing installed JDKs only.");
            return List.of();
        }
    }

    private static String localLabel(InstalledJdk jdk) {
        Path home = jdk.home();
        if (!Files.isSymbolicLink(home)) {
            return home.toString();
        }
        if (SymlinkProvisioner.isBrokenLink(home)) {
            return "<broken link → " + readlinkOr(home) + ">";
        }
        return "→ " + readlinkOr(home);
    }

    private static String readlinkOr(Path link) {
        try { return Files.readSymbolicLink(link).toString(); }
        catch (IOException e) { return "?"; }
    }

    /**
     * Pull the version segment off the front of an identifier:
     * {@code 21.0.5-tem-x64-linux} → {@code 21.0.5}. Modern JDK versions
     * use {@code +<build>} (not {@code -}) so the first dash is always
     * the version/vendor boundary.
     */
    static String extractVersion(String identifier) {
        int dash = identifier.indexOf('-');
        return dash < 0 ? identifier : identifier.substring(0, dash);
    }
}
